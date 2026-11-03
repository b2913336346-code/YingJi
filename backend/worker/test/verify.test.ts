import {test,expect,vi,afterEach} from 'vitest';
import {verifyDeepSeek,runDeepSeek,MODEL_TIMEOUT_MS} from '../src/deepseek';
import type {Bindings} from '../src/env';
import {API_KEY,ACCESS,input} from './fixtures';
const bindings={UPSTREAM_MODE:'MOCK',DEEPSEEK_MODEL:'deepseek-flash',FINGERPRINT_SECRET:'TEST_ONLY_FINGERPRINT_0000000000000000000000000000'} as Bindings;
const credential={key:API_KEY,clientId:ACCESS};afterEach(()=>vi.useRealTimers());
test('verification has one total deadline and never retries an unresponsive account lookup',async()=>{
  vi.useFakeTimers();let signal:AbortSignal|undefined;const send=vi.fn((_url:unknown,init:RequestInit)=>{signal=init.signal!;return new Promise<Response>(()=>{});});const done=expect(verifyDeepSeek(bindings,credential,send as typeof fetch)).rejects.toMatchObject({code:'UPSTREAM_TIMEOUT',usage:{httpRequests:1,retries:0}});await vi.advanceTimersByTimeAsync(MODEL_TIMEOUT_MS);await done;expect(signal!.aborted).toBe(true);expect(send).toHaveBeenCalledTimes(1);
});
test('verification deadline also bounds and cancels the account response stream',async()=>{
  vi.useFakeTimers();const cancel=vi.fn();const send=vi.fn().mockResolvedValue(new Response(new ReadableStream({cancel})));const done=expect(verifyDeepSeek(bindings,credential,send as typeof fetch)).rejects.toMatchObject({code:'UPSTREAM_TIMEOUT'});await vi.advanceTimersByTimeAsync(MODEL_TIMEOUT_MS);await done;expect(cancel).toHaveBeenCalledTimes(1);expect(send).toHaveBeenCalledTimes(1);
});
test('invalid or oversized account response is sanitized and cannot verify a Key',async()=>{
  for(const response of [new Response('{'),new Response('x'.repeat(8193)),Response.json({is_available:'true',balance_infos:[]}),Response.json({is_available:true,balance_infos:[{currency:'CNY',total_balance:API_KEY,granted_balance:'0',topped_up_balance:'0'}]})]){const send=vi.fn().mockResolvedValue(response);await expect(verifyDeepSeek(bindings,credential,send as typeof fetch)).rejects.toMatchObject({code:'MODEL_FORMAT'});expect(send).toHaveBeenCalledTimes(1);}
});
test('Mock configuration never forwards a genuine-looking credential',async()=>{
  const send=vi.fn();const fake={key:'FAKE_NON_TEST_CREDENTIAL_NOT_USED',clientId:ACCESS};await expect(verifyDeepSeek(bindings,fake,send as typeof fetch)).rejects.toMatchObject({code:'KEY_INVALID'});await expect(runDeepSeek(input(),bindings,fake,send as typeof fetch)).rejects.toMatchObject({code:'KEY_INVALID'});expect(send).not.toHaveBeenCalled();
});
test.each([[401,'UPSTREAM_AUTH'],[403,'UPSTREAM_FORBIDDEN'],[402,'UPSTREAM_BILLING']])('recommendation distinguishes upstream %i and never retries it',async(status,code)=>{
  const send=vi.fn().mockResolvedValue(new Response(API_KEY,{status:Number(status)}));await expect(runDeepSeek(input(),bindings,credential,send as typeof fetch)).rejects.toMatchObject({code,retryable:false,usage:{httpRequests:1,retries:0}});expect(send).toHaveBeenCalledTimes(1);
});

test('escaped punctuation in a model credential cannot evade decoded-output sanitization',async()=>{
  const fake={key:'FAKE_CREDENTIAL_quote_"_slash_\\_only_test',clientId:ACCESS};const live={...bindings,UPSTREAM_MODE:'LIVE'} as Bindings;
  const response=Response.json({model:'deepseek-flash',choices:[{finish_reason:'stop',message:{role:'assistant',content:JSON.stringify({preferredTopics:[fake.key],conditions:{requiredTopics:[],excludedTopics:[],minYear:null,maxYear:null,maxRuntimeMinutes:null}})}}],usage:{prompt_tokens:100,completion_tokens:40,total_tokens:140}});const send=vi.fn().mockResolvedValue(response);await expect(runDeepSeek(input(),live,fake,send as typeof fetch)).rejects.toMatchObject({code:'MODEL_FORMAT'});expect(send).toHaveBeenCalledTimes(1);
});
