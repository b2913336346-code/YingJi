import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdir, mkdtemp } from 'node:fs/promises';
import { resolve } from 'node:path';
import { build } from 'esbuild';
import { Miniflare, Response, Log, LogLevel, convertV4MiniflareOptions } from 'miniflare';

// Run outside Vitest's runner Durable Object: no response streams cross its I/O context.
// The same source and all late-result/restart assertions are exercised through real dispatch.
test('late authorization is rejected, survives graceful eviction and a fresh workerd process', {timeout:10000}, async()=>{
  const compile=async entry=>(await build({entryPoints:[entry],bundle:true,platform:'neutral',format:'esm',external:['cloudflare:workers'],write:false})).outputFiles[0].text;
  const script=await compile('src/index.ts');
  const fixtures=await import('data:text/javascript;base64,'+Buffer.from(await compile('test/fixtures.ts')).toString('base64'));
  await mkdir('.wrangler',{recursive:true});
  const persist=await mkdtemp(resolve('.wrangler','a5-ledger-'));
  let release, started;const ready=new Promise(r=>started=r);const released=new Promise(r=>release=r);let calls=0;
  const options=convertV4MiniflareOptions({name:'yingji-a5-integration',modules:true,script,compatibilityDate:'2026-10-19',cf:false,
    telemetry:{enabled:false},log:new Log(LogLevel.ERROR),resourcePersistencePath:persist,
    bindings:{CLIENT_PUBLIC_KEYS:fixtures.CLIENT_PUBLIC_KEYS,FINGERPRINT_SECRET:fixtures.FINGERPRINT_SECRET,UPSTREAM_MODE:'MOCK',DEEPSEEK_MODEL:'deepseek-flash',LIVE_AI_ENABLED:'true',BILLING_POLICY:"{\"id\":\"TEST_ONLY_V1\",\"currency\":\"CNY\",\"verification\":\"MOCK\",\"inputMicrosPerMillion\":\"1000000\",\"cachedInputMicrosPerMillion\":\"100000\",\"outputMicrosPerMillion\":\"2000000\",\"dailyBudgetMicros\":\"100000000\",\"totalBudgetMicros\":\"1000000000\"}"},
    durableObjects:{REQUEST_GATE:{className:'RequestGate',useSQLite:true}},
    outboundService:async request=>{
      assert.equal(request.url,'https://api.deepseek.com/chat/completions');calls++;
      await request.text();if(calls===1){started();await released;}
      return Response.json(fixtures.completionBody());
    }});
  let runtime=new Miniflare(options);
  let lastRequest;
  const dispatch=async body=>{const request=await fixtures.httpRequest(body);lastRequest=request;const result=await runtime.dispatchFetch(request.url,{
    method:'POST',headers:Object.fromEntries(request.headers),body:JSON.stringify(body)});
    return {status:result.status,body:await result.json()};};
  try {
    const old=fixtures.input();const first=dispatch(old);await ready;
    const duplicate=await dispatch(old);assert.equal(duplicate.status,202);assert.equal(duplicate.body.status,'pending');assert.equal(duplicate.body.quota.reserved,1);assert.equal(duplicate.body.quota.used,0);assert.equal(duplicate.body.quota.remaining,29);
    const newer=await dispatch(fixtures.input({profileVersion:2,sourceSettingsVersion:2}));assert.equal(newer.status,409);assert.equal(newer.body.error.code,'REQUEST_IN_PROGRESS');
    release();const late=await first;assert.equal(late.status,409);assert.equal(late.body.recommendationHints,null);assert.equal(late.body.error.code,'STALE_PROFILE');assert.equal(late.body.quota.used,0);assert.equal(late.body.quota.reserved,0);
    assert.equal((await dispatch(old)).status,409);
    await runtime.unsafeEvictDurableObject('yingji-a5-integration','RequestGate',{name:'yingji-personal-v1'});
    assert.equal((await dispatch(old)).status,409);assert.equal(calls,1);
    await runtime.dispose();runtime=new Miniflare(options);
    assert.equal((await dispatch(old)).status,409);assert.equal(calls,1);
    const current=fixtures.input({profileVersion:2,sourceSettingsVersion:2});const valid=await dispatch(current);
    assert.equal(valid.status,200);assert.equal(valid.body.status,'success');assert.equal(calls,2);
    const packet=lastRequest;
    await runtime.dispose();runtime=new Miniflare(options);
    const replay=await runtime.dispatchFetch(packet.url,{method:'POST',headers:Object.fromEntries(packet.headers),body:JSON.stringify(current)});
    assert.equal(replay.status,409);assert.equal((await replay.json()).error.code,'NONCE_REPLAY');assert.equal(calls,2);
    assert.deepEqual(await dispatch(current),valid);assert.equal(calls,2);
    const quotaResponse=await runtime.dispatchFetch('https://unit.test/v1/ai/quota',{headers:Object.fromEntries((await fixtures.getRequest('/v1/ai/quota')).headers)});assert.equal(quotaResponse.status,200);const summary=await quotaResponse.json();
    assert.equal(summary.total.successes,1);assert.equal(summary.total.failedTasks,1);assert.equal(summary.total.httpRequests,2);assert.equal(summary.total.committedMicros,'360');assert.equal(summary.total.reservedMicros,'0');
  } finally {release();await runtime.dispose();}
});
