import { env, exports } from 'cloudflare:workers';
import { runInDurableObject, evictDurableObject } from 'cloudflare:test';
import { beforeAll, afterAll, beforeEach, afterEach, expect, test, vi } from 'vitest';
import { setupNetwork } from '@msw/cloudflare';
import { http } from 'msw';
import worker, { RequestGate } from '../src/index';
const RATE_LIMIT=6;
import type { Bindings } from '../src/env';
import { input, httpRequest, rawHttpRequest, completion, completionBody, hints, ACCESS, API_KEY } from './fixtures';
import type { HintRequest } from '../src/protocol';

declare module 'cloudflare:workers' { interface ProvidedEnv extends Bindings {} }
const network=setupNetwork();
beforeAll(()=>network.enable());
afterAll(()=>network.disable());
const bindings=()=>env as unknown as Bindings;
let gateId:DurableObjectId;
const stub=()=>env.REQUEST_GATE.get(gateId);
const call=async(body:unknown=input(),override:Partial<Bindings>={})=>{
  const value=Object.keys(override).length?await (async()=>{const result=await worker.fetch(await httpRequest(body),{...bindings(),...override});return {status:result.status,raw:await result.text()};})():
    await runInDurableObject(stub(),async(instance:RequestGate)=>{const result=await instance.fetch(await httpRequest(body));return {status:result.status,raw:await result.text()};});
  return {status:value.status,json:async()=>JSON.parse(value.raw),text:async()=>value.raw};
};
const send=vi.fn(async (_url:unknown,_init?:RequestInit)=>completion());
let faultRestore:(()=>void)|undefined;
beforeEach(async()=>{vi.restoreAllMocks();gateId=env.REQUEST_GATE.newUniqueId();vi.spyOn(env.REQUEST_GATE,'idFromName').mockImplementation(()=>gateId);send.mockReset();send.mockImplementation(async()=>completion());network.resetHandlers();network.use(http.all('*',()=>new Response('UNMATCHED_TEST_REQUEST',{status:599})));network.use(http.post('https://api.deepseek.com/chat/completions',async({request})=>send(request.url,{method:request.method,headers:Object.fromEntries(request.headers),redirect:request.redirect,body:await request.text(),signal:request.signal})));
  await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{await state.storage.deleteAll();});});
afterEach(async()=>{faultRestore?.();faultRestore=undefined;await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{await state.storage.deleteAll();});network.resetHandlers();});

test('real Worker dispatch validates success and sends only the necessary summary',async()=>{
  const body=input({mode:'QUERY',userQuery:'想看科幻',excludeExternalIds:[{sourceId:'catalogue',externalId:'external-7'}]});
  const result=await exports.default.fetch(await httpRequest(body));expect(result.status).toBe(200);
  expect(await result.json()).toMatchObject({schemaVersion:1,requestId:body.requestId,profileVersion:1,sourceSettingsVersion:1,status:'success',recommendationHints:hints(),usage:{httpRequests:1,retries:0,totalTokens:140},quota:{status:'AVAILABLE',limit:30,used:1,remaining:29},error:null});
  expect(result.headers.get('cache-control')).toBe('no-store');expect(send).toHaveBeenCalledTimes(1);
  const [url,options]=send.mock.calls[0];expect(url).toBe('https://api.deepseek.com/chat/completions');
  const posted=JSON.parse(String(options!.body));expect(posted).toMatchObject({model:'deepseek-flash',stream:false,max_tokens:512,thinking:{type:'disabled'},response_format:{type:'json_object'}});
  const modelInput=JSON.parse(posted.messages[1].content);expect(Object.keys(modelInput).sort()).toEqual(['explicit','hardConditions','inferred','mode','userQuery']);
  expect(posted.messages[1].content).not.toContain('external-7');expect(posted.messages[1].content).not.toContain(ACCESS);expect(posted.messages[1].content).not.toContain(body.requestId);
});
test('wrong or missing Bearer never reaches storage or provider',async()=>{
  for(const auth of ['', 'Bearer invalid', 'Bearer '+ACCESS+'x']){
    const result=await worker.fetch(await httpRequest(input(),{Authorization:auth}),bindings());expect(result.status).toBe(401);expect(await result.json()).toMatchObject({requestId:null,error:{code:'UNAUTHORIZED',retryable:false}});
  }expect(send).not.toHaveBeenCalled();
});
test('default disabled live switch and missing configuration make no provider call',async()=>{
  expect((await call(input(),{LIVE_AI_ENABLED:'false'})).status).toBe(503);
  for(const change of [{CLIENT_PUBLIC_KEYS:''},{FINGERPRINT_SECRET:''},{DEEPSEEK_MODEL:'other-model'}])expect((await call(input(),change)).status).toBe(503);
  expect(send).not.toHaveBeenCalled();
});
test('unknown route query-string and method return structured errors',async()=>{
  for(const request of [new Request('https://unit.test/other'),new Request('https://unit.test/v1/ai/recommend?secret=hidden'),new Request('https://unit.test/v1/ai/recommend')]){
    const result=await worker.fetch(request,bindings());expect([404,405]).toContain(result.status);expect((await result.text())).not.toContain('hidden');
  }expect(send).not.toHaveBeenCalled();
});
test('invalid JSON media type declared size and streamed size are rejected before provider',async()=>{
  const cases=[await rawHttpRequest('{'),
    await httpRequest(input(),{'Content-Type':'text/plain'}),await httpRequest(input(),{'Content-Length':'999999'}),await httpRequest({padding:'a'.repeat(17000)})];
  for(const request of cases){const result=await worker.fetch(request,bindings());expect([400,413,415]).toContain(result.status);}
  expect(send).not.toHaveBeenCalled();
});
test('reused requestId returns persisted identical response even after Durable Object eviction',async()=>{
  const body=input();const first=await call(body);const expected=await first.json();await evictDurableObject(stub());
  const again=await call(body);expect(again.status).toBe(200);expect(await again.json()).toEqual(expected);expect(send).toHaveBeenCalledTimes(1);
});
test('same requestId with changed input conflicts and cannot call model twice',async()=>{
  const body=input();expect((await call(body)).status).toBe(200);
  const result=await call({...body,mode:'QUERY',userQuery:'another query'});expect(result.status).toBe(409);expect(await result.json()).toMatchObject({error:{code:'REQUEST_ID_CONFLICT'}});expect(send).toHaveBeenCalledTimes(1);
});
test('concurrent duplicate returns pending while a different request is blocked',async()=>{
  let finish!:(result:string)=>Promise<void>;let started!:()=>void;const ready=new Promise<void>(resolve=>started=resolve);
  send.mockImplementationOnce(()=>{
    const stream=new TransformStream<Uint8Array,Uint8Array>();const writer=stream.writable.getWriter();
    finish=async result=>{await writer.write(new TextEncoder().encode(result));await writer.close();writer.releaseLock();};
    started();return Promise.resolve(new Response(stream.readable,{headers:{'Content-Type':'application/json'}}));
  });
  const body=input();const first=call(body);await ready;
  const duplicate=await call(body);expect(duplicate.status).toBe(202);expect(await duplicate.json()).toMatchObject({status:'pending'});
  expect((await call(input())).status).toBe(409);await finish(JSON.stringify(completionBody()));expect((await first).status).toBe(200);expect(send).toHaveBeenCalledTimes(1);
});
test('rate guard uses server time survives eviction and replay does not consume a slot',async()=>{
  const first=input();expect((await call(first)).status).toBe(200);
  for(let i=1;i<RATE_LIMIT;i++)expect((await call()).status).toBe(200);
  await evictDurableObject(stub());expect((await call(first)).status).toBe(200);
  const result=await exports.default.fetch(await httpRequest(input(),{Date:'Thu, 01 Jan 1970 00:00:00 GMT'}));expect(result.status).toBe(429);expect(await result.json()).toMatchObject({error:{code:'RATE_LIMITED'}});expect(send).toHaveBeenCalledTimes(RATE_LIMIT);
});
test('malformed internal binding response is not forwarded as raw text',async()=>{
  const broken={idFromName(){return {};},get(){return {fetch:async()=>new Response('private storage failure')};}} as unknown as DurableObjectNamespace;
  const result=await call(input(),{REQUEST_GATE:broken});expect(result.status).toBe(503);expect(await result.text()).not.toContain('private storage');expect(send).not.toHaveBeenCalled();
});
test('broken binding fails closed without any upstream call',async()=>{
  const broken={idFromName(){throw new Error('raw sensitive storage error');}} as unknown as DurableObjectNamespace;
  const result=await call(input(),{REQUEST_GATE:broken});expect(result.status).toBe(503);expect(await result.json()).toMatchObject({error:{code:'STORAGE_UNAVAILABLE'}});expect(send).not.toHaveBeenCalled();
});
test('reservation storage failure prevents model call',async()=>{
  await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{const fault=vi.spyOn(state.storage,'transaction').mockRejectedValueOnce(new Error('private storage detail'));faultRestore=()=>fault.mockRestore();});
  const result=await call();expect(result.status).toBe(503);expect(await result.text()).not.toContain('private storage detail');expect(send).not.toHaveBeenCalled();
});
test('failed final persistence never exposes a success and never repeats the pending upstream call',async()=>{
  await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{
    const original=state.storage.transaction.bind(state.storage);let times=0;
    const fault=vi.spyOn(state.storage,'transaction').mockImplementation(async fn=>{times++;if(times===4)throw new Error('injected final write failure');return original(fn);});faultRestore=()=>fault.mockRestore();
  });
  const body=input();const result=await call(body);expect(result.status).toBe(503);expect(await result.json()).toMatchObject({error:{code:'STORAGE_UNAVAILABLE'},recommendationHints:null});
  expect((await call(body)).status).toBe(202);expect(send).toHaveBeenCalledTimes(1);
});
test('abandoned durable reservation is uncertain after restart and is not blindly retried',async()=>{
  const body=input();const seeded={fingerprint:'unrelated',createdAt:Date.now()-30000,response:null};
  await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{await state.storage.put({'job:abandoned':seeded,active:'abandoned'});});
  await evictDurableObject(stub());const result=await call(body);expect(result.status).toBe(503);expect(await result.json()).toMatchObject({error:{code:'OUTCOME_UNKNOWN',retryable:false}});expect(send).not.toHaveBeenCalled();
});
test('provider redirects are rejected without forwarding credentials to another host',async()=>{
  send.mockImplementationOnce(async()=>new Response(null,{status:302,headers:{Location:'https://untrusted.invalid/'}}));
  const result=await call();expect(result.status).toBe(502);expect(send).toHaveBeenCalledTimes(1);expect(send.mock.calls[0][1]!.redirect).toBe('manual');
  expect(await result.json()).toMatchObject({error:{code:'UPSTREAM_UNAVAILABLE',retryable:false}});
});
test('provider authentication errors are sanitized and are not automatically retried',async()=>{
  send.mockImplementationOnce(async()=>new Response('private provider failure '+API_KEY,{status:401}));const result=await call();expect(result.status).toBe(502);const raw=await result.text();expect(raw).not.toContain(API_KEY);expect(raw).not.toContain('private provider');expect(send).toHaveBeenCalledTimes(1);
});
test('recoverable provider failure retries once without changing model or request identity',async()=>{
  send.mockImplementationOnce(async()=>new Response('temporary',{status:503}));const result=await call();expect(result.status).toBe(200);expect(await result.json()).toMatchObject({usage:{httpRequests:2,retries:1,promptTokens:null,totalTokens:null}});expect(send).toHaveBeenCalledTimes(2);
  expect(send.mock.calls.map(row=>JSON.parse(String(row[1]!.body)).model)).toEqual(['deepseek-flash','deepseek-flash']);
});
test('empty malformed fabricated or truncated model output is rejected without retry',async()=>{
  for(const bad of [null,{movies:[{title:'made up film'}]}, {preferredTopics:[],conditions:{}}, {preferredTopics:[API_KEY],conditions:hints().conditions}]){
    send.mockImplementationOnce(async()=>completion(bad));const result=await call();expect(result.status).toBe(502);expect(await result.json()).toMatchObject({recommendationHints:null,error:{code:'MODEL_FORMAT'}});
  }expect(send).toHaveBeenCalledTimes(4);
});
