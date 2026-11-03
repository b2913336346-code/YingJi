import { parseRequest, type HintRequest, type Hints } from '../src/protocol';
import { CLIENT_ID as ACCESS, ROTATED_ID, PRIVATE_JWK, ROTATED_PRIVATE_JWK } from './client-fixture';
import { signingText } from '../src/auth';
export { ACCESS };
export const API_KEY='TEST_ONLY_PROVIDER_0000000000000000';
export const blankConditions=()=>({requiredTopics:[],excludedTopics:[],minYear:null,maxYear:null,maxRuntimeMinutes:null});
export const hints=():Hints=>({preferredTopics:['科幻'],conditions:blankConditions()});
export function input(change:Partial<HintRequest>={}):HintRequest{return parseRequest({schemaVersion:1,requestId:crypto.randomUUID(),mode:'FOR_YOU',userQuery:'',externalProcessingConsent:true,
  profileVersion:1,sourceSettingsVersion:1,profileSummary:{enabledSources:{ratings:false,behavior:false,feedback:true},explicit:[],inferred:[]},excludeExternalIds:[],hardConditions:blankConditions(),...change});}
export function completionBody(content:unknown=hints(),extra:Record<string,unknown>={}){return {model:'deepseek-flash',choices:[{finish_reason:'stop',message:{role:'assistant',content:JSON.stringify(content)}}],usage:{prompt_tokens:100,completion_tokens:40,total_tokens:140},...extra};}
export function completion(content:unknown=hints(),extra:Record<string,unknown>={}):Response{return Response.json(completionBody(content,extra));}
export async function rawHttpRequest(raw:string,headers:HeadersInit={},url='https://unit.test/v1/ai/recommend',method='POST'):Promise<Request>{
  const values=new Headers({'Authorization':'Signature '+ACCESS,'X-Yingji-Auth-Version':'1','X-Yingji-Timestamp':String(Date.now()),'X-Yingji-Nonce':crypto.randomUUID(),...(method==='POST'?{'Content-Type':'application/json','X-DeepSeek-Key':API_KEY}:{})});
  new Headers(headers).forEach((value,name)=>values.set(name,value));
  const request=new Request(url,{method,headers:values,...(method==='POST'?{body:raw}:{})});
  const key=await crypto.subtle.importKey('jwk',values.get('authorization')==='Signature '+ROTATED_ID?ROTATED_PRIVATE_JWK:PRIVATE_JWK,{name:'ECDSA',namedCurve:'P-256'},false,['sign']);
  const signature=await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},key,new TextEncoder().encode(await signingText(request,raw,values.get('x-deepseek-key')??'')));
  if(!values.has('x-yingji-signature'))values.set('X-Yingji-Signature',btoa(String.fromCharCode(...new Uint8Array(signature))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,''));
  return new Request(url,{method,headers:values,...(method==='POST'?{body:raw}:{})});
}
export function httpRequest(body:unknown=input(),headers:HeadersInit={}):Promise<Request>{return rawHttpRequest(JSON.stringify(body),headers);}
export function getRequest(path:string,headers:HeadersInit={}):Promise<Request>{return rawHttpRequest('',headers,'https://unit.test'+path,'GET');}
export { CLIENT_PUBLIC_KEYS, FINGERPRINT_SECRET } from './client-fixture';
