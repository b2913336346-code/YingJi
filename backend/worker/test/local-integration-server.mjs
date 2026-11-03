import http from 'node:http';
import { mkdir, mkdtemp, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { Miniflare, Response, Log, LogLevel, convertV4MiniflareOptions } from 'miniflare';

// Local test fixture only: no environment credentials, no real outbound fetch, no deployment.
export const ACCESS = 'TEST_ONLY_ACCESS_0000000000000000000000000000';
const conditions = () => ({requiredTopics:[],excludedTopics:[],minYear:null,maxYear:null,maxRuntimeMinutes:null});
const billing = JSON.stringify({id:'TEST_ONLY_V1',currency:'CNY',verification:'MOCK',inputMicrosPerMillion:'1000000',cachedInputMicrosPerMillion:'100000',outputMicrosPerMillion:'2000000',dailyBudgetMicros:'100000000',totalBudgetMicros:'1000000000'});
export async function createLocalIntegrationServer(port = 8787, trace = () => {}, approvedClients = []) {
  const script = (await build({entryPoints:['src/index.ts'],bundle:true,platform:'neutral',format:'esm',external:['cloudflare:workers'],write:false})).outputFiles[0].text;
  const fixture = await import('data:text/javascript;base64,'+Buffer.from((await build({entryPoints:['test/fixtures.ts'],bundle:true,platform:'neutral',format:'esm',write:false})).outputFiles[0].text).toString('base64'));
  // Public pins are explicitly supplied by the local operator. No HTTP enrollment route exists.
  if (!Array.isArray(approvedClients) || approvedClients.length > 2) throw Error('Invalid local public pins');
  for (const pin of approvedClients) {
    if (!pin || Object.keys(pin).sort().join(',') !== 'id,jwk' || !/^[A-Za-z0-9_-]{8,100}$/.test(pin.id) ||
      !pin.jwk || Object.keys(pin.jwk).sort().join(',') !== 'crv,kty,x,y' || pin.jwk.kty !== 'EC' || pin.jwk.crv !== 'P-256' ||
      !/^[-_A-Za-z0-9]{43}$/.test(pin.jwk.x) || !/^[-_A-Za-z0-9]{43}$/.test(pin.jwk.y)) throw Error('Invalid local public pin');
  }
  const pins = [...JSON.parse(fixture.CLIENT_PUBLIC_KEYS), ...approvedClients];
  if (new Set(pins.map(pin=>pin.id)).size !== pins.length) throw Error('Duplicate local client');
  await mkdir('.wrangler', {recursive:true});
  const base = await mkdtemp(resolve('.wrangler', 'a8-local-'));
  const sessions = new Map();
  async function session(id) {
    if (sessions.has(id)) return sessions.get(id);
    if (sessions.size >= 8) { const [key, old] = sessions.entries().next().value; await old.runtime.dispose(); sessions.delete(key); }
    const state = {verifications:0,fault:'normal',modelCalls:0,posts:0,gets:0,releases:[]};
    const options = convertV4MiniflareOptions({name:'yingji-a8-local',modules:true,script,compatibilityDate:'2026-10-19',cf:false,
      telemetry:{enabled:false},log:new Log(LogLevel.ERROR),resourcePersistencePath:resolve(base,id),
      bindings:{CLIENT_PUBLIC_KEYS:JSON.stringify(pins),FINGERPRINT_SECRET:fixture.FINGERPRINT_SECRET,UPSTREAM_MODE:'MOCK',DEEPSEEK_MODEL:'deepseek-flash',LIVE_AI_ENABLED:'true',BILLING_POLICY:billing},
      durableObjects:{REQUEST_GATE:{className:'RequestGate',useSQLite:true}},
      outboundService: async request => {
        if (request.url === 'https://api.deepseek.com/user/balance') {
          state.verifications++;
          if (state.fault === 'verify-hold') await new Promise(r => state.releases.push(r));
          if (state.fault === 'verify-auth' || request.headers.get('authorization')?.includes('INVALID')) return new Response('synthetic auth failure', {status:401});
          const failures = {'verify-forbidden':403,'verify-billing':402,'verify-rate':429};
          if (failures[state.fault]) return new Response('synthetic provider failure', {status:failures[state.fault]});
          return Response.json({is_available:true,balance_infos:[]});
        }
        if (request.url !== 'https://api.deepseek.com/chat/completions') throw Error('Unexpected outbound URL');
        await request.text(); state.modelCalls++;
        if (state.fault === 'hold') await new Promise(r => state.releases.push(r));
        const content = state.fault === 'model-format' ? '{invalid' : JSON.stringify({preferredTopics:['科幻'],conditions:conditions()});
        return Response.json({model:'deepseek-flash',choices:[{finish_reason:'stop',message:{role:'assistant',content}}],usage:{prompt_tokens:100,completion_tokens:40,total_tokens:140}});
      }});
    state.runtime = new Miniflare(options); sessions.set(id,state); return state;
  }
  const server = http.createServer(async (req,res) => {
    try {
      const id = req.headers['x-yingji-mock-session'] || 'android-home';
      if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(id)) { res.writeHead(400).end(); return; }
      if (req.url === '/__test/ready') { res.writeHead(200,{'content-type':'application/json'}).end('{"mock":true}'); return; }
      let body = ''; for await (const chunk of req) { body += chunk; if (Buffer.byteLength(body) > 16384) { res.writeHead(413).end(); return; } }
      const state = await session(id);
      if (req.url === '/__test/state') {
        if (req.method === 'POST') {
          const command = JSON.parse(body);
          if (!['normal','model-format','wrong-version','invalid-json','redirect','hold','unavailable','verify-hold','verify-auth','verify-forbidden','verify-billing','verify-rate'].includes(command.fault)) throw Error('Unknown fixture');
          state.fault = command.fault;
          if (command.release) state.releases.splice(0).forEach(r=>r());
        }
        res.writeHead(200,{'content-type':'application/json'}).end(JSON.stringify({verifications:state.verifications,modelCalls:state.modelCalls,posts:state.posts,gets:state.gets,held:state.releases.length})); return;
      }
      if (!['/v1/ai/quota','/v1/ai/recommend','/v1/ai/verify','/v1/ai/health'].includes(req.url)) { res.writeHead(404).end(); return; }
      if (req.method === 'POST') state.posts++; else state.gets++;
      let safe = {session:id,route:req.url,method:req.method};
      if (body) { const input = (()=>{try{const value=JSON.parse(body);return value&&typeof value==='object'&&!Array.isArray(value)?value:{}}catch{return {}}})(); safe = {...safe,requestId:typeof input.requestId==='string'&&/^[a-f0-9-]{36}$/i.test(input.requestId)?input.requestId:undefined,mode:['FOR_YOU','TONIGHT','EXPLORE','QUERY'].includes(input.mode)?input.mode:undefined,profileVersion:Number.isSafeInteger(input.profileVersion)?input.profileVersion:undefined,sourceSettingsVersion:Number.isSafeInteger(input.sourceSettingsVersion)?input.sourceSettingsVersion:undefined,queryLength:[...(input.userQuery||'')].length,explicitCount:input.profileSummary?.explicit?.length,inferredCount:input.profileSummary?.inferred?.length,keys:Object.keys(input).filter(key=>['schemaVersion','requestId','mode','userQuery','externalProcessingConsent','profileVersion','sourceSettingsVersion','profileSummary','hardConditions','excludeExternalIds'].includes(key))}; }
      if (state.fault === 'redirect') { res.writeHead(302,{location:'https://example.invalid/forbidden'}).end(); trace({...safe,status:302}); return; }
      if (state.fault === 'unavailable') { res.writeHead(503,{'content-type':'application/json'}).end('{"unavailable":true}'); trace({...safe,status:503}); return; }
      const response = await state.runtime.dispatchFetch('https://local.test'+req.url,{method:req.method,headers:Object.fromEntries(['authorization','content-type','x-deepseek-key','x-yingji-auth-version','x-yingji-timestamp','x-yingji-nonce','x-yingji-signature'].filter(name=>typeof req.headers[name]==='string').map(name=>[name,req.headers[name]])),...(body?{body}:{})});
      let output = await response.text();
      if (req.method === 'POST' && state.fault === 'invalid-json') output = '{invalid';
      if (req.method === 'POST' && state.fault === 'wrong-version') { const value = JSON.parse(output); value.profileVersion++; output=JSON.stringify(value); }
      res.writeHead(response.status,{'content-type':'application/json','X-Yingji-Api-Contract':response.headers.get('X-Yingji-Api-Contract')??''}).end(output);
      let value; try { value=JSON.parse(output); } catch {}
      trace({...safe,status:response.status,taskStatus:value?.status,error:value?.error?.code,quota:value?.quota?.status,used:value?.quota?.used,reserved:value?.quota?.reserved,modelCalls:state.modelCalls});
    } catch { if (!res.headersSent) res.writeHead(500,{'content-type':'application/json'}); res.end('{"fixtureFailure":true}'); }
  });
  await new Promise((ok,bad)=>{server.once('error',bad);server.listen(port,'127.0.0.1',ok)});
  return {port:server.address().port, close:async()=>{for(const s of sessions.values()) s.releases.splice(0).forEach(r=>r());await new Promise(r=>server.close(r));await Promise.all([...sessions.values()].map(s=>s.runtime.dispose()));}};
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const pinArg = process.argv.indexOf('--android-public-key');
  const approved = pinArg < 0 ? [] : [JSON.parse(await readFile(process.argv[pinArg+1], 'utf8'))];
  const fixture = await createLocalIntegrationServer(8787,row=>console.log(JSON.stringify(row)),approved);
  console.log(JSON.stringify({ready:true,host:'127.0.0.1',port:fixture.port,mock:true,realOutbound:false}));
  let closing = false;
  const close = async()=>{if(closing)return;closing=true;await fixture.close();process.exit(0)};
  process.once('SIGINT',close);
  // Automated local runs own this stdin pipe and close it after device validation.
  process.stdin.resume(); process.stdin.once('end',close);
}
