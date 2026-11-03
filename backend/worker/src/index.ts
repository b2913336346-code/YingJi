import { DurableObject } from 'cloudflare:workers';
import type { Bindings } from './env';
import { runDeepSeek, verifyDeepSeek, providerConfiguration } from './deepseek';
import { Ledger, jobKey, type SavedResponse, type Job } from './ledger';
import { ApiError, emptyUsage, plain, hasSensitiveText, readLimitedText, parseRequest, response, type HintRequest, type Usage } from './protocol';
import { API_CONTRACT, PRINCIPAL, authConfiguration, consumeProof, keyBinding, parseJson, parseVerify, readIncoming, sha256, type Incoming, type VerifyRequest } from './auth';

const RATE_LIMIT=6;
interface Version { profile:number; sources:number }
type Operation='recommend'|'verify'|'quota'|'health';
const internalError=(usage=emptyUsage())=>new ApiError('STORAGE_UNAVAILABLE',503,true,{...usage,complete:false});
const safeHeaders={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','X-Yingji-Api-Contract':API_CONTRACT};
function route(request:Request):Operation {
  const url=new URL(request.url);const routes:Record<string,Operation>={'/v1/ai/recommend':'recommend','/v1/ai/verify':'verify','/v1/ai/quota':'quota','/v1/ai/health':'health'};
  if(url.protocol!=='https:')throw new ApiError('HTTPS_REQUIRED',400);
  if(!Object.hasOwn(routes,url.pathname)||url.search||url.hash||url.username||url.password)throw new ApiError('NOT_FOUND',404);
  const operation=routes[url.pathname];if(request.method!==(operation==='quota'||operation==='health'?'GET':'POST'))throw new ApiError('METHOD_NOT_ALLOWED',405);return operation;
}
function verifyResponse(input:VerifyRequest|null,status:'success'|'pending'|'error',usage=emptyUsage(),error:ApiError|null=null,verifiedAt:number|null=null):Response {
  return Response.json({schemaVersion:1,requestId:input?.requestId??null,status,verified:status==='success',verifiedAt,usage,quota:{status:'UNAVAILABLE'},error:error?{code:error.code,retryable:error.retryable}:null},
    {status:error?.status??(status==='pending'?202:200),headers:{...safeHeaders,...(status==='pending'?{'Retry-After':'1'}:{})}});
}
function errorResponse(error:unknown,input:HintRequest|null=null):Response {
  const failure=error instanceof ApiError?error:new ApiError('SERVICE_UNAVAILABLE',503,true);
  if(failure.code==='OUTCOME_UNKNOWN'){failure.usage.complete=false;failure.usage.promptTokens=null;failure.usage.completionTokens=null;failure.usage.totalTokens=null;}
  return response(input,'error',null,failure.usage,failure);
}
export function configurationError(env:Bindings):ApiError|null {
  try{authConfiguration(env);}catch{return new ApiError('CONFIGURATION_ERROR',503);}
  if(env.LIVE_AI_ENABLED!=='true')return new ApiError('SERVICE_DISABLED',503);
  if(env.DEEPSEEK_MODEL!=='deepseek-flash'||!['MOCK','LIVE'].includes(env.UPSTREAM_MODE))return new ApiError('CONFIGURATION_ERROR',503);
  return null;
}
export default {
  async fetch(request:Request,env:Bindings):Promise<Response>{
    let input:HintRequest|null=null;let operation:Operation|undefined;
    try {
      operation=route(request);const post=operation==='recommend'||operation==='verify';
      const incoming=await readIncoming(request,env,post);
      if(post){const invalid=configurationError(env);if(invalid)throw invalid;providerConfiguration(env,{key:incoming.key!,clientId:incoming.proof.clientId});}
      if(operation==='recommend')input=parseRequest(parseJson(incoming.raw));else if(operation==='verify')parseVerify(incoming.raw);
      try {
        // Credentials remain in this request's headers only. They are never included in a storage value.
        const headers=new Headers();for(const name of ['authorization','content-type','x-deepseek-key','x-yingji-auth-version','x-yingji-timestamp','x-yingji-nonce','x-yingji-signature']){const value=request.headers.get(name);if(value!==null)headers.set(name,value);}
        const result=await env.REQUEST_GATE.get(env.REQUEST_GATE.idFromName(PRINCIPAL)).fetch(request.url,{method:request.method,headers,...(post?{body:incoming.raw}:{})});
        if(!result.headers.get('content-type')?.startsWith('application/json'))throw internalError();
        const raw=await readLimitedText(result,32768);if((incoming.key&&raw.includes(incoming.key))||raw.includes(env.FINGERPRINT_SECRET))throw internalError();
        const body:unknown=JSON.parse(raw);
        if(hasSensitiveText(body,[incoming.key??'',env.FINGERPRINT_SECRET]))throw internalError();
        if(!plain(body)||body.schemaVersion!==1||!['success','pending','error'].includes(String(body.status))||(operation!=='health'&&!plain(body.quota))||
          (operation==='recommend'&&(body.requestId!==input!.requestId||body.profileVersion!==input!.profileVersion||body.sourceSettingsVersion!==input!.sourceSettingsVersion))||
          (operation==='verify'&&body.requestId!==parseVerify(incoming.raw).requestId)||
          (operation==='health'&&body.status==='success'&&body.apiContract!==API_CONTRACT))throw internalError();
        const outputHeaders=new Headers(result.headers);for(const [name,value]of Object.entries(safeHeaders))outputHeaders.set(name,value);
        if(result.status===401)outputHeaders.set('WWW-Authenticate','Signature');
        return Response.json(body,{status:result.status,headers:outputHeaders});
      }catch{throw internalError();}
    }catch(error){
      const failure=error instanceof ApiError?error:new ApiError('SERVICE_UNAVAILABLE',503,true);
      const result=operation==='verify'?verifyResponse(null,'error',failure.usage,failure):errorResponse(error,input);
      for(const [k,v]of Object.entries(safeHeaders))result.headers.set(k,v);
      if(failure.code==='METHOD_NOT_ALLOWED'&&['/v1/ai/quota','/v1/ai/health'].includes(new URL(request.url).pathname))result.headers.set('Allow','GET');
      if(failure.status===401)result.headers.set('WWW-Authenticate','Signature');return result;
    }
  },
} satisfies ExportedHandler<Bindings>;
const verifyJobKey=(id:string)=>'verify:job:'+id;
const activeJob=(storage:DurableObjectTransaction,id:string)=>storage.get<Job>(id.startsWith('verify:')?verifyJobKey(id.slice(7)):jobKey(id));

/** Same personal namespace, recommendation job keys and accounting ledger; no destructive migration. */
export class RequestGate extends DurableObject<Bindings> {
  async fetch(request:Request):Promise<Response>{
    const ledger=new Ledger(this.ctx.storage,this.env,480);
    let input:HintRequest|null=null;
    const withQuota=async(result:Response)=>{const body:unknown=await result.json();if(!plain(body))throw internalError();const view=await ledger.view();return Response.json({...body,quota:view.quota},{status:result.status,headers:result.headers});};
    try {
      const operation=route(request),incoming=await readIncoming(request,this.env,operation==='recommend'||operation==='verify');
      if(operation==='quota'||operation==='health') {
        const body=await this.ctx.storage.transaction(async tx=>{await consumeProof(tx,incoming.proof);
          if(operation==='health')return {schemaVersion:1,status:'success',apiContract:API_CONTRACT,model:this.env.DEEPSEEK_MODEL,liveAiEnabled:this.env.LIVE_AI_ENABLED==='true',fees:{deepseek:'USER_ACCOUNT',worker:'DEPLOYER_ACCOUNT',quotaIsProviderBalance:false}};
          return {schemaVersion:1,status:'success',...await ledger.viewIn(tx,Date.now())};
        });return Response.json(body,{headers:safeHeaders});
      }
      const invalid=configurationError(this.env);if(invalid)throw invalid;
      providerConfiguration(this.env,{key:incoming.key!,clientId:incoming.proof.clientId});
      if(operation==='verify')return this.verify(incoming,ledger);
      input=parseRequest(parseJson(incoming.raw));
      const credentialBinding=await keyBinding(incoming.key!,this.env);
      const fingerprint=await sha256(JSON.stringify(input));
      let reserved:{kind:'new'}|{kind:'saved';value:SavedResponse}|{kind:'pending'}|{kind:'error';error:ApiError};
      try {
        reserved=await this.ctx.storage.transaction(async storage=>{
          await consumeProof(storage,incoming.proof);
          try {
          const versions=await storage.get<Version>('versions');
          if(versions&&(input!.profileVersion<versions.profile||input!.sourceSettingsVersion<versions.sources))return {kind:'error' as const,error:new ApiError('STALE_PROFILE',409)};
          const existing=await storage.get<Job>(jobKey(input!.requestId));
          if(existing&&(existing.fingerprint!==fingerprint||(existing.credentialBinding!==undefined&&existing.credentialBinding!==credentialBinding)))return {kind:'error' as const,error:new ApiError('REQUEST_ID_CONFLICT',409)};
          await storage.put('versions',{profile:input!.profileVersion,sources:input!.sourceSettingsVersion});
          if(existing?.response)return {kind:'saved' as const,value:existing.response};
          if(existing){if(Date.now()-existing.createdAt>20000)return {kind:'error' as const,error:new ApiError('OUTCOME_UNKNOWN',503)};return {kind:'pending' as const};}
          const active=await storage.get<string>('active');
          if(active){const pending=await activeJob(storage,active);return {kind:'error' as const,error:new ApiError(pending&&Date.now()-pending.createdAt>20000?'OUTCOME_UNKNOWN':'REQUEST_IN_PROGRESS',pending&&Date.now()-pending.createdAt>20000?503:409,Boolean(pending&&Date.now()-pending.createdAt<=20000))};}
          const now=Date.now();const recent=(await storage.get<number[]>('recent')??[]).filter(t=>t>now-60000);
          if(recent.length>=RATE_LIMIT)return {kind:'error' as const,error:new ApiError('RATE_LIMITED',429,true)};
          const day=await ledger.reserve(storage,now);
          await storage.put({[jobKey(input!.requestId)]:{fingerprint,createdAt:now,response:null,credentialBinding,billing:{day,settled:false}},active:input!.requestId,recent:[...recent,now]});
          return {kind:'new' as const};
          } catch(error) { if(error instanceof ApiError && !['STORAGE_UNAVAILABLE'].includes(error.code)) return {kind:'error' as const,error};throw error; }
        });
      }catch(error){throw error instanceof ApiError?error:internalError();}
      if(reserved.kind==='error')throw reserved.error;
      if(reserved.kind==='saved')return await withQuota(Response.json(reserved.value.body,{status:reserved.value.status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}}));
      if(reserved.kind==='pending')return await withQuota(response(input,'pending',null,{...emptyUsage(),complete:false,promptTokens:null,completionTokens:null,totalTokens:null}));
      let result:Response;let usage:Usage=emptyUsage();
      try{const generated=await runDeepSeek(input,this.env,{key:incoming.key!,clientId:incoming.proof.clientId},fetch,{begin:index=>ledger.begin(input!.requestId,index),finish:(index,result)=>ledger.finish(input!.requestId,index,result)});usage=generated.usage;result=response(input,'success',generated.hints,usage);}
      catch(error){if(error instanceof ApiError)usage=error.usage;result=errorResponse(error,input);}
      const saved={status:result.status,body:await result.json()};
      try {
        await this.ctx.storage.transaction(async storage=>{
          const versions=await storage.get<Version>('versions');
          if(versions&&(input!.profileVersion<versions.profile||input!.sourceSettingsVersion<versions.sources)){
            if(!plain(saved.body))throw internalError(usage);
            saved.status=409;saved.body={...saved.body,status:'error',recommendationHints:null,error:{code:'STALE_PROFILE',retryable:false}};
          }
          const quota=await ledger.settle(storage,input!.requestId,saved,Date.now());
          if(!plain(saved.body))throw internalError(usage);saved.body={...saved.body,quota};
          const job=await storage.get<Job>(jobKey(input!.requestId));await storage.put(jobKey(input!.requestId),{...job!,response:saved});
        });
      }catch{throw internalError(usage);}
      return Response.json(saved.body,{status:saved.status,headers:result.headers});
    }catch(error){
      try{return await withQuota(errorResponse(error,input));}catch{return errorResponse(internalError(error instanceof ApiError?error.usage:emptyUsage()),input);}
    }
  }
  private async verify(incoming:Incoming,ledger:Ledger):Promise<Response> {
    let input:VerifyRequest|null=null;
    const withQuota=async(result:Response)=>{const body=await result.json() as Record<string,unknown>;return Response.json({...body,quota:(await ledger.view()).quota},{status:result.status,headers:result.headers});};
    try {
      input=parseVerify(incoming.raw);const id=input.requestId,key=verifyJobKey(id),fingerprint=await sha256(JSON.stringify(input)),binding=await keyBinding(incoming.key!,this.env);
      const reservation=await this.ctx.storage.transaction(async tx=>{
        await consumeProof(tx,incoming.proof);
        const existing=await tx.get<Job>(key);
        if(existing&&(existing.fingerprint!==fingerprint||existing.credentialBinding!==binding))return {kind:'error' as const,error:new ApiError('REQUEST_ID_CONFLICT',409)};
        if(existing?.response)return {kind:'saved' as const,value:existing.response};
        if(existing)return Date.now()-existing.createdAt>20000?{kind:'error' as const,error:new ApiError('OUTCOME_UNKNOWN',503)}:{kind:'pending' as const};
        const active=await tx.get<string>('active');if(active){const pending=await activeJob(tx,active);const abandoned=pending&&Date.now()-pending.createdAt>20000;return {kind:'error' as const,error:new ApiError(abandoned?'OUTCOME_UNKNOWN':'REQUEST_IN_PROGRESS',abandoned?503:409,!abandoned)};}
        const now=Date.now(),recent=(await tx.get<number[]>('verify:recent')??[]).filter(t=>t>now-60000);if(recent.length>=6)return {kind:'error' as const,error:new ApiError('RATE_LIMITED',429,true)};
        await tx.put({[key]:{fingerprint,credentialBinding:binding,createdAt:now,response:null},active:'verify:'+id,'verify:recent':[...recent,now]});return {kind:'new' as const};
      });
      if(reservation.kind==='error')throw reservation.error;
      if(reservation.kind==='saved')return withQuota(Response.json(reservation.value.body,{status:reservation.value.status,headers:safeHeaders}));
      if(reservation.kind==='pending')return withQuota(verifyResponse(input,'pending',{...emptyUsage(),complete:false}));
      let result:Response;
      try {const usage=await verifyDeepSeek(this.env,{key:incoming.key!,clientId:incoming.proof.clientId});result=verifyResponse(input,'success',usage,null,Date.now());}
      catch(error){const failure=error instanceof ApiError?error:internalError();result=verifyResponse(input,'error',failure.usage,failure);}
      const saved={status:result.status,body:await result.json()};
      await this.ctx.storage.transaction(async tx=>{const job=await tx.get<Job>(key);if(!job||job.response||await tx.get('active')!=='verify:'+id)throw internalError();await tx.put(key,{...job,response:saved});await tx.delete('active');});
      return withQuota(Response.json(saved.body,{status:saved.status,headers:safeHeaders}));
    }catch(error){const failure=error instanceof ApiError?error:internalError();try{return await withQuota(verifyResponse(input,'error',failure.usage,failure));}catch{return verifyResponse(input,'error',failure.usage,failure);}}
  }
}
