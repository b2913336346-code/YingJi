import assert from 'node:assert/strict';
import test from 'node:test';
import {generateKeyPairSync,sign,createHash,randomUUID} from 'node:crypto';
import {createLocalIntegrationServer} from './local-integration-server.mjs';
const hash=value=>createHash('sha256').update(value).digest('hex');
test('explicit public pin approves a generated client, signatures bind the key and replay never repeats verification', {timeout:30000}, async()=>{
 const pair=generateKeyPairSync('ec',{namedCurve:'P-256'});const exported=pair.publicKey.export({format:'jwk'});
 const pin={id:'android_host_generated_client',jwk:{kty:'EC',crv:'P-256',x:exported.x,y:exported.y}};
 const trace=[];const server=await createLocalIntegrationServer(0,row=>trace.push(row),[pin]);
 const body=JSON.stringify({schemaVersion:1,requestId:randomUUID()}),key='TEST_ONLY_BYOK_host_generated';
 const proof=(target,raw,credential,id=pin.id)=>{
  const authorization='Signature '+id,time=Date.now().toString(),nonce=randomUUID();
  const message=['YINGJI-BYOK-1',raw?'POST':'GET','https://local.test'+target,authorization,time,nonce,hash(raw),hash(credential)].join(String.fromCharCode(10));
  return {'Authorization':authorization,'X-Yingji-Auth-Version':'1','X-Yingji-Timestamp':time,'X-Yingji-Nonce':nonce,'X-Yingji-Signature':sign('sha256',Buffer.from(message),{key:pair.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url'),...(raw?{'Content-Type':'application/json','X-DeepSeek-Key':credential}:{})};
 };
 const send=async(path,headers,raw='')=>fetch('http://127.0.0.1:'+server.port+path,{method:raw?'POST':'GET',headers,...(raw?{body:raw}:{})});
 try {
  const headers=proof('/v1/ai/verify',body,key);const first=await send('/v1/ai/verify',headers,body);
  assert.equal(first.status,200);assert.equal(first.headers.get('x-yingji-api-contract'),'byok-1');assert.equal((await first.json()).verified,true);
  const replay=await send('/v1/ai/verify',headers,body);assert.equal(replay.status,409);assert.equal((await replay.json()).error.code,'NONCE_REPLAY');
  const idem=await send('/v1/ai/verify',proof('/v1/ai/verify',body,key),body);assert.equal((await idem.json()).verified,true);
  const tampered=await send('/v1/ai/verify',{...proof('/v1/ai/verify',body,key),'X-DeepSeek-Key':key+'_changed'},body);assert.equal(tampered.status,401);
  const unknown=await send('/v1/ai/health',proof('/v1/ai/health','','','unapproved_android_client'));assert.equal(unknown.status,401);
  const enrollment=await send('/__test/approve',{},JSON.stringify(pin));assert.equal(enrollment.status,404);
  const state=await (await send('/__test/state',{})).json();assert.equal(state.verifications,1);assert.equal(state.modelCalls,0);
  const quota=await (await send('/v1/ai/quota',proof('/v1/ai/quota','',''))).json();assert.equal(quota.quota.remaining,30);
  assert(!JSON.stringify(trace).includes(key));assert(!JSON.stringify(trace).includes(exported.x));
 }finally{await server.close();}
});
test('local public-pin setup refuses private keys and duplicate identities',async()=>{
 const pair=generateKeyPairSync('ec',{namedCurve:'P-256'});const jwk=pair.privateKey.export({format:'jwk'});
 await assert.rejects(createLocalIntegrationServer(0,()=>{},[{id:'android_private_forbidden',jwk}]));
 const pub={kty:'EC',crv:'P-256',x:jwk.x,y:jwk.y};
 await assert.rejects(createLocalIntegrationServer(0,()=>{},[{id:'android_duplicate',jwk:pub},{id:'android_duplicate',jwk:pub}]));
});
