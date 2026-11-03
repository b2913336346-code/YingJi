import { expect, test, vi, afterEach } from 'vitest';
import { runDeepSeek, MODEL_TIMEOUT_MS } from '../src/deepseek';
import type { Bindings } from '../src/env';
import { input, completion, hints, ACCESS, API_KEY } from './fixtures';
const bindings={FINGERPRINT_SECRET:'TEST_ONLY_FINGERPRINT_0000000000000000000000000000',UPSTREAM_MODE:'MOCK',LIVE_AI_ENABLED:'true',DEEPSEEK_MODEL:'deepseek-flash'} as Bindings;
afterEach(()=>vi.useRealTimers());

test('transport failure and retry exhaustion count at most two HTTP attempts',async()=>{
  const send=vi.fn().mockRejectedValue(new Error('private transport failure'));
  await expect(runDeepSeek(input(),bindings,{key:API_KEY,clientId:ACCESS},send as typeof fetch)).rejects.toMatchObject({code:'UPSTREAM_UNAVAILABLE',usage:{httpRequests:2,retries:1,totalTokens:null}});expect(send).toHaveBeenCalledTimes(2);
});
test('provider Retry-After is not ignored and failure is safe for the caller',async()=>{
  const send=vi.fn().mockResolvedValue(new Response('private',{status:429,headers:{'Retry-After':'30'}}));
  await expect(runDeepSeek(input(),bindings,{key:API_KEY,clientId:ACCESS},send as typeof fetch)).rejects.toMatchObject({code:'UPSTREAM_RATE_LIMIT',retryable:true});expect(send).toHaveBeenCalledTimes(1);
});
test('total 15 second deadline aborts a hung request without model switching or extra attempts',async()=>{
  vi.useFakeTimers();let signal:AbortSignal|undefined;
  const send=vi.fn((_url:unknown,init:RequestInit)=>{signal=init.signal!;return new Promise<Response>(()=>{});});
  const done=expect(runDeepSeek(input(),bindings,{key:API_KEY,clientId:ACCESS},send as typeof fetch)).rejects.toMatchObject({code:'UPSTREAM_TIMEOUT',usage:{httpRequests:1,retries:0,totalTokens:null}});
  await vi.advanceTimersByTimeAsync(MODEL_TIMEOUT_MS);await done;expect(signal!.aborted).toBe(true);expect(send).toHaveBeenCalledTimes(1);
});
test('response body has the same deadline and its stream is cancelled',async()=>{
  vi.useFakeTimers();const cancel=vi.fn();const stream=new ReadableStream<Uint8Array>({cancel});
  const send=vi.fn().mockResolvedValue(new Response(stream));
  const done=expect(runDeepSeek(input(),bindings,{key:API_KEY,clientId:ACCESS},send as typeof fetch)).rejects.toMatchObject({code:'UPSTREAM_TIMEOUT'});
  await vi.advanceTimersByTimeAsync(MODEL_TIMEOUT_MS);await done;expect(cancel).toHaveBeenCalledTimes(1);
});
test('truncated tool wrong-model and inconsistent token output fail schema checks',async()=>{
  const cases=[completion(hints(),{choices:[{finish_reason:'length',message:{role:'assistant',content:JSON.stringify(hints())}}]}),
    completion(hints(),{model:'other-model'}),completion(hints(),{usage:{prompt_tokens:100,completion_tokens:40,total_tokens:999}}),
    completion(hints(),{choices:[{finish_reason:'stop',message:{role:'assistant',content:JSON.stringify(hints()),tool_calls:[]}}]})];
  for(const result of cases)await expect(runDeepSeek(input(),bindings,{key:API_KEY,clientId:ACCESS},vi.fn().mockResolvedValue(result) as typeof fetch)).rejects.toMatchObject({code:'MODEL_FORMAT'});
});
test('huge invalid JSON or secret-bearing upstream replies cannot reach the caller',async()=>{
  for(const result of [new Response('{'),new Response('x'.repeat(32769)),completion({...hints(),preferredTopics:[ACCESS]})])
    await expect(runDeepSeek(input(),bindings,{key:API_KEY,clientId:ACCESS},vi.fn().mockResolvedValue(result) as typeof fetch)).rejects.toMatchObject({code:'MODEL_FORMAT'});
});
