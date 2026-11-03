import { env, exports } from 'cloudflare:workers';
import { runInDurableObject, evictDurableObject } from 'cloudflare:test';
import { beforeAll,afterAll,beforeEach,afterEach,expect,test,vi } from 'vitest';
import { setupNetwork } from '@msw/cloudflare';
import { http } from 'msw';
import worker, { RequestGate } from '../src/index';
import { CLIENT_PUBLIC_KEYS } from './client-fixture';
import { Ledger, attemptKey, jobKey, type Attempt } from '../src/ledger';
import { serverDay, zeroStats, type Stats } from '../src/accounting';
import type { Bindings } from '../src/env';
import { input,httpRequest,getRequest,completion,hints,ACCESS } from './fixtures';
const network=setupNetwork();beforeAll(()=>network.enable());afterAll(()=>network.disable());
let id:DurableObjectId;const stub=()=>env.REQUEST_GATE.get(id);const policy=env.BILLING_POLICY;
const send=vi.fn(async()=>completion());let restore:(()=>void)|undefined;
const call=async(body=input())=>runInDurableObject(stub(),async(instance:RequestGate)=>{const result=await instance.fetch(await httpRequest(body));return {status:result.status,body:await result.json() as any};});
const quota=async()=>{const result=await exports.default.fetch(await getRequest('/v1/ai/quota'));return {status:result.status,body:await result.json() as any};};
const stats=()=>runInDurableObject(stub(),async(_instance:RequestGate,state)=>({total:await state.storage.get<Stats>('billing:total'),daily:await state.storage.get<Stats>('billing:day:'+serverDay(Date.now(),480).day)}));
const setPolicy=async(value:string)=>runInDurableObject(stub(),async(instance:RequestGate)=>{(instance as unknown as {env:Bindings}).env.BILLING_POLICY=value;});
beforeEach(async()=>{vi.restoreAllMocks();id=env.REQUEST_GATE.newUniqueId();vi.spyOn(env.REQUEST_GATE,'idFromName').mockReturnValue(id);send.mockReset();send.mockImplementation(async()=>completion());network.resetHandlers();network.use(http.all('*',()=>new Response('UNMATCHED_TEST_REQUEST',{status:599})));network.use(http.post('https://api.deepseek.com/chat/completions',()=>send()));await setPolicy(policy!);});
afterEach(async()=>{restore?.();restore=undefined;vi.restoreAllMocks();await setPolicy(policy!);await runInDurableObject(stub(),async(_instance:RequestGate,state)=>state.storage.deleteAll());network.resetHandlers();});

test('thirty actual successes, free replays, exact quota boundary and server-day reset',async()=>{
  let now=Date.parse('2026-10-19T01:00:00Z');vi.spyOn(Date,'now').mockImplementation(()=>now);
  const first=input();for(let i=0;i<30;i++){const result=await call(i===0?first:input());expect(result.status).toBe(200);expect(result.body.quota).toMatchObject({used:i+1,remaining:29-i});now+=61000;}
  expect((await call()).body.error.code).toBe('QUOTA_EXHAUSTED');
  const replay=await call(first);expect(replay.status).toBe(200);expect(replay.body.quota).toMatchObject({status:'EXHAUSTED',used:30,remaining:0});
  const read=await quota();expect(read.status).toBe(200);expect(read.body.daily).toMatchObject({successes:30,httpRequests:30,retries:0,reservedTasks:0});expect(send).toHaveBeenCalledTimes(30);
  await evictDurableObject(stub());expect((await call()).body.error.code).toBe('QUOTA_EXHAUSTED');expect(send).toHaveBeenCalledTimes(30);
  now=Date.parse('2026-10-19T16:00:00Z');const next=await call();expect(next.status).toBe(200);expect(next.body.quota).toMatchObject({day:'2026-10-20',used:1,remaining:29,resetsAt:'2026-10-20T16:00:00.000Z'});expect((await stats()).total!.successes).toBe(31);
});
test('quota reads ignore client Date and work without live AI, spending no task or HTTP slot',async()=>{
  const before=await quota();expect(before.body.quota).toMatchObject({limit:30,used:0,remaining:30});
  for(let i=0;i<8;i++){const result=await exports.default.fetch(await getRequest('/v1/ai/quota',{Date:'Thu, 01 Jan 1970 00:00:00 GMT'}));expect(result.status).toBe(200);expect((await result.json() as any).total).toEqual(zeroStats());}
  await runInDurableObject(stub(),async(instance:RequestGate)=>{(instance as unknown as {env:Bindings}).env.LIVE_AI_ENABLED='false';});
  expect((await quota()).status).toBe(200);await runInDurableObject(stub(),async(instance:RequestGate)=>{(instance as unknown as {env:Bindings}).env.LIVE_AI_ENABLED='true';});expect(send).not.toHaveBeenCalled();
});
test('failed attempt and retry retain separate usage, errors and cost while charging one success',async()=>{
  send.mockImplementationOnce(async()=>new Response('temporary',{status:503}));const body=input();const result=await call(body);expect(result.status).toBe(200);
  expect(result.body.quota.used).toBe(1);expect((await stats()).total).toMatchObject({successes:1,failedTasks:0,httpRequests:2,retries:1,knownPromptTokens:100,knownCompletionTokens:40,unknownUsageAttempts:1,committedMicros:'180',reservedMicros:'33792'});
  const records=await runInDurableObject(stub(),async(_instance:RequestGate,state)=>[await state.storage.get<Attempt>(attemptKey(body.requestId,0)),await state.storage.get<Attempt>(attemptKey(body.requestId,1))]);
  expect(records[0]).toMatchObject({state:'UNKNOWN',httpStatus:503,errorCode:'UPSTREAM_UNAVAILABLE',tokens:null,estimatedMicros:null});expect(records[1]).toMatchObject({state:'KNOWN',httpStatus:200,errorCode:null,estimatedMicros:'180'});
  await call(body);expect(send).toHaveBeenCalledTimes(2);
});
test('invalid model hints with valid usage cost money but never consume success quota',async()=>{
  send.mockImplementationOnce(async()=>completion({movies:[{title:'unverified'}]}));const body=input(),result=await call(body);expect(result.status).toBe(502);expect(result.body.error.code).toBe('MODEL_FORMAT');expect(result.body.quota.used).toBe(0);
  expect((await stats()).total).toMatchObject({failedTasks:1,successes:0,httpRequests:1,knownPromptTokens:100,knownCompletionTokens:40,unknownUsageAttempts:0,committedMicros:'180',reservedMicros:'0'});await call(body);expect(send).toHaveBeenCalledTimes(1);
});
test('unknown first-attempt cost exhausts budget and prevents retry and all later paid calls',async()=>{
  const p={...JSON.parse(policy!),dailyBudgetMicros:'33792',totalBudgetMicros:'33792'};await setPolicy(JSON.stringify(p));
  send.mockImplementationOnce(async()=>new Response('unavailable',{status:503}));const result=await call();expect(result.status).toBe(429);expect(result.body.error.code).toBe('BUDGET_EXHAUSTED');expect(result.body.usage).toMatchObject({httpRequests:1,retries:0});
  expect((await stats()).total).toMatchObject({successes:0,reservedTasks:0,failedTasks:1,httpRequests:1,reservedMicros:'33792'});expect((await call()).body.error.code).toBe('BUDGET_EXHAUSTED');
  const future=Date.now()+86400000;vi.spyOn(Date,'now').mockReturnValue(future);expect((await call()).body.error.code).toBe('BUDGET_EXHAUSTED');expect(send).toHaveBeenCalledTimes(1);
});
test('exact estimated spend reaches budget and persists across eviction',async()=>{
  await setPolicy(JSON.stringify({...JSON.parse(policy!),dailyBudgetMicros:'33792',totalBudgetMicros:'33792'}));
  // A known 32768-input/512-output report fills the conservative ceiling exactly.
  send.mockImplementationOnce(async()=>completion(hints(),{usage:{prompt_tokens:32768,completion_tokens:512,total_tokens:33280}}));
  expect((await call()).status).toBe(200);expect((await stats()).total!.committedMicros).toBe('33792');await evictDurableObject(stub());
  expect((await call()).body.error.code).toBe('BUDGET_EXHAUSTED');expect(send).toHaveBeenCalledTimes(1);
});
test.each([2,3])('storage failure in accounting transaction %i prevents success or an unrecorded retry',async(which)=>{
  await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{const original=state.storage.transaction.bind(state.storage);let count=0;const fault=vi.spyOn(state.storage,'transaction').mockImplementation(async fn=>{count++;if(count===which)throw new Error('private failure');return original(fn);});restore=()=>fault.mockRestore();});
  const body=input(),result=await call(body);expect(result.status).toBe(503);expect(result.body.error.code).toBe('STORAGE_UNAVAILABLE');expect(result.body.recommendationHints).toBeNull();expect(result.body.quota.used).toBe(0);expect(send).toHaveBeenCalledTimes(which===2?0:1);
  await call(body);expect(send).toHaveBeenCalledTimes(which===2?0:1);if(which===3)expect((await stats()).total!.reservedMicros).toBe('33792');
});
test('quota storage read failure returns unavailable instead of a fabricated quota',async()=>{
  await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{const fault=vi.spyOn(state.storage,'transaction').mockRejectedValue(new Error('private read failure'));restore=()=>fault.mockRestore();});const result=await quota();expect(result.status).toBe(503);expect(result.body.quota).toEqual({status:'UNAVAILABLE'});expect(send).not.toHaveBeenCalled();
});
test('A5 additive migration preserves cached identity, counts success and blocks unknown historical costs',async()=>{
  const old=input();await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{const fingerprint=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(old))))).map(b=>b.toString(16).padStart(2,'0')).join('');await state.storage.put(jobKey(old.requestId),{fingerprint,createdAt:Date.now(),response:{status:200,body:{schemaVersion:1,requestId:old.requestId,profileVersion:1,sourceSettingsVersion:1,status:'success',recommendationHints:hints(),usage:{complete:true,httpRequests:1,retries:0,promptTokens:100,completionTokens:40,totalTokens:140},quota:{status:'NOT_IMPLEMENTED'},error:null}}});});
  const replay=await call(old);expect(replay.status).toBe(200);expect(replay.body.quota).toMatchObject({status:'REVIEW_REQUIRED',used:1,remaining:29});expect(replay.body.recommendationHints).toEqual(hints());
  const fresh=await call();expect(fresh.status).toBe(503);expect(fresh.body.error.code).toBe('BILLING_REVIEW_REQUIRED');expect((await stats()).total).toMatchObject({successes:1,httpRequests:1,knownPromptTokens:100});expect(send).not.toHaveBeenCalled();
});
test('unverified policy refuses new calls but quota remains readable',async()=>{
  await setPolicy('{}');const result=await call();expect(result.status).toBe(503);expect(result.body.error.code).toBe('BILLING_UNCONFIGURED');expect((await quota()).body.quota.status).toBe('BILLING_UNCONFIGURED');expect(send).not.toHaveBeenCalled();
});
test('an attempt keeps its original rate card when configured prices change before settlement',async()=>{
  const result=await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{
    const e={...env} as unknown as Bindings,ledger=new Ledger(state.storage,e,480),body=input();
    await state.storage.transaction(async tx=>{const day=await ledger.reserve(tx,Date.now());await tx.put({[jobKey(body.requestId)]:{fingerprint:'test',createdAt:Date.now(),response:null,billing:{day,settled:false}},active:body.requestId});});
    await ledger.begin(body.requestId,0);e.BILLING_POLICY=JSON.stringify({...JSON.parse(policy!),id:'TEST_ONLY_CHANGED',inputMicrosPerMillion:'100000000'});
    await ledger.finish(body.requestId,0,{httpStatus:200,errorCode:null,tokens:{promptTokens:100,completionTokens:40,totalTokens:140,cacheHitTokens:null,cacheMissTokens:null}});return state.storage.get<Attempt>(attemptKey(body.requestId,0));
  });expect(result).toMatchObject({estimatedMicros:'180',rateCard:{id:'TEST_ONLY_V1'}});
});

test('timeout settlement records an uncertain attempt and never releases its cost reservation',async()=>{
  const result=await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{
    const ledger=new Ledger(state.storage,env as unknown as Bindings,480),body=input();
    await state.storage.transaction(async tx=>{const day=await ledger.reserve(tx,Date.now());await tx.put({[jobKey(body.requestId)]:{fingerprint:'timeout-fixture',createdAt:Date.now(),response:null,billing:{day,settled:false}},active:body.requestId});});
    await ledger.begin(body.requestId,0);await state.storage.transaction(tx=>ledger.settle(tx,body.requestId,{status:504,body:{status:'error',error:{code:'UPSTREAM_TIMEOUT'}}},Date.now()));
    let lateRejected=false;try{await ledger.finish(body.requestId,0,{httpStatus:200,errorCode:null,tokens:{promptTokens:100,completionTokens:40,totalTokens:140,cacheHitTokens:null,cacheMissTokens:null}});}catch{lateRejected=true;}
    return {attempt:await state.storage.get<Attempt>(attemptKey(body.requestId,0)),view:await ledger.view(),lateRejected};
  });expect(result.lateRejected).toBe(true);expect(result.attempt).toMatchObject({state:'UNKNOWN',errorCode:'UPSTREAM_TIMEOUT',tokens:null,estimatedMicros:null});expect(result.view.total).toMatchObject({successes:0,reservedTasks:0,failedTasks:1,httpRequests:1,unknownUsageAttempts:1,reservedMicros:'33792'});
});
test('a missing previously-created day bucket fails closed instead of resetting quota',async()=>{
  expect((await call()).status).toBe(200);await runInDurableObject(stub(),async(_instance:RequestGate,state)=>state.storage.delete('billing:day:'+serverDay(Date.now(),480).day));const result=await call();expect(result.status).toBe(503);expect(result.body.error.code).toBe('STORAGE_UNAVAILABLE');expect(send).toHaveBeenCalledTimes(1);
});

test('ten simultaneous callers cannot exceed the final remaining success slot',async()=>{
  await quota();await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{const s={...zeroStats(),successes:29};await state.storage.put({'billing:total':s,['billing:day:'+serverDay(Date.now(),480).day]:s});});
  const results=await Promise.all(Array.from({length:10},()=>call()));expect(results.filter(r=>r.status===200)).toHaveLength(1);for(const result of results.filter(r=>r.status!==200))expect(['REQUEST_IN_PROGRESS','QUOTA_EXHAUSTED']).toContain(result.body.error.code);
  expect((await quota()).body.quota).toMatchObject({used:30,reserved:0,remaining:0});expect(send).toHaveBeenCalledTimes(1);
});
test('token rotation and client clock headers never create a fresh quota principal',async()=>{
  expect((await call()).body.quota.used).toBe(1);const token='TEST_ONLY_ROTATED_ACCESS_000000000000000000000';
  const result=await worker.fetch(await httpRequest(input(),{Authorization:'Signature '+token,Date:'Thu, 01 Jan 1970 00:00:00 GMT'}),{...env,CLIENT_PUBLIC_KEYS} as unknown as Bindings);expect(result.status).toBe(200);expect((await result.json() as any).quota.used).toBe(2);expect((await stats()).total!.successes).toBe(2);expect(send).toHaveBeenCalledTimes(2);
});
test('a task spanning midnight settles its reserved day and preserves lifetime counters',async()=>{
  let now=Date.parse('2026-10-19T15:59:59Z');vi.spyOn(Date,'now').mockImplementation(()=>now);
  const result=await runInDurableObject(stub(),async(_instance:RequestGate,state)=>{
    const ledger=new Ledger(state.storage,env as unknown as Bindings,480),body=input();
    await state.storage.transaction(async tx=>{const day=await ledger.reserve(tx,Date.now());await tx.put({[jobKey(body.requestId)]:{fingerprint:'midnight-fixture',createdAt:Date.now(),response:null,billing:{day,settled:false}},active:body.requestId});});
    await ledger.begin(body.requestId,0);now=Date.parse('2026-10-19T16:00:00Z');await ledger.finish(body.requestId,0,{httpStatus:200,errorCode:null,tokens:{promptTokens:100,completionTokens:40,totalTokens:140,cacheHitTokens:0,cacheMissTokens:100}});
    await state.storage.transaction(tx=>ledger.settle(tx,body.requestId,{status:200,body:{status:'success'}},Date.now()));return {old:await state.storage.get<Stats>('billing:day:2026-10-19'),current:await ledger.view()};
  });expect(result.old).toMatchObject({successes:1,reservedTasks:0,committedMicros:'180'});expect(result.current.quota).toMatchObject({day:'2026-10-20',used:0,remaining:30});expect(result.current.total).toMatchObject({successes:1,committedMicros:'180'});
});

test('quota route rejects unauthorized callers and advertises GET for wrong methods',async()=>{
  const denied=await exports.default.fetch('https://unit.test/v1/ai/quota');expect(denied.status).toBe(401);expect((await denied.json() as any).quota).toEqual({status:'UNAVAILABLE'});
  const method=await exports.default.fetch('https://unit.test/v1/ai/quota',{method:'POST',headers:{Authorization:'Bearer '+ACCESS}});expect(method.status).toBe(405);expect(method.headers.get('allow')).toBe('GET');expect(send).not.toHaveBeenCalled();
});
