import type { Bindings } from './env';
import { ApiError, MAX_BODY_BYTES, object, hasSensitiveText, readLimitedText } from './protocol';

export const AUTH_VERSION = '1';
export const API_CONTRACT = 'byok-1';
export const PRINCIPAL = 'yingji-personal-v1'; // Keep the existing personal ledger across client-key rotations.
export const CLOCK_WINDOW_MS = 60000;
export const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
export interface Proof { clientId: string; nonce: string; timestamp: number }
export interface Incoming { raw: string; key: string | null; proof: Proof }
export const sha256 = async (value: string): Promise<string> => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value)))).map(v=>v.toString(16).padStart(2,'0')).join('');
export async function signingText(request: Request, raw: string, key: string): Promise<string> {
  const url = new URL(request.url);
  return ['YINGJI-BYOK-1',request.method,url.origin+url.pathname,request.headers.get('authorization')??'',request.headers.get('x-yingji-timestamp')??'',request.headers.get('x-yingji-nonce')??'',await sha256(raw),await sha256(key)].join('\n');
}
function clients(env: Bindings): {id: string; jwk: JsonWebKey}[] {
  try {
    if (typeof env.CLIENT_PUBLIC_KEYS !== 'string' || env.CLIENT_PUBLIC_KEYS.length > 8192 || !/^[A-Za-z0-9_-]{32,256}$/.test(env.FINGERPRINT_SECRET??'')) throw Error();
    const list: unknown = JSON.parse(env.CLIENT_PUBLIC_KEYS);
    if (!Array.isArray(list) || list.length < 1 || list.length > 4) throw Error();
    const result = list.map(value=>{
      const c=object(value,['id','jwk']); const k=object(c.jwk,['kty','crv','x','y']);
      if(typeof c.id!=='string'||!/^[A-Za-z0-9_-]{8,100}$/.test(c.id)||k.kty!=='EC'||k.crv!=='P-256'||typeof k.x!=='string'||typeof k.y!=='string'||!/^[-_A-Za-z0-9]{43}$/.test(k.x)||!/^[-_A-Za-z0-9]{43}$/.test(k.y))throw Error();
      return {id:c.id,jwk:{kty:'EC',crv:'P-256',x:k.x,y:k.y}};
    });
    if(new Set(result.map(c=>c.id)).size!==result.length)throw Error(); return result;
  } catch { throw new ApiError('CONFIGURATION_ERROR',503); }
}
export function authConfiguration(env: Bindings): void { clients(env); }
export function userKey(request: Request, required: boolean): string | null {
  const key=request.headers.get('x-deepseek-key');
  if(!required){if(key!==null)throw new ApiError('INVALID_SCHEMA',400);return null;}
  if(!key)throw new ApiError('KEY_REQUIRED',400);
  if(!/^[\x21-\x7e]{16,512}$/.test(key))throw new ApiError('KEY_INVALID',400);
  return key;
}
export async function readIncoming(request: Request, env: Bindings, keyRequired: boolean): Promise<Incoming> {
  const allowed=clients(env); const authorization=request.headers.get('authorization')??'';
  const match=/^Signature ([A-Za-z0-9_-]{8,100})$/.exec(authorization);
  const client=match?allowed.find(c=>c.id===match[1]):undefined;
  const time=request.headers.get('x-yingji-timestamp')??'',nonce=request.headers.get('x-yingji-nonce')??'',signature=request.headers.get('x-yingji-signature')??'';
  if(!client||request.headers.get('x-yingji-auth-version')!==AUTH_VERSION||!/^\d{13}$/.test(time)||!uuid(nonce)||!/^[-_A-Za-z0-9]{86}$/.test(signature))throw new ApiError('UNAUTHORIZED',401);
  const timestamp=Number(time); if(!Number.isSafeInteger(timestamp)||Math.abs(Date.now()-timestamp)>CLOCK_WINDOW_MS)throw new ApiError('REQUEST_EXPIRED',401);
  const key=userKey(request,keyRequired);
  let raw='';
  if(request.method==='GET') { if(request.body!==null||Number(request.headers.get('content-length')??0)!==0)throw new ApiError('INVALID_SCHEMA',400); }
  else {
    if(request.headers.get('content-type')?.split(';')[0].trim()!=='application/json'||request.headers.has('content-encoding'))throw new ApiError('UNSUPPORTED_MEDIA_TYPE',415);
    const controller=new AbortController();let timer:ReturnType<typeof setTimeout>|undefined;
    try { raw=await Promise.race([readLimitedText(request,MAX_BODY_BYTES,controller.signal,true),new Promise<never>((_,reject)=>{timer=setTimeout(()=>{reject(new ApiError('REQUEST_TIMEOUT',408,true));controller.abort();},3000);})]); }
    finally {clearTimeout(timer??null);controller.abort();}
  }
  let valid=false;
  try {
    const publicKey=await crypto.subtle.importKey('jwk',client.jwk,{name:'ECDSA',namedCurve:'P-256'},false,['verify']);
    const bytes=Uint8Array.from(atob(signature.replace(/-/g,'+').replace(/_/g,'/')+'=='),c=>c.charCodeAt(0));
    valid=bytes.byteLength===64&&await crypto.subtle.verify({name:'ECDSA',hash:'SHA-256'},publicKey,bytes,new TextEncoder().encode(await signingText(request,raw,key??'')));
  } catch { valid=false; }
  if(!valid)throw new ApiError('UNAUTHORIZED',401);
  if(raw&&hasSensitiveText(parseJson(raw),[key??'',env.FINGERPRINT_SECRET]))throw new ApiError('INVALID_SCHEMA',400);
  return {raw,key,proof:{clientId:client.id,nonce,timestamp}};
}
export function parseJson(raw: string): unknown { try{return JSON.parse(raw);}catch{throw new ApiError('INVALID_JSON',400);} }
export async function keyBinding(key: string, env: Bindings): Promise<string> {
  const secret=await crypto.subtle.importKey('raw',new TextEncoder().encode(env.FINGERPRINT_SECRET),{name:'HMAC',hash:'SHA-256'},false,['sign']);
  const value=await crypto.subtle.sign('HMAC',secret,new TextEncoder().encode('YINGJI-CREDENTIAL-1\n'+key));
  return Array.from(new Uint8Array(value)).map(v=>v.toString(16).padStart(2,'0')).join('');
}
/** Called inside the existing reservation transaction. Only nonce/time metadata is durable. */
export async function consumeProof(tx: DurableObjectTransaction, proof: Proof): Promise<void> {
  const now=Date.now(); if(Math.abs(now-proof.timestamp)>CLOCK_WINDOW_MS)throw new ApiError('REQUEST_EXPIRED',401);
  const key='auth:nonce:'+proof.clientId+':'+proof.nonce;
  if(await tx.get(key)!==undefined)throw new ApiError('NONCE_REPLAY',409);
  const saved=await tx.get<unknown>('auth:recent');
  const version=await tx.get<number>('auth:version');
  if((version!==undefined&&version!==1)||(version===1&&saved===undefined)||(version===undefined&&saved!==undefined))throw new ApiError('STORAGE_UNAVAILABLE',503);
  if(saved!==undefined&&(!Array.isArray(saved)||saved.some(v=>!Number.isSafeInteger(v))))throw new ApiError('STORAGE_UNAVAILABLE',503);
  const recent=((saved??[]) as number[]).filter(t=>t>now-60000);
  if(recent.length>=120)throw new ApiError('ACCESS_RATE_LIMITED',429,true);
  const nonces=await tx.list<number>({prefix:'auth:nonce:',limit:512});
  let retained=0;for(const [name,until] of nonces){if(!Number.isSafeInteger(until))throw new ApiError('STORAGE_UNAVAILABLE',503);if(until<now)await tx.delete(name);else retained++;}
  if(retained>=512)throw new ApiError('ACCESS_RATE_LIMITED',429,true);
  await tx.put({[key]:proof.timestamp+CLOCK_WINDOW_MS+1,'auth:recent':[...recent,now],'auth:version':1});
}
export interface VerifyRequest {schemaVersion: 1; requestId: string}
export function parseVerify(raw: string): VerifyRequest {
  const value=object(parseJson(raw),['schemaVersion','requestId']);
  if(value.schemaVersion!==1||!uuid(value.requestId))throw new ApiError('INVALID_SCHEMA',400);
  return {schemaVersion:1,requestId:value.requestId.toLowerCase()};
}
