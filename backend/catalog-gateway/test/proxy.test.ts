import test, { TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { createServer as httpServer, IncomingHttpHeaders } from 'node:http';
import { createServer as httpsServer, request as httpsRequest } from 'node:https';
import { connect } from 'node:net';
import { Duplex } from 'node:stream';
import { generate } from 'selfsigned';
import { parseLocalHttpProxy } from '../src/proxy-config.js';
import { createProxyHttpsExecutor } from '../src/proxy.js';
import { CatalogRequest, GatewayError, resolveRoute } from '../src/contract.js';
import { Secret, startupConfig } from '../src/credentials.js';
import { HttpsRequester, TmdbUpstream } from '../src/tmdb.js';
import { readPayload } from '../src/upstream.js';
import { configureLive, parseLiveArgs } from '../scripts/start-live.js';
import { readSecret, readVisible } from '../scripts/prompt.js';
import { MockUpstream } from '../src/mock.js';

const TOKEN = 'TEST_ONLY_TMDB_READ_TOKEN_0123456789abcdef';
const LOCAL = 'TEST_ONLY_GATEWAY_TOKEN_0123456789abcdef';
const certificate = generate([{ name:'commonName', value:'YingJi proxy transport test only' }], {
  keySize:2048, days:1, algorithm:'sha256', extensions:[
    { name:'basicConstraints', cA:true },
    { name:'subjectAltName', altNames:[{type:2,value:'api.themoviedb.org'},{type:2,value:'image.tmdb.org'}] },
  ],
});
function route(path: string): CatalogRequest {
  const value = resolveRoute(path); if (value.kind === 'health') assert.fail(); return value;
}
const trustedRequester: HttpsRequester = (url,options,callback) => {
  assert.equal(options.rejectUnauthorized,true); assert.equal(options.minVersion,'TLSv1.2');
  return httpsRequest(url,{...options,ca:certificate.cert},callback);
};
function deferred<T>() { let resolve!: (value:T)=>void; const promise=new Promise<T>(r=>{resolve=r;}); return {promise,resolve}; }

async function proxyFixture(t: TestContext, behavior: 'tunnel'|'reject'|'redirect'|'stall'|'reset' = 'tunnel', bodyStall = false) {
  const sockets = new Set<Duplex>();
  const seenConnects: {path:string; headers:IncomingHttpHeaders}[] = [];
  const seenRequests: {host:string|undefined; authorization:string|undefined; path:string|undefined}[] = [];
  const connected = deferred<void>(); const proxyClosed = deferred<void>();
  const upstream = httpsServer({key:certificate.private,cert:certificate.cert},(req,res)=>{
    seenRequests.push({host:req.headers.host,authorization:req.headers.authorization,path:req.url});
    if (bodyStall) { res.writeHead(200,{'content-type':'application/json'}); res.flushHeaders(); return; }
    if (req.url?.startsWith('/t/p/')) {
      void new MockUpstream().load(route('/v1/tmdb/images/w500/yingji-mock-movie.png'),new AbortController().signal)
        .then(async image=>{res.writeHead(200,{'content-type':'image/png'});res.end(Buffer.from(await image.arrayBuffer()));});
    } else {
      const tv = req.url?.startsWith('/3/tv/');
      res.writeHead(200,{'content-type':'application/json'});
      res.end(JSON.stringify({id:7,adult:false,[tv?'name':'title']:'代理 TLS 合成样例',
        [tv?'first_air_date':'release_date']:'2024-01-01',genres:[],overview:null,poster_path:null}));
    }
  });
  upstream.on('connection',socket=>{sockets.add(socket);socket.once('close',()=>sockets.delete(socket));});
  upstream.on('tlsClientError',()=>{});
  await new Promise<void>(resolve=>upstream.listen(0,'127.0.0.1',resolve));
  const targetAddress=upstream.address();if(!targetAddress||typeof targetAddress==='string')assert.fail();
  const targetPort=targetAddress.port;
  const proxy=httpServer();
  proxy.on('connection',socket=>{sockets.add(socket);socket.once('close',()=>sockets.delete(socket));});
  proxy.on('connect',(req,client,head)=>{
    seenConnects.push({path:req.url??'',headers:req.headers});connected.resolve();
    client.once('close',()=>proxyClosed.resolve());client.on('error',()=>{});client.once('end',()=>client.end());
    if(behavior==='reject'||behavior==='redirect'){
      const status=behavior==='reject'?'407 Proxy Authentication Required':'302 Found';
      client.end(['HTTP/1.1 '+status,'Content-Length: 0','Location: http://evil.example','',''].join(String.fromCharCode(13,10)));return;
    }
    if(behavior==='stall')return;
    if(behavior==='reset'){client.destroy();return;}
    const target=connect({host:'127.0.0.1',port:targetPort},()=>{
      client.write(['HTTP/1.1 200 Connection Established','',''].join(String.fromCharCode(13,10)));
      if(head.length)target.write(head);client.pipe(target);target.pipe(client);
    });
    sockets.add(target);target.once('close',()=>sockets.delete(target));
    target.on('error',()=>client.destroy());client.once('close',()=>target.destroy());target.once('close',()=>client.destroy());
  });
  await new Promise<void>(resolve=>proxy.listen(0,'127.0.0.1',resolve));
  const address=proxy.address();if(!address||typeof address==='string')assert.fail();
  t.after(async()=>{
    for(const socket of sockets)socket.destroy();
    await Promise.all([new Promise<void>(resolve=>proxy.close(()=>resolve())),new Promise<void>(resolve=>upstream.close(()=>resolve()))]);
  });
  const settings=parseLocalHttpProxy('http://127.0.0.1:'+address.port)!;
  return {settings,seenConnects,seenRequests,connected,proxyClosed};
}

test('proxy is opt-in, accepts an explicit loopback port and canonicalizes its trailing slash',()=>{
  assert.equal(parseLocalHttpProxy(undefined),undefined);assert.equal(parseLocalHttpProxy(''),undefined);
  assert.deepEqual(parseLocalHttpProxy('http://127.0.0.1:7897/'),{url:'http://127.0.0.1:7897'});
  assert.deepEqual(parseLocalHttpProxy('http://127.0.0.1:65535'),{url:'http://127.0.0.1:65535'});
});
for(const value of ['https://127.0.0.1:7897','socks5://127.0.0.1:7897','http://localhost:7897','http://192.168.1.1:7897',
  'http://127.0.0.1:0','http://127.0.0.1:65536','http://127.0.0.1:07897','http://127.0.0.1:7897/path',
  'http://127.0.0.1:7897?x=1','http://127.0.0.1:7897#x','http://user:TEST_ONLY_SECRET@127.0.0.1:7897','TEST_ONLY_SECRET']){
  test('reject unsafe proxy configuration without echo: '+value,()=>{
    assert.throws(()=>parseLocalHttpProxy(value),error=>{assert.ok(error instanceof Error);assert.equal(error.message.includes('TEST_ONLY_SECRET'),false);return true;});
  });
}

test('startup and interactive launcher carry explicit proxy without reading global proxy variables',async()=>{
  const env=Object.freeze({UPSTREAM_MODE:'LIVE',TMDB_READ_ACCESS_TOKEN:TOKEN,YINGJI_GATEWAY_TOKEN:LOCAL,
    TMDB_HTTP_PROXY:'http://127.0.0.1:7897',HTTP_PROXY:'http://evil.example:80',HTTPS_PROXY:'http://evil.example:443'});
  const config=startupConfig(env);if(config.mode!=='LIVE')assert.fail();
  assert.equal(config.httpProxy?.url,'http://127.0.0.1:7897');
  const direct=startupConfig({...env,TMDB_HTTP_PROXY:''});if(direct.mode!=='LIVE')assert.fail();assert.equal(direct.httpProxy,undefined);
  assert.deepEqual(startupConfig({UPSTREAM_MODE:'MOCK',TMDB_HTTP_PROXY:'bad'}),{mode:'MOCK',port:8788});
  const inputs=[TOKEN,LOCAL];let n=0;let launches=0;
  await configureLive(async()=>inputs[n++]!,value=>{launches++;if(value.mode!=='LIVE')assert.fail();assert.equal(value.httpProxy?.url,'http://127.0.0.1:7897');},env);
  assert.equal(launches,1);assert.equal(env.TMDB_HTTP_PROXY,'http://127.0.0.1:7897');
});

test('invalid proxy fails before requesting credentials or starting a service',async()=>{
  let prompts=0;let launches=0;
  await assert.rejects(configureLive(async()=>{prompts++;return TOKEN;},()=>{launches++;},{TMDB_HTTP_PROXY:'http://evil.example:7897'}));
  assert.equal(prompts,0);assert.equal(launches,0);
});

test('proxy CLI preserves hidden/visible choice, environment fallback and explicit precedence',()=>{
  const hidden=parseLiveArgs(['--proxy','http://127.0.0.1:7897'],{});
  assert.equal(hidden.prompt,readSecret);assert.equal(hidden.env.TMDB_HTTP_PROXY,'http://127.0.0.1:7897');
  const visible=parseLiveArgs(['--show-input','--proxy','http://127.0.0.1:7897'],{TMDB_HTTP_PROXY:'http://127.0.0.1:7890'});
  assert.equal(visible.prompt,readVisible);assert.equal(visible.env.TMDB_HTTP_PROXY,'http://127.0.0.1:7897');
  assert.equal(parseLiveArgs([],{TMDB_HTTP_PROXY:'http://127.0.0.1:7897'}).env.TMDB_HTTP_PROXY,'http://127.0.0.1:7897');
  for(const args of [['--proxy'],['--proxy',''],['--proxy','http://evil.example:1'],['--proxy','http://127.0.0.1:7897','--proxy','http://127.0.0.1:7898'],['--show-input','--show-input'],['--unknown']])
    assert.throws(()=>parseLiveArgs(args,{}));
});

for(const path of ['/v1/tmdb/movie/7','/v1/tmdb/tv/7','/v1/tmdb/images/w500/yingji-mock-movie.png']){
  test('verified TLS through CONNECT, with API/image credential separation: '+path,async t=>{
    const app=await proxyFixture(t);
    const adapter=new TmdbUpstream(Secret.tmdb(TOKEN),createProxyHttpsExecutor(app.settings,trustedRequester));
    const payload=await readPayload(adapter,route(path),AbortSignal.timeout(3000));
    assert.equal(app.seenConnects.length,1);assert.equal(app.seenRequests.length,1);
    const image=path.includes('/images/');
    assert.equal(app.seenConnects[0]!.path,(image?'image.tmdb.org':'api.themoviedb.org')+':443');
    assert.equal(app.seenConnects[0]!.headers.authorization,undefined);assert.equal(app.seenConnects[0]!.headers['proxy-authorization'],undefined);
    assert.equal(JSON.stringify(app.seenConnects).includes(TOKEN),false);
    assert.equal(app.seenRequests[0]!.authorization,image?undefined:'Bearer '+TOKEN);
    if(image){assert.equal(payload.contentType,'image/png');assert.deepEqual([...payload.bytes.slice(0,8)],[137,80,78,71,13,10,26,10]);}
    else assert.equal(JSON.parse(new TextDecoder().decode(payload.bytes)).id,7);
    await app.proxyClosed.promise;
  });
}

for(const behavior of ['reject','redirect','reset'] as const){
  test('proxy '+behavior+' never retries or falls back to direct connection',async t=>{
    const app=await proxyFixture(t,behavior);
    const adapter=new TmdbUpstream(Secret.tmdb(TOKEN),createProxyHttpsExecutor(app.settings,trustedRequester));
    await assert.rejects(readPayload(adapter,route('/v1/tmdb/movie/7'),AbortSignal.timeout(3000)),error=>{
      assert.ok(error instanceof GatewayError);assert.equal(error.status,502);assert.equal(error.message.includes(TOKEN),false);return true;
    });
    assert.equal(app.seenConnects.length,1);assert.equal(app.seenRequests.length,0);
    assert.equal(JSON.stringify(app.seenConnects).includes(TOKEN),false);
    await app.proxyClosed.promise;
  });
}

test('untrusted TLS certificate through proxy is rejected before sending API credentials',async t=>{
  const app=await proxyFixture(t);
  const adapter=new TmdbUpstream(Secret.tmdb(TOKEN),createProxyHttpsExecutor(app.settings));
  await assert.rejects(readPayload(adapter,route('/v1/tmdb/movie/7'),AbortSignal.timeout(3000)),GatewayError);
  assert.equal(app.seenConnects.length,1);assert.equal(app.seenRequests.length,0);
  assert.equal(JSON.stringify(app.seenConnects).includes(TOKEN),false);
  await app.proxyClosed.promise;
});

test('cancelling a stalled CONNECT closes proxy socket and preserves timeout reason',async t=>{
  const app=await proxyFixture(t,'stall');
  const adapter=new TmdbUpstream(Secret.tmdb(TOKEN),createProxyHttpsExecutor(app.settings,trustedRequester));
  const controller=new AbortController();
  const pending=readPayload(adapter,route('/v1/tmdb/movie/7'),controller.signal);
  await app.connected.promise;
  const reason=new GatewayError(504,'UPSTREAM_TIMEOUT','test deadline');controller.abort(reason);
  await assert.rejects(pending,error=>error===reason);await app.proxyClosed.promise;
  assert.equal(app.seenRequests.length,0);
});

test('cancelling proxied response body releases the tunnel',async t=>{
  const app=await proxyFixture(t,'tunnel',true);
  const adapter=new TmdbUpstream(Secret.tmdb(TOKEN),createProxyHttpsExecutor(app.settings,trustedRequester));
  const controller=new AbortController();const response=await adapter.load(route('/v1/tmdb/movie/7'),controller.signal);
  const reading=response.arrayBuffer();const reason=new GatewayError(504,'UPSTREAM_TIMEOUT','body deadline');controller.abort(reason);
  await assert.rejects(reading,error=>error===reason);await app.proxyClosed.promise;
});

test('invalid internal targets and pre-cancelled requests never reach proxy',async t=>{
  const app=await proxyFixture(t);const execute=createProxyHttpsExecutor(app.settings,trustedRequester);
  for(const target of ['http://api.themoviedb.org/3/movie/7','https://evil.example/','https://api.themoviedb.org:8443/3/movie/7','https://user:pass@api.themoviedb.org/3/movie/7'])
    await assert.rejects(execute(new URL(target),{},new AbortController().signal),GatewayError);
  const controller=new AbortController();controller.abort();
  await assert.rejects(execute(new URL('https://api.themoviedb.org/3/movie/7'),{},controller.signal));
  assert.equal(app.seenConnects.length,0);
});

test('unavailable local proxy fails without a direct fallback',async()=>{
  const reservation=httpServer();await new Promise<void>(resolve=>reservation.listen(0,'127.0.0.1',resolve));
  const address=reservation.address();if(!address||typeof address==='string')assert.fail();
  await new Promise<void>(resolve=>reservation.close(()=>resolve()));
  let requests=0;
  const requester:HttpsRequester=(url,options,callback)=>{requests++;assert.notEqual(options.agent,false);return httpsRequest(url,options,callback);};
  const adapter=new TmdbUpstream(Secret.tmdb(TOKEN),createProxyHttpsExecutor(parseLocalHttpProxy('http://127.0.0.1:'+address.port)!,requester));
  await assert.rejects(readPayload(adapter,route('/v1/tmdb/movie/7'),AbortSignal.timeout(3000)),error=>{
    assert.ok(error instanceof GatewayError);assert.equal(error.code,'UPSTREAM_UNAVAILABLE');return true;
  });
  assert.equal(requests,1);
});
