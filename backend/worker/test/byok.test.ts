import {env} from 'cloudflare:workers';
import {runInDurableObject,evictDurableObject} from 'cloudflare:test';
import {beforeAll,afterAll,beforeEach,afterEach,test,expect,vi} from 'vitest';
import {setupNetwork} from '@msw/cloudflare';
import {http} from 'msw';
import worker,{RequestGate} from '../src/index';
import type {Bindings} from '../src/env';
import {sha256,signingText} from '../src/auth';
import {input,httpRequest,rawHttpRequest,getRequest,completion,completionBody,API_KEY,ACCESS,hints} from './fixtures';
import {FINGERPRINT_SECRET,PRIVATE_JWK} from './client-fixture';
const network=setupNetwork();beforeAll(()=>network.enable());afterAll(()=>network.disable());
let id:DurableObjectId;const stub=()=>env.REQUEST_GATE.get(id);const bindings=()=>env as unknown as Bindings;
type Upstream=Pick<Request,'url'|'method'|'headers'|'redirect'>;
const chat=vi.fn(async(_request:Upstream)=>completion());
const balance=vi.fn(async(_request:Upstream)=>Response.json({is_available:true,balance_infos:[{currency:'CNY',total_balance:'10.00',granted_balance:'0.00',topped_up_balance:'10.00'}]}));
let restore:(()=>void)|undefined;
beforeEach(async()=>{vi.restoreAllMocks();id=env.REQUEST_GATE.newUniqueId();vi.spyOn(env.REQUEST_GATE,'idFromName').mockReturnValue(id);chat.mockReset();chat.mockImplementation(async()=>completion());balance.mockReset();balance.mockImplementation(async()=>Response.json({is_available:true,balance_infos:[{currency:'CNY',total_balance:'10.00',granted_balance:'0.00',topped_up_balance:'10.00'}]}));network.resetHandlers();network.use(http.post('https://api.deepseek.com/chat/completions',({request})=>chat(request)),http.get('https://api.deepseek.com/user/balance',({request})=>balance(request)),http.all('*',()=>new Response(null,{status:599})));});
afterEach(async()=>{restore?.();restore=undefined;await runInDurableObject(stub(),async(_instance:RequestGate,state)=>state.storage.deleteAll());network.resetHandlers();});
const call=async(request:Request,overrides:Partial<Bindings>={})=>{const result=await worker.fetch(request,{...bindings(),...overrides});return {status:result.status,body:await result.json() as any,headers:result.headers};};
const verify=(id=crypto.randomUUID(),headers:HeadersInit={})=>rawHttpRequest(JSON.stringify({schemaVersion:1,requestId:id}),headers,'https://unit.test/v1/ai/verify');
const stored=()=>runInDurableObject(stub(),async(_instance:RequestGate,state)=>[...await state.storage.list()]);

test('a request must prove an approved private key; the former Bearer and self-declared clients are insufficient',async()=>{
  for(const auth of ['Bearer '+ACCESS,'Signature unregistered_client','Signature '+ACCESS+'x'])expect((await call(await httpRequest(input(),{Authorization:auth}))).status).toBe(401);
  expect(env.REQUEST_GATE.idFromName).not.toHaveBeenCalled();expect(chat).not.toHaveBeenCalled();expect(balance).not.toHaveBeenCalled();
});
test('HTTPS, fixed routes and parameter allowlists reject arbitrary targets and old shared-key routes',async()=>{
  const denied=await call(await rawHttpRequest(JSON.stringify(input()),{},'http://unit.test/v1/ai/recommend'));expect(denied.body.error.code).toBe('HTTPS_REQUIRED');
  for(const path of ['/v1/recommendations/hints','/v1/quota','/v1/ai/recommend?target=https://evil.invalid','/proxy'])expect((await call(new Request('https://unit.test'+path))).status).toBe(404);
  expect((await call(await httpRequest({...input(),targetUrl:'https://evil.invalid'}))).body.error.code).toBe('INVALID_SCHEMA');expect(chat).not.toHaveBeenCalled();
});
test.each(['','TEST ONLY_PROVIDER_xxxxxxxxx','short'])('missing or malformed per-request Key never falls back to a developer Key: %s',async value=>{
  const r=await call(await httpRequest(input(),{'X-DeepSeek-Key':value}),{...bindings(),DEEPSEEK_API_KEY:API_KEY} as Bindings);expect(r.status).toBe(400);expect(['KEY_REQUIRED','KEY_INVALID']).toContain(r.body.error.code);expect(chat).not.toHaveBeenCalled();expect(await stored()).toEqual([]);
});
test.each([-60001,60001])('server freshness rejects signed timestamps outside the window: %i',async delta=>{
  const now=Date.now();vi.spyOn(Date,'now').mockReturnValue(now);const r=await call(await httpRequest(input(),{'X-Yingji-Timestamp':String(now+delta)}));expect(r.status).toBe(401);expect(r.body.error.code).toBe('REQUEST_EXPIRED');expect(chat).not.toHaveBeenCalled();
});
test('signature binds body, API key, route and origin before any storage access',async()=>{
  const body=input();const original=await httpRequest(body);
  for(const request of [new Request(original.url,{method:'POST',headers:original.headers,body:JSON.stringify({...body,profileVersion:2})}),new Request(original.url,{method:'POST',headers:{...Object.fromEntries(original.headers),'x-deepseek-key':API_KEY+'_changed'},body:JSON.stringify(body)}),new Request('https://unit.test/v1/ai/verify',{method:'POST',headers:original.headers,body:JSON.stringify(body)}),new Request('https://other.test/v1/ai/recommend',{method:'POST',headers:original.headers,body:JSON.stringify(body)})]){expect((await call(request)).body.error.code).toBe('UNAUTHORIZED');}
  expect(env.REQUEST_GATE.idFromName).not.toHaveBeenCalled();expect(chat).not.toHaveBeenCalled();
});
test('exact signed packets cannot replay after eviction, while newly signed same-task queries are idempotent',async()=>{
  const body=input(),request=await httpRequest(body),replay=request.clone();expect((await call(request)).status).toBe(200);await evictDurableObject(stub());const denied=await call(replay);expect(denied.status).toBe(409);expect(denied.body.error.code).toBe('NONCE_REPLAY');expect((await call(await httpRequest(body))).status).toBe(200);expect(chat).toHaveBeenCalledTimes(1);
});
test('concurrent exact packets dispatch at most once',async()=>{
  const request=await httpRequest(input());const results=await Promise.all([call(request.clone()),call(request.clone()),call(request.clone())]);expect(results.filter(r=>r.status===200)).toHaveLength(1);expect(results.filter(r=>r.body.error?.code==='NONCE_REPLAY')).toHaveLength(2);expect(chat).toHaveBeenCalledTimes(1);
});
test('each recommendation uses its current Key, while changed-Key reuse of an id conflicts',async()=>{
  const body=input(),other=API_KEY+'_other';expect((await call(await httpRequest(body))).status).toBe(200);expect((await call(await httpRequest(body,{'X-DeepSeek-Key':other}))).body.error.code).toBe('REQUEST_ID_CONFLICT');expect((await call(await httpRequest(input(),{'X-DeepSeek-Key':other}))).status).toBe(200);expect(chat.mock.calls.map(row=>row[0].headers.get('authorization'))).toEqual(['Bearer '+API_KEY,'Bearer '+other]);expect(chat).toHaveBeenCalledTimes(2);
});
test('raw Key, fingerprint Secret, private signer and full query/profile never enter DO storage, responses or logs',async()=>{
  const logs:string[]=[];for(const method of ['log','info','warn','error','debug'] as const)vi.spyOn(console,method).mockImplementation((...values)=>{logs.push(values.map(String).join(' '));});
  const body=input({mode:'QUERY',userQuery:'PRIVATE_FULL_QUERY',profileSummary:{enabledSources:{ratings:false,behavior:false,feedback:true},explicit:[{topic:'PRIVATE_EXPLICIT_TOPIC',direction:'LIKE'}],inferred:[]}});const r=await call(await httpRequest(body));expect(r.status).toBe(200);const records=await stored(),serialized=JSON.stringify({records,response:r.body,logs});for(const value of [API_KEY,FINGERPRINT_SECRET,PRIVATE_JWK.d,'PRIVATE_FULL_QUERY','PRIVATE_EXPLICIT_TOPIC'])expect(serialized).not.toContain(value);
  const job=records.find(([key])=>key==='job:'+body.requestId)![1] as any;expect(job.credentialBinding).toMatch(/^[a-f0-9]{64}$/);expect(job.credentialBinding).not.toBe(await sha256(API_KEY));
});
test('escaped secrets in decoded model output are rejected before persistence',async()=>{
  const value=completionBody({...hints(),preferredTopics:[API_KEY]});const raw=JSON.stringify(value).replaceAll('TEST_ONLY_PROVIDER','\\u0054EST_ONLY_PROVIDER');chat.mockImplementationOnce(async()=>new Response(raw));const r=await call(await httpRequest(input()));expect(r.body.error.code).toBe('MODEL_FORMAT');expect(JSON.stringify(await stored())).not.toContain(API_KEY);
});
test('connection verification uses only fixed account lookup; it never creates a model task or exposes balance',async()=>{
  const id=crypto.randomUUID(),r=await call(await verify(id));expect(r.status).toBe(200);expect(r.body).toMatchObject({schemaVersion:1,requestId:id,status:'success',verified:true,error:null,usage:{httpRequests:1,retries:0,totalTokens:0},quota:{used:0,reserved:0,remaining:30}});expect(r.body.verifiedAt).toBeTypeOf('number');expect(JSON.stringify(r.body)).not.toContain('balance_infos');expect(JSON.stringify(await stored())).not.toContain('total_balance');expect(balance).toHaveBeenCalledTimes(1);expect(chat).not.toHaveBeenCalled();const sent=balance.mock.calls[0][0];expect(sent.url).toBe('https://api.deepseek.com/user/balance');expect(sent.method).toBe('GET');expect(sent.headers.get('authorization')).toBe('Bearer '+API_KEY);expect([...sent.headers.keys()]).toEqual(['authorization']);
  expect((await call(await verify(id))).body.verified).toBe(true);expect(balance).toHaveBeenCalledTimes(1);
});
test.each([[401,'UPSTREAM_AUTH'],[403,'UPSTREAM_FORBIDDEN'],[402,'UPSTREAM_BILLING'],[429,'UPSTREAM_RATE_LIMIT'],[500,'UPSTREAM_UNAVAILABLE'],[302,'UPSTREAM_UNAVAILABLE']])('verification maps provider %i without leaking or retrying',async(status,code)=>{
  balance.mockImplementationOnce(async()=>new Response('PRIVATE_PROVIDER '+API_KEY,{status:Number(status),headers:{Location:'https://evil.invalid'}}));const r=await call(await verify());expect(r.status).toBe(502);expect(r.body.error.code).toBe(code);expect(r.body.verified).toBe(false);expect(JSON.stringify(r.body)).not.toContain(API_KEY);expect(JSON.stringify(await stored())).not.toContain(API_KEY);expect(balance).toHaveBeenCalledTimes(1);expect(chat).not.toHaveBeenCalled();expect(balance.mock.calls[0][0].redirect).toBe('manual');
});
test('authenticated but unavailable balance is explicit and never marked verified',async()=>{
  balance.mockImplementationOnce(async()=>Response.json({is_available:false,balance_infos:[]}));const r=await call(await verify());expect(r.body.error.code).toBe('UPSTREAM_BILLING');expect(r.body.verified).toBe(false);expect(r.body.verifiedAt).toBeNull();
});
test('verification and recommendation share one flight and verification duplicates stay pending',async()=>{
  let release!:()=>void,started!:()=>void;const ready=new Promise<void>(r=>started=r),held=new Promise<void>(r=>release=r);balance.mockImplementationOnce(async()=>{started();await held;return Response.json({is_available:true,balance_infos:[]});});const id=crypto.randomUUID(),first=call(await verify(id));await ready;try{expect((await call(await verify(id))).status).toBe(202);expect((await call(await verify())).body.error.code).toBe('REQUEST_IN_PROGRESS');expect((await call(await httpRequest(input()))).body.error.code).toBe('REQUEST_IN_PROGRESS');}finally{release();}expect((await first).status).toBe(200);expect(balance).toHaveBeenCalledTimes(1);expect(chat).not.toHaveBeenCalled();
});
test('verification failure to save cannot report success or repeat the lookup',async()=>{
  await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{const original=state.storage.transaction.bind(state.storage);let count=0;const fault=vi.spyOn(state.storage,'transaction').mockImplementation(async fn=>{if(++count===2)throw Error(API_KEY);return original(fn);});restore=()=>fault.mockRestore();});const id=crypto.randomUUID(),r=await call(await verify(id));expect(r.status).toBe(503);expect(r.body.verified).toBe(false);expect(r.body.error.code).toBe('STORAGE_UNAVAILABLE');expect((await call(await verify(id))).status).toBe(202);expect(balance).toHaveBeenCalledTimes(1);
});
test('nonce reservation failure prevents any upstream call',async()=>{
  await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{const fault=vi.spyOn(state.storage,'transaction').mockRejectedValueOnce(Error(API_KEY));restore=()=>fault.mockRestore();});const r=await call(await httpRequest(input()));expect(r.status).toBe(503);expect(r.body.error.code).toBe('STORAGE_UNAVAILABLE');expect(chat).not.toHaveBeenCalled();
});
test('health and quota are signed keyless reads, disclose fee ownership, and never call provider',async()=>{
  const r=await call(await getRequest('/v1/ai/health'));expect(r.status).toBe(200);expect(r.headers.get('x-yingji-api-contract')).toBe('byok-1');expect(r.body).toMatchObject({apiContract:'byok-1',fees:{deepseek:'USER_ACCOUNT',worker:'DEPLOYER_ACCOUNT',quotaIsProviderBalance:false}});expect((await call(await getRequest('/v1/ai/quota'))).body.quota.used).toBe(0);expect((await call(await getRequest('/v1/ai/health',{'X-DeepSeek-Key':API_KEY}))).status).toBe(400);expect(chat).not.toHaveBeenCalled();expect(balance).not.toHaveBeenCalled();
});
test('signed free endpoints cannot bypass the persisted access rate limit',async()=>{
  await runInDurableObject(stub(),async(_instance:RequestGate,state)=>state.storage.put({'auth:recent':Array(120).fill(Date.now()),'auth:version':1}));const r=await call(await getRequest('/v1/ai/health'));expect(r.status).toBe(429);expect(r.body.error.code).toBe('ACCESS_RATE_LIMITED');expect(chat).not.toHaveBeenCalled();
});

test('a missing initialized authentication bucket fails closed instead of resetting access limits',async()=>{
  expect((await call(await getRequest('/v1/ai/health'))).status).toBe(200);await runInDurableObject(stub(),async(_instance:RequestGate,state)=>state.storage.delete('auth:recent'));const r=await call(await getRequest('/v1/ai/health'));expect(r.status).toBe(503);expect(r.body.error.code).toBe('STORAGE_UNAVAILABLE');expect(chat).not.toHaveBeenCalled();
});
test.each([-60000,60000])('exact signed clock boundary is accepted: %i',async delta=>{
  const now=Date.now();vi.spyOn(Date,'now').mockReturnValue(now);const r=await call(await httpRequest(input(),{'X-Yingji-Timestamp':String(now+delta)}));expect(r.status).toBe(200);expect(chat).toHaveBeenCalledTimes(1);
});
test('verification rate and idempotency persist across eviction without spending success quota',async()=>{
  const first=crypto.randomUUID();for(let i=0;i<6;i++)expect((await call(await verify(i===0?first:crypto.randomUUID()))).status).toBe(200);await evictDurableObject(stub());expect((await call(await verify())).body.error.code).toBe('RATE_LIMITED');expect((await call(await verify(first))).status).toBe(200);expect(balance).toHaveBeenCalledTimes(6);expect((await call(await getRequest('/v1/ai/quota'))).body.quota.used).toBe(0);
});

test('JSON-escaped credentials cannot be smuggled into the model prompt or durable values',async()=>{
  const body=input({mode:'QUERY',userQuery:API_KEY});const encoded=Array.from(API_KEY).map(c=>String.fromCharCode(92)+'u'+c.charCodeAt(0).toString(16).padStart(4,'0')).join('');const raw=JSON.stringify(body).replace(API_KEY,encoded);expect(raw).not.toContain(API_KEY);const r=await call(await rawHttpRequest(raw));expect(r.status).toBe(400);expect(r.body.error.code).toBe('INVALID_SCHEMA');expect(chat).not.toHaveBeenCalled();expect(await stored()).toEqual([]);
});

test('expired nonce cleanup cannot deadlock a full historical nonce bucket or remove task data',async()=>{
  const body=input();expect((await call(await httpRequest(body))).status).toBe(200);await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{const records:Record<string,number>={};for(let i=0;i<512;i++)records['auth:nonce:old:'+i]=Date.now()-1;await state.storage.put(records);});expect((await call(await getRequest('/v1/ai/health'))).status).toBe(200);expect((await call(await httpRequest(body))).status).toBe(200);expect(chat).toHaveBeenCalledTimes(1);expect((await stored()).some(([key])=>key==='job:'+body.requestId)).toBe(true);
});

test('signature text matches the independently specified LF and empty-body SHA-256 vector',async()=>{
  const empty='e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',nonce='00000000-0000-4000-8000-000000000000';const request=new Request('https://unit.test/v1/ai/health',{headers:{Authorization:'Signature '+ACCESS,'X-Yingji-Timestamp':'1700000000000','X-Yingji-Nonce':nonce}});expect(await signingText(request,'','')).toBe(['YINGJI-BYOK-1','GET','https://unit.test/v1/ai/health','Signature '+ACCESS,'1700000000000',nonce,empty,empty].join(String.fromCharCode(10)));
});
test('a prepended UTF-8 BOM changes the signed bytes and cannot reuse a valid proof',async()=>{
  const body=input(),request=await httpRequest(body);const altered=new Request(request.url,{method:'POST',headers:request.headers,body:String.fromCharCode(65279)+JSON.stringify(body)});const r=await call(altered);expect(r.status).toBe(401);expect(r.body.error.code).toBe('UNAUTHORIZED');expect(chat).not.toHaveBeenCalled();expect(await stored()).toEqual([]);
});
