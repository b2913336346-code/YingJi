import type { QuotaSnapshot } from './accounting';
export const SCHEMA_VERSION = 1;
export const MAX_BODY_BYTES = 16384;
export type Mode = 'FOR_YOU' | 'TONIGHT' | 'EXPLORE' | 'QUERY';
export interface Identity { sourceId: string; externalId: string }
export interface Conditions { requiredTopics: string[]; excludedTopics: string[]; minYear: number | null; maxYear: number | null; maxRuntimeMinutes: number | null }
export interface ProfileSummary {
  enabledSources: { ratings: boolean; behavior: boolean; feedback: boolean };
  explicit: { topic: string; direction: 'LIKE' | 'DISLIKE' }[];
  inferred: { topic: string; direction: 'LIKE' | 'DISLIKE'; source: 'RATING' | 'FEEDBACK' }[];
}
export interface HintRequest {
  schemaVersion: 1; requestId: string; mode: Mode; userQuery: string;
  externalProcessingConsent: true; profileVersion: number; sourceSettingsVersion: number;
  profileSummary: ProfileSummary; excludeExternalIds: Identity[]; hardConditions: Conditions;
}
export interface Hints { preferredTopics: string[]; conditions: Conditions }
export interface Usage { complete: boolean; httpRequests: number; retries: number; promptTokens: number | null; completionTokens: number | null; totalTokens: number | null }
export const emptyUsage = (): Usage => ({ complete: true, httpRequests: 0, retries: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0 });
export class ApiError extends Error {
  constructor(readonly code: string, readonly status: number, readonly retryable = false, readonly usage = emptyUsage()) { super(code); }
}
export const plain = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value);
export function object(value: unknown, keys: string[]): Record<string, unknown> {
  if (!plain(value) || Object.keys(value).length !== keys.length || Object.keys(value).some(key => !keys.includes(key))) throw new ApiError('INVALID_SCHEMA',400);
  return value;
}
function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || !value.trim() || Array.from(value).length > max || /[\u0000-\u001f]/u.test(value)) throw new ApiError('INVALID_SCHEMA',400);
  return value.trim();
}
function array(value: unknown, max: number): unknown[] { if (!Array.isArray(value) || value.length > max) throw new ApiError('INVALID_SCHEMA',400); return value; }
export function topics(value: unknown): string[] { const rows = array(value,12).map(item => text(item,60).normalize('NFKC').toLowerCase()); if (new Set(rows).size !== rows.length) throw new ApiError('INVALID_SCHEMA',400); return rows; }
function integer(value: unknown, min: number, max: number): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) throw new ApiError('INVALID_SCHEMA',400); return value; }
export function conditions(value: unknown): Conditions {
  const row=object(value,['requiredTopics','excludedTopics','minYear','maxYear','maxRuntimeMinutes']);
  const result: Conditions={ requiredTopics:topics(row.requiredTopics),excludedTopics:topics(row.excludedTopics),
    minYear:row.minYear===null?null:integer(row.minYear,1800,3000),maxYear:row.maxYear===null?null:integer(row.maxYear,1800,3000),
    maxRuntimeMinutes:row.maxRuntimeMinutes===null?null:integer(row.maxRuntimeMinutes,1,10000) };
  if ((result.minYear!==null && result.maxYear!==null && result.minYear>result.maxYear) || result.requiredTopics.some(t=>result.excludedTopics.includes(t))) throw new ApiError('CONFLICTING_CONDITIONS',422);
  return result;
}
export function parseRequest(value: unknown): HintRequest {
  const row=object(value,['schemaVersion','requestId','mode','userQuery','externalProcessingConsent','profileVersion','sourceSettingsVersion','profileSummary','excludeExternalIds','hardConditions']);
  if(row.schemaVersion!==1 || typeof row.requestId!=='string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(row.requestId) ||
     (typeof row.mode!=='string'||!['FOR_YOU','TONIGHT','EXPLORE','QUERY'].includes(row.mode)) || typeof row.userQuery!=='string' || Array.from(row.userQuery).length>500) throw new ApiError('INVALID_SCHEMA',400);
  if(row.externalProcessingConsent!==true) throw new ApiError('CONSENT_REQUIRED',403);
  if((row.mode==='QUERY' && !row.userQuery.trim()) || (row.mode!=='QUERY' && row.userQuery!=='')) throw new ApiError('INVALID_SCHEMA',400);
  const summary=object(row.profileSummary,['enabledSources','explicit','inferred']);
  const enabled=object(summary.enabledSources,['ratings','behavior','feedback']);
  if(Object.values(enabled).some(v=>typeof v!=='boolean')) throw new ApiError('INVALID_SCHEMA',400);
  const explicit=array(summary.explicit,20).map(value=>{ const e=object(value,['topic','direction']); if((typeof e.direction!=='string'||!['LIKE','DISLIKE'].includes(e.direction))) throw new ApiError('INVALID_SCHEMA',400);return {topic:text(e.topic,60).normalize('NFKC').toLowerCase(),direction:e.direction as 'LIKE'|'DISLIKE'}; });
  if(new Set(explicit.map(e=>e.topic)).size!==explicit.length) throw new ApiError('INVALID_SCHEMA',400);
  const inferred=array(summary.inferred,20).map(value=>{
    const e=object(value,['topic','direction','source']);if((typeof e.direction!=='string'||!['LIKE','DISLIKE'].includes(e.direction)) || (typeof e.source!=='string'||!['RATING','FEEDBACK'].includes(e.source))) throw new ApiError('INVALID_SCHEMA',400);
    if((e.source==='RATING'&&!enabled.ratings)||(e.source==='FEEDBACK'&&!enabled.feedback)) throw new ApiError('CONSENT_REQUIRED',403);
    return {topic:text(e.topic,60).normalize('NFKC').toLowerCase(),direction:e.direction as 'LIKE'|'DISLIKE',source:e.source as 'RATING'|'FEEDBACK'};
  });
  if(new Set(inferred.map(e=>e.source+':'+e.topic)).size!==inferred.length) throw new ApiError('INVALID_SCHEMA',400);
  const identities=array(row.excludeExternalIds,50).map(value=>{const e=object(value,['sourceId','externalId']);const id={sourceId:text(e.sourceId,100),externalId:text(e.externalId,200)};if(id.sourceId!==e.sourceId||id.externalId!==e.externalId)throw new ApiError('INVALID_SCHEMA',400);return id;});
  if(new Set(identities.map(e=>JSON.stringify(e))).size!==identities.length) throw new ApiError('INVALID_SCHEMA',400);
  const hard=conditions(row.hardConditions);
  if(hard.requiredTopics.some(t=>explicit.some(e=>e.topic===t&&e.direction==='DISLIKE')))throw new ApiError('CONFLICTING_CONDITIONS',422);
  return {schemaVersion:1,requestId:row.requestId.toLowerCase(),mode:row.mode as Mode,userQuery:row.userQuery.trim(),externalProcessingConsent:true,
    profileVersion:integer(row.profileVersion,0,Number.MAX_SAFE_INTEGER),sourceSettingsVersion:integer(row.sourceSettingsVersion,0,Number.MAX_SAFE_INTEGER),
    profileSummary:{enabledSources:{ratings:enabled.ratings as boolean,behavior:enabled.behavior as boolean,feedback:enabled.feedback as boolean},explicit,inferred},excludeExternalIds:identities,hardConditions:hard};
}
export function validateHints(value: unknown, input: HintRequest): Hints {
  try {
    const row=object(value,['preferredTopics','conditions']);const proposed=conditions(row.conditions);
    const union=(...rows:string[][])=>[...new Set(rows.flat())];
    const min=(a:number|null,b:number|null)=>a===null?b:b===null?a:Math.min(a,b);
    const max=(a:number|null,b:number|null)=>a===null?b:b===null?a:Math.max(a,b);
    const merged:Conditions={requiredTopics:union(input.hardConditions.requiredTopics,proposed.requiredTopics),
      excludedTopics:union(input.hardConditions.excludedTopics,input.profileSummary.explicit.filter(p=>p.direction==='DISLIKE').map(p=>p.topic),proposed.excludedTopics),
      minYear:max(input.hardConditions.minYear,proposed.minYear),maxYear:min(input.hardConditions.maxYear,proposed.maxYear),
      maxRuntimeMinutes:min(input.hardConditions.maxRuntimeMinutes,proposed.maxRuntimeMinutes)};
    if((merged.minYear!==null&&merged.maxYear!==null&&merged.minYear>merged.maxYear)||merged.requiredTopics.some(t=>merged.excludedTopics.includes(t))) throw new Error('conflict');
    return {preferredTopics:topics(row.preferredTopics).filter(t=>!merged.excludedTopics.includes(t)),conditions:merged};
  } catch { throw new ApiError('MODEL_FORMAT',502); }
}
export async function readLimitedText(message: Request | Response, limit: number, signal?: AbortSignal, preserveBom = false): Promise<string> {
  const declared=message.headers.get('content-length');
  if(declared!==null&&(!/^\d+$/.test(declared)||Number(declared)>limit)) throw new ApiError('PAYLOAD_TOO_LARGE',413);
  if(!message.body) throw new ApiError('INVALID_JSON',400);
  const reader=message.body.getReader();let bytes=0;const chunks:Uint8Array[]=[];
  const cancel=()=>{void reader.cancel().catch(()=>{});};signal?.addEventListener('abort',cancel,{once:true});
  try { while(true) {if(signal?.aborted)throw new Error('aborted');const {done,value}=await reader.read();if(done)break;bytes+=value.byteLength;if(bytes>limit)throw new ApiError('PAYLOAD_TOO_LARGE',413);chunks.push(value);} }
  catch(error) { await reader.cancel().catch(()=>{});throw error; } finally { signal?.removeEventListener('abort',cancel);reader.releaseLock(); }
  const joined=new Uint8Array(bytes);let offset=0;for(const chunk of chunks){joined.set(chunk,offset);offset+=chunk.length;}
  try{return new TextDecoder('utf-8',{fatal:true,ignoreBOM:preserveBom}).decode(joined);}catch{throw new ApiError('INVALID_JSON',400);}
}
export async function readRequest(request:Request):Promise<HintRequest> {
  if(request.headers.get('content-type')?.split(';')[0].trim()!=='application/json' || request.headers.has('content-encoding')) throw new ApiError('UNSUPPORTED_MEDIA_TYPE',415);
  const controller=new AbortController();let timer:ReturnType<typeof setTimeout>|undefined;
  try {
    const raw=await Promise.race([readLimitedText(request,MAX_BODY_BYTES,controller.signal),new Promise<never>((_,reject)=>{timer=setTimeout(()=>{reject(new ApiError('REQUEST_TIMEOUT',408,true));controller.abort();},3000);})]);
    let value:unknown;try{value=JSON.parse(raw);}catch{throw new ApiError('INVALID_JSON',400);}return parseRequest(value);
  } finally {clearTimeout(timer??null);controller.abort();}
}
export function response(request:HintRequest|null,status:'success'|'pending'|'error',hints:Hints|null,usage=emptyUsage(),error:ApiError|null=null,quota:QuotaSnapshot|{status:'UNAVAILABLE'}={status:'UNAVAILABLE'}):Response {
  return Response.json({schemaVersion:1,requestId:request?.requestId??null,profileVersion:request?.profileVersion??null,
    sourceSettingsVersion:request?.sourceSettingsVersion??null,status,recommendationHints:hints,usage,quota,
    error:error?{code:error.code,retryable:error.retryable}:null},
    {status:error?.status??(status==='pending'?202:200),headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',...(status==='pending'?{'Retry-After':'1'}:{}),...(error?.code==='RATE_LIMITED'?{'Retry-After':'60'}:{}),...(error?.status===401?{'WWW-Authenticate':'Bearer'}:{}),...(error?.status===405?{'Allow':'POST'}:{})}});
}

/** JSON-decoded values are checked before normalization, so escaping cannot hide credential echoes. */
export function hasSensitiveText(value:unknown,secrets:string[]):boolean {
  const needles=secrets.filter(Boolean).map(value=>value.toLowerCase()),pending:unknown[]=[value];
  while(pending.length){const item=pending.pop();if(typeof item==='string'){if(needles.some(secret=>item.toLowerCase().includes(secret)))return true;}
    else if(Array.isArray(item)){for(const part of item)pending.push(part);}
    else if(plain(item)){for(const [key,part]of Object.entries(item)){pending.push(key,part);}}
  }return false;
}
