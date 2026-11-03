import assert from 'node:assert/strict';
import test from 'node:test';
import { randomUUID } from 'node:crypto';
import { build } from 'esbuild';
import { createLocalIntegrationServer, ACCESS } from './local-integration-server.mjs';
const blank=()=>({requiredTopics:[],excludedTopics:[],minYear:null,maxYear:null,maxRuntimeMinutes:null});
const input=()=>({schemaVersion:1,requestId:randomUUID(),mode:'FOR_YOU',userQuery:'',externalProcessingConsent:true,profileVersion:1,sourceSettingsVersion:1,profileSummary:{enabledSources:{ratings:false,behavior:false,feedback:true},explicit:[],inferred:[]},excludeExternalIds:[],hardConditions:blank()});
test('local HTTP bridge uses real Worker admission, idempotence, versions, format and quota', {timeout:60000}, async()=>{
 const fixtures=await import('data:text/javascript;base64,'+Buffer.from((await build({entryPoints:['test/fixtures.ts'],bundle:true,platform:'neutral',format:'esm',write:false})).outputFiles[0].text).toString('base64'));
 const trace=[]; const server=await createLocalIntegrationServer(0,row=>trace.push(row));
 const send=async(path,body,session='host',token=ACCESS)=>{const proof=await fixtures.rawHttpRequest(body?JSON.stringify(body):'',{Authorization:'Signature '+token},'https://local.test'+path,body?'POST':'GET');const r=await fetch('http://127.0.0.1:'+server.port+path,{method:proof.method,headers:{...Object.fromEntries(proof.headers),'X-Yingji-Mock-Session':session},...(body?{body:JSON.stringify(body)}:{})});return {status:r.status,body:await r.json()};};
 try {
  const request=input(); const before=await send('/v1/ai/quota'); assert.equal(before.body.quota.remaining,30);
  const first=await send('/v1/ai/recommend',request); assert.equal(first.status,200); assert.equal(first.body.quota.used,1); assert.equal(first.body.usage.totalTokens,140);
  assert.deepEqual(await send('/v1/ai/recommend',request),first);
  assert.equal((await send('/__test/state')).body.modelCalls,1);
  const denied=await send('/v1/ai/recommend',input(),'host','TEST_ONLY_INVALID_000000000000000000000000');assert.equal(denied.status,401);
  const newer={...input(),profileVersion:2};assert.equal((await send('/v1/ai/recommend',newer)).status,200);
  assert.equal((await send('/v1/ai/recommend',input())).body.error.code,'STALE_PROFILE');
  await send('/__test/state',{fault:'model-format'},'format'); const malformed=await send('/v1/ai/recommend',input(),'format');assert.equal(malformed.body.error.code,'MODEL_FORMAT');assert.equal(malformed.body.quota.used,0);
  for(let i=0;i<6;i++) assert.equal((await send('/v1/ai/recommend',input(),'limit')).status,200);
  const exhausted=await send('/v1/ai/recommend',input(),'limit');assert.equal(exhausted.body.error.code,'RATE_LIMITED');assert.equal((await send('/__test/state',null,'limit')).body.modelCalls,6);
  const final=await send('/v1/ai/quota',null,'limit');assert.equal(final.body.quota.remaining,24);assert.equal(final.body.total.successes,6);
  assert.equal((await send('/v1/ai/recommend',{...input(),requestId:fixtures.API_KEY})).status,400);
  assert.equal((await send('/v1/ai/recommend',{...input(),[fixtures.API_KEY]:'redacted'})).status,400);
  for(const raw of ['{','null']){const signed=await fixtures.rawHttpRequest(raw,{},'https://local.test/v1/ai/recommend');const invalid=await fetch('http://127.0.0.1:'+server.port+'/v1/ai/recommend',{method:'POST',headers:{...Object.fromEntries(signed.headers),'X-Yingji-Mock-Session':'host'},body:raw});assert.equal(invalid.status,400);}
  const serialized=JSON.stringify(trace);assert(!serialized.includes(ACCESS));assert(!serialized.includes('TEST_ONLY_PROVIDER'));assert(!serialized.includes('enabledSources'));assert(!serialized.includes('userQuery":"'));
 }finally{await server.close();}
});
