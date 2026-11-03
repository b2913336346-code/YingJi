import test from 'node:test';
import assert from 'node:assert/strict';
import { PassThrough } from 'node:stream';
import { readSecret, readVisible, selectPrompt } from '../scripts/prompt.js';
import { configureLive } from '../scripts/start-live.js';
import { LocalReader, readLocal, runVerification, verifyLive } from '../scripts/test-live.js';
import { Secret } from '../src/credentials.js';
import { createGatewayServer } from '../src/gateway.js';
import { TmdbUpstream } from '../src/tmdb.js';

const TMDB = 'TEST_ONLY_TMDB_READ_TOKEN_0123456789abcdef';
const LOCAL = 'TEST_ONLY_GATEWAY_TOKEN_0123456789abcdef';
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value));

test('terminal secret prompt never echoes input and restores raw mode on completion', async () => {
  const input = Object.assign(new PassThrough(), { isTTY: true, setRawMode(value: boolean) { raw.push(value); } });
  const output = new PassThrough(); let visible = ''; const raw: boolean[] = [];
  output.on('data',chunk => { visible += chunk.toString(); });
  const answer = readSecret('hidden: ',input,output);
  input.write(LOCAL + '\n');
  assert.equal(await answer,LOCAL); assert.equal(visible.includes(LOCAL),false);
  assert.match(visible,/hidden:/); assert.deepEqual(raw,[true,false]); input.destroy(); output.destroy();
});

test('terminal prompt rejects redirected input and handles cancellation without echo', async () => {
  await assert.rejects(readSecret('hidden',new PassThrough(),new PassThrough()), /交互终端/);
  const input = Object.assign(new PassThrough(), { isTTY: true, setRawMode() {} });
  const output = new PassThrough(); let visible = '';
  output.on('data',chunk => { visible += chunk.toString(); });
  const answer = readSecret('hidden: ',input,output);
  input.write(TMDB + '\x03'); await assert.rejects(answer,/取消/);
  assert.equal(visible.includes(TMDB),false); input.destroy(); output.destroy();
});

test('LIVE launcher validates two inputs and passes private objects without mutating its environment', async () => {
  const env = Object.freeze({ PORT: '8788', UPSTREAM_MODE: 'MOCK', TMDB_READ_ACCESS_TOKEN: 'unchanged' });
  const entries = [TMDB,LOCAL]; let prompts = 0; let launches = 0;
  await configureLive(async () => entries[prompts++]!, config => {
    launches++; assert.equal(config.mode,'LIVE');
    if (config.mode !== 'LIVE') assert.fail();
    assert.equal(config.tmdbToken.use(value => value),TMDB); assert.equal(config.accessToken.matches(LOCAL),true);
    assert.equal(JSON.stringify(config).includes(TMDB),false); assert.equal(JSON.stringify(config).includes(LOCAL),false);
  },env);
  assert.equal(prompts,2); assert.equal(launches,1); assert.equal(env.TMDB_READ_ACCESS_TOKEN,'unchanged');
});

test('invalid, cancelled or failed launch never changes provided environment', async () => {
  const env = Object.freeze({ PORT: '8788', TMDB_READ_ACCESS_TOKEN: 'unchanged' });
  let launches = 0;
  await assert.rejects(configureLive(async () => 'bad', () => { launches++; },env));
  await assert.rejects(configureLive(async () => { throw Error('cancelled'); }, () => { launches++; },env));
  assert.equal(launches,0);
  const entries = [TMDB,LOCAL]; let n = 0;
  await assert.rejects(configureLive(async () => entries[n++]!, () => { throw Error('failed launch'); },env));
  assert.equal(env.TMDB_READ_ACCESS_TOKEN,'unchanged');
});

function localFixture(modify?: (path: string,value: Record<string,unknown>) => unknown) {
  const paths: string[] = [];
  const read: LocalReader = async (port,path,authorization) => {
    assert.equal(port,8788); paths.push(path);
    assert.equal(authorization,path === '/health' ? undefined : 'Bearer ' + LOCAL);
    let value: Record<string,unknown>;
    if (path === '/health') value = { mode: 'LIVE', liveUpstreamEnabled: true };
    else if (path.includes('/discover/')) value = { page: 1, results: [{ id: 7 }] };
    else value = { id: 7, title: '电影样例', name: '剧集样例', poster_path: '/sample.png' };
    return { status: 200, headers: { 'x-yingji-upstream': 'LIVE', 'x-yingji-api-contract': 'catalog-live-1',
      'content-type': path.includes('/images/') ? 'image/png' : 'application/json' },
      bytes: path.includes('/images/') ? new Uint8Array([137,80,78,71]) : bytes(modify ? modify(path,value) : value) };
  };
  return { paths,read };
}

test('verifier checks health, both discoveries, both typed details and poster using only local token', async () => {
  const app = localFixture(); const result = await verifyLive(8788,Secret.gateway(LOCAL),app.read);
  assert.deepEqual(app.paths,['/health','/v1/tmdb/discover/movie','/v1/tmdb/movie/7','/v1/tmdb/discover/tv','/v1/tmdb/tv/7','/v1/tmdb/images/w500/sample.png']);
  assert.deepEqual(result,{movie:'passed',tv:'passed',poster:'passed',posterBytes:4,androidDisplayVerified:false});
});

for (const [label,change] of [
  ['empty discovery',(path: string,value: Record<string,unknown>) => path.includes('/discover/') ? {page:1,results:[]} : value],
  ['wrong identity',(path: string,value: Record<string,unknown>) => path === '/v1/tmdb/movie/7' ? {...value,id:8} : value],
  ['missing poster',(path: string,value: Record<string,unknown>) => ({...value,poster_path:null})],
  ['Mock mode',(path: string,value: Record<string,unknown>) => path === '/health' ? {...value,mode:'MOCK'} : value],
] as const) {
  test('verifier cannot report a pass with ' + label, async () => {
    await assert.rejects(verifyLive(8788,Secret.gateway(LOCAL),localFixture(change).read));
  });
}

test('verifier rejects HTTP errors, wrong markers and non-JSON content without leaking bodies', async () => {
  for (const patch of [{status:401},{headers:{'x-yingji-upstream':'MOCK'}},{headers:{'x-yingji-upstream':'LIVE','x-yingji-api-contract':'catalog-live-1','content-type':'text/html'}}]) {
    const read: LocalReader = async () => ({ status:200, headers:{},bytes:bytes({secret:TMDB}),...patch });
    await assert.rejects(verifyLive(8788,Secret.gateway(LOCAL),read),error => {
      assert.ok(error instanceof Error); assert.equal(error.message.includes(TMDB),false); return true;
    });
  }
});

test('interactive verification obtains only the local gateway token', async () => {
  let prompts = 0;
  const result = await runVerification(async prompt => { prompts++; assert.equal(prompt.includes('TMDB'),false); return LOCAL; },localFixture().read,{});
  assert.equal(prompts,1); assert.equal(result.poster,'passed');
});

test('bounded loopback verifier can read a LIVE gateway while upstream remains fully mocked', async t => {
  const upstream = new TmdbUpstream(Secret.tmdb(TMDB),async url => {
    if (url.pathname.includes('/discover/')) return Response.json({page:1,results:[{id:7}]});
    return Response.json({id:7,title:'电影样例',name:'剧集样例',poster_path:null});
  });
  const server = createGatewayServer(upstream,{accessToken:Secret.gateway(LOCAL)});
  await new Promise<void>(resolve => server.listen(0,'127.0.0.1',resolve));
  t.after(() => new Promise<void>((resolve,reject) => { server.close(error => error ? reject(error) : resolve()); server.closeAllConnections(); }));
  const address = server.address(); if (!address || typeof address === 'string') assert.fail();
  const health = await readLocal(address.port,'/health'); assert.equal(health.status,200);
  const detail = await readLocal(address.port,'/v1/tmdb/movie/7','Bearer ' + LOCAL);
  assert.equal(detail.status,200); assert.equal(JSON.parse(new TextDecoder().decode(detail.bytes)).id,7);
  const denied = await readLocal(address.port,'/v1/tmdb/movie/7'); assert.equal(denied.status,401);
  await assert.rejects(readLocal(0,'/health')); await assert.rejects(readLocal(address.port,'https://evil.example/'));
});

test('explicit visible mode echoes input, labels the mode and leaves output open', async () => {
  const raw: boolean[] = [];
  const input = Object.assign(new PassThrough(), { isTTY:true, setRawMode(value: boolean) { raw.push(value); } });
  const output = new PassThrough(); let visible = '';
  output.on('data',chunk => { visible += chunk.toString(); });
  const answer = readVisible('local: ',input,output);
  input.write(LOCAL + '\n');
  assert.equal(await answer,LOCAL); assert.equal(visible.includes(LOCAL),true);
  assert.equal(visible.includes('输入可见'),true); assert.equal(output.writableEnded,false);
  assert.deepEqual(raw,[true,false]); input.destroy(); output.destroy();
});

test('visible input cancellation restores terminal and does not accept redirected input', async () => {
  await assert.rejects(readVisible('local',new PassThrough(),new PassThrough()),/交互终端/);
  const raw: boolean[] = [];
  const input = Object.assign(new PassThrough(), { isTTY:true, setRawMode(value: boolean) { raw.push(value); } });
  const output = new PassThrough(); output.resume();
  const answer = readVisible('local: ',input,output);
  input.write('TEST_ONLY' + '\x03'); await assert.rejects(answer,/取消/);
  assert.deepEqual(raw,[true,false]); assert.equal(output.writableEnded,false);
  input.destroy(); output.destroy();
});

test('only explicit show-input flag enables echo; default and unknown flags stay safe', () => {
  assert.equal(selectPrompt([]),readSecret);
  assert.equal(selectPrompt(['--show-input']),readVisible);
  for (const args of [['--show-input=false'],['--show-input',LOCAL],[LOCAL],['--unknown']])
    assert.throws(() => selectPrompt(args),error => {
      assert.ok(error instanceof Error); assert.equal(error.message.includes(LOCAL),false); return true;
    });
});
