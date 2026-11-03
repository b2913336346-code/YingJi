import test, { TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { IncomingMessage, ClientRequest, request } from 'node:http';
import { Socket } from 'node:net';
import { inspect } from 'node:util';
import { CatalogRequest, GatewayError, resolveRoute } from '../src/contract.js';
import { Secret, startupConfig } from '../src/credentials.js';
import { createHttpsExecutor, HttpsExecutor, HttpsRequester, TmdbUpstream } from '../src/tmdb.js';
import { readPayload } from '../src/upstream.js';
import { createGatewayServer } from '../src/gateway.js';

const TMDB_TOKEN = 'TEST_ONLY_TMDB_READ_TOKEN_0123456789abcdef';
const LOCAL_TOKEN = 'TEST_ONLY_GATEWAY_TOKEN_0123456789abcdef';
const signal = () => new AbortController().signal;
function route(path: string): CatalogRequest {
  const value = resolveRoute(path); if (value.kind === 'health') assert.fail(); return value;
}
const movie = () => ({ id: 7, title: '协议样例', release_date: '2024-01-01', genres: [], poster_path: null });
const secrets = () => ({ tmdbToken: Secret.tmdb(TMDB_TOKEN), accessToken: Secret.gateway(LOCAL_TOKEN) });

for (const path of ['/v1/tmdb/discover/movie','/v1/tmdb/discover/tv','/v1/tmdb/movie/7','/v1/tmdb/tv/7','/v1/tmdb/images/w500/sample.png']) {
  test('LIVE adapter sends only its fixed official request: ' + path, async () => {
    const input = route(path); let calls = 0;
    const adapter = new TmdbUpstream(secrets().tmdbToken, async (url, headers, receivedSignal) => {
      calls++; assert.equal(url.href, input.target); assert.equal(url.protocol, 'https:');
      assert.equal(headers['accept-encoding'], 'identity'); assert.equal(headers['cache-control'], 'no-store');
      assert.equal(receivedSignal, activeSignal);
      if (input.kind === 'image') {
        assert.equal(url.host, 'image.tmdb.org'); assert.equal(headers.authorization, undefined);
        assert.equal(headers.accept, 'image/jpeg,image/png');
      } else {
        assert.equal(url.host, 'api.themoviedb.org'); assert.equal(headers.authorization, 'Bearer ' + TMDB_TOKEN);
        assert.equal(headers.accept, 'application/json');
      }
      assert.equal(JSON.stringify(headers).includes(LOCAL_TOKEN), false);
      return Response.json({});
    });
    const activeSignal = signal(); await adapter.load(input, activeSignal); assert.equal(calls, 1);
  });
}

for (const target of [
  'https://evil.example/3/movie/7?language=zh-CN', 'http://api.themoviedb.org/3/movie/7?language=zh-CN',
  'https://api.themoviedb.org:8443/3/movie/7?language=zh-CN',
  'https://api.themoviedb.org/3/movie/8?language=zh-CN',
  'https://api.themoviedb.org/3/movie/7?language=zh-CN&api_key=TEST_ONLY',
  'https://api.themoviedb.org@evil.example/3/movie/7?language=zh-CN',
]) {
  test('forged internal target is rejected before credentials are attached: ' + target, async () => {
    let calls = 0;
    const adapter = new TmdbUpstream(secrets().tmdbToken, async () => { calls++; return Response.json({}); });
    await assert.rejects(adapter.load({ ...route('/v1/tmdb/movie/7'), target }, signal()), GatewayError);
    assert.equal(calls, 0);
  });
}

test('an already cancelled request never dispatches', async () => {
  let calls = 0; const controller = new AbortController(); controller.abort();
  const adapter = new TmdbUpstream(secrets().tmdbToken, async () => { calls++; return Response.json({}); });
  await assert.rejects(adapter.load(route('/v1/tmdb/movie/7'), controller.signal)); assert.equal(calls, 0);
});

test('secrets and adapter are redacted in JSON, ordinary formatting and inspect', () => {
  const values = secrets(); const adapter = new TmdbUpstream(values.tmdbToken);
  for (const text of [JSON.stringify(values), inspect(values), String(values.tmdbToken), JSON.stringify(adapter), inspect(adapter)]) {
    assert.equal(text.includes(TMDB_TOKEN), false); assert.equal(text.includes(LOCAL_TOKEN), false);
  }
  assert.equal(values.accessToken.matches(LOCAL_TOKEN), true);
  assert.equal(values.accessToken.matches(TMDB_TOKEN), false);
  assert.throws(() => new TmdbUpstream(values.accessToken));
});

test('startup is MOCK by default; LIVE requires distinct, validated credentials', () => {
  assert.deepEqual(startupConfig({}), { mode: 'MOCK', port: 8788 });
  assert.deepEqual(startupConfig({ TMDB_READ_ACCESS_TOKEN: 'not-loaded-in-mock' }), { mode: 'MOCK', port: 8788 });
  const config = startupConfig({ UPSTREAM_MODE: 'LIVE', TMDB_READ_ACCESS_TOKEN: TMDB_TOKEN, YINGJI_GATEWAY_TOKEN: LOCAL_TOKEN });
  assert.equal(config.mode, 'LIVE'); assert.equal(config.port, 8788);
  assert.equal(JSON.stringify(config).includes(TMDB_TOKEN), false);
  assert.equal(JSON.stringify(config).includes(LOCAL_TOKEN), false);
});

for (const [label, patch] of Object.entries({
  missingUpstream: { TMDB_READ_ACCESS_TOKEN: undefined }, missingLocal: { YINGJI_GATEWAY_TOKEN: undefined },
  prefixed: { TMDB_READ_ACCESS_TOKEN: 'Bearer ' + TMDB_TOKEN },
  injection: { TMDB_READ_ACCESS_TOKEN: TMDB_TOKEN + '\r\nInjected: true' },
  short: { TMDB_READ_ACCESS_TOKEN: 'TEST_ONLY_SHORT' },
  oversized: { TMDB_READ_ACCESS_TOKEN: 'x'.repeat(4097) },
  sharedCredential: { YINGJI_GATEWAY_TOKEN: TMDB_TOKEN },
  localInjection: { YINGJI_GATEWAY_TOKEN: LOCAL_TOKEN + '\n' },
  insecureTls: { NODE_TLS_REJECT_UNAUTHORIZED: '0' }, unknownMode: { UPSTREAM_MODE: 'OTHER' },
})) {
  test('startup rejects unsafe configuration without echo: ' + label, () => {
    assert.throws(() => startupConfig({ UPSTREAM_MODE: 'LIVE', TMDB_READ_ACCESS_TOKEN: TMDB_TOKEN,
      YINGJI_GATEWAY_TOKEN: LOCAL_TOKEN, ...patch }), error => {
        assert.ok(error instanceof Error); assert.equal(error.message.includes(TMDB_TOKEN), false);
        assert.equal(error.message.includes(LOCAL_TOKEN), false); return true;
      });
  });
}

function fakeHttps(status: number, body: string, onOptions?: (options: import('node:https').RequestOptions) => void): HttpsRequester {
  return (url, options, callback) => {
    assert.equal(url.host, 'api.themoviedb.org'); onOptions?.(options);
    const fake = new EventEmitter();
    const incoming = new IncomingMessage(new Socket());
    incoming.statusCode = status;
    incoming.headers = { 'content-type': 'application/json', 'content-length': String(Buffer.byteLength(body)),
      'set-cookie': ['should-not-pass=1'], location: 'https://evil.example' };
    Object.assign(fake, { end() { queueMicrotask(() => {
      callback(incoming); if (!incoming.destroyed) { incoming.push(body); incoming.push(null); }
    }); } });
    return fake as ClientRequest;
  };
}

test('raw HTTPS bridge enforces GET, TLS verification, bounded headers and no reusable agent', async () => {
  const active = signal(); let calls = 0;
  const executor = createHttpsExecutor(fakeHttps(200, JSON.stringify(movie()), options => {
    calls++; assert.equal(options.method, 'GET'); assert.equal(options.rejectUnauthorized, true);
    assert.equal(options.minVersion, 'TLSv1.2'); assert.equal(options.maxHeaderSize, 8192);
    assert.equal(options.agent, false); assert.equal(options.signal, active);
  }));
  const result = await readPayload(new TmdbUpstream(secrets().tmdbToken, executor), route('/v1/tmdb/movie/7'), active);
  assert.equal(JSON.parse(new TextDecoder().decode(result.bytes)).id, 7); assert.equal(calls, 1);
});

test('raw HTTPS bridge strips cookies and redirect headers and never follows redirects', async () => {
  let calls = 0;
  const execute = createHttpsExecutor(fakeHttps(302, 'TEST_ONLY_PRIVATE_BODY', () => { calls++; }));
  const response = await execute(new URL('https://api.themoviedb.org/3/movie/7'), {}, signal());
  assert.equal(response.status, 302); assert.equal(response.headers.get('location'), null); assert.equal(response.headers.get('set-cookie'), null);
  await response.body?.cancel(); assert.equal(calls, 1);
  await assert.rejects(readPayload(new TmdbUpstream(secrets().tmdbToken, execute), route('/v1/tmdb/movie/7'), signal()),
    (error: unknown) => error instanceof GatewayError && error.code === 'INVALID_UPSTREAM_RESPONSE');
  assert.equal(calls, 2);
});

test('raw HTTPS errors are masked and rejected requests never expose transport error text', async () => {
  const executor = createHttpsExecutor(() => { throw Error('TEST_ONLY_PRIVATE_TLS_ERROR'); });
  await assert.rejects(executor(new URL('https://api.themoviedb.org/3/movie/7'), {}, signal()), error => {
    assert.ok(error instanceof GatewayError); assert.equal(error.message.includes('TEST_ONLY_PRIVATE'), false); return true;
  });
});

for (const escaped of [false, true]) {
  test('upstream credential echo is blocked even in escaped JSON: ' + escaped, async () => {
    let text = JSON.stringify({ ...movie(), overview: TMDB_TOKEN });
    if (escaped) text = text.replace(TMDB_TOKEN, [...TMDB_TOKEN].map(char => '\\u' + char.charCodeAt(0).toString(16).padStart(4, '0')).join(''));
    const adapter = new TmdbUpstream(secrets().tmdbToken, async () => new Response(text, { headers: { 'content-type': 'application/json' } }));
    await assert.rejects(readPayload(adapter, route('/v1/tmdb/movie/7'), signal()), error => {
      assert.ok(error instanceof GatewayError); assert.equal(error.code, 'INVALID_UPSTREAM_RESPONSE');
      assert.equal(error.message.includes(TMDB_TOKEN), false); return true;
    });
  });
}

async function liveFixture(t: TestContext, executor: HttpsExecutor = async () => Response.json(movie()), timeoutMs = 5000) {
  let calls = 0;
  const adapter = new TmdbUpstream(secrets().tmdbToken, async (...args) => { calls++; return executor(...args); });
  const server = createGatewayServer(adapter, { accessToken: secrets().accessToken, timeoutMs });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise<void>((resolve,reject) => { server.close(error => error ? reject(error) : resolve()); server.closeAllConnections(); }));
  const address = server.address(); if (!address || typeof address === 'string') assert.fail();
  const port = address.port;
  async function get(path: string, token?: string) {
    return new Promise<{ status: number; json: any; headers: import('node:http').IncomingHttpHeaders }>((resolve,reject) => {
      const req = request({ host: '127.0.0.1', port, path,
        headers: token === undefined ? {} : { authorization: 'Bearer ' + token } }, res => {
        const chunks: Buffer[] = []; res.on('data',chunk => chunks.push(Buffer.from(chunk)));
        res.on('end', () => resolve({ status: res.statusCode!, json: JSON.parse(Buffer.concat(chunks).toString()), headers: res.headers }));
      }); req.on('error',reject); req.end();
    });
  }
  return { get, calls: () => calls };
}

test('LIVE HTTP health is local readiness, explicitly not upstream verification', async t => {
  const app = await liveFixture(t);
  const result = await app.get('/health');
  assert.deepEqual(result.json, { status: 'ok', mode: 'LIVE', apiContract: 'catalog-live-1', liveUpstreamEnabled: true, upstreamVerified: false });
  assert.equal(result.headers['x-yingji-upstream'], 'LIVE'); assert.equal(app.calls(), 0);
});

test('LIVE HTTP access requires its separate token and never forwards that token upstream', async t => {
  const app = await liveFixture(t, async (url, headers) => {
    assert.equal(url.host, 'api.themoviedb.org'); assert.equal(headers.authorization, 'Bearer ' + TMDB_TOKEN);
    assert.equal(JSON.stringify(headers).includes(LOCAL_TOKEN), false); return Response.json(movie());
  });
  for (const token of [undefined, 'TEST_ONLY_WRONG_TOKEN', TMDB_TOKEN]) {
    const denied = await app.get('/v1/tmdb/movie/7', token);
    assert.equal(denied.status, 401); assert.equal(denied.json.error.code, 'UNAUTHORIZED');
    assert.equal(denied.headers['www-authenticate'], 'Bearer');
  }
  assert.equal(app.calls(), 0);
  const accepted = await app.get('/v1/tmdb/movie/7', LOCAL_TOKEN);
  assert.equal(accepted.status, 200); assert.equal(accepted.json.id, 7);
  assert.equal(accepted.headers['x-yingji-api-contract'], 'catalog-live-1');
  assert.equal(accepted.headers['cache-control'], 'no-store'); assert.equal(app.calls(), 1);
});

test('LIVE upstream authentication errors remain server-side errors', async t => {
  const app = await liveFixture(t, async () => new Response('TEST_ONLY_PROVIDER_BODY', { status: 401 }));
  const result = await app.get('/v1/tmdb/movie/7', LOCAL_TOKEN);
  assert.equal(result.status, 502); assert.equal(result.json.error.code, 'UPSTREAM_AUTH'); assert.equal(app.calls(), 1);
});

test('LIVE connection deadline aborts transport and preserves timeout classification', async t => {
  let aborted = false;
  const app = await liveFixture(t, async (_url,_headers,active) => new Promise<Response>((_,reject) => {
    active.addEventListener('abort', () => { aborted = true; reject(active.reason); }, { once: true });
  }), 30);
  const result = await app.get('/v1/tmdb/movie/7', LOCAL_TOKEN);
  assert.equal(result.status, 504); assert.equal(result.json.error.code, 'UPSTREAM_TIMEOUT'); assert.equal(aborted, true);
});

test('raw HTTPS stream abort after headers destroys the socket without double completion', async () => {
  let incoming!: IncomingMessage;
  const executor = createHttpsExecutor((_url,_options,callback) => {
    const fake = new EventEmitter();
    incoming = new IncomingMessage(new Socket()); incoming.statusCode = 200;
    incoming.headers = { 'content-type': 'application/json' };
    Object.assign(fake, { end() { queueMicrotask(() => callback(incoming)); } });
    return fake as ClientRequest;
  });
  const controller = new AbortController();
  const response = await executor(new URL('https://api.themoviedb.org/3/movie/7'), {}, controller.signal);
  const reason = new GatewayError(504, 'UPSTREAM_TIMEOUT', 'deadline');
  controller.abort(reason);
  await assert.rejects(response.arrayBuffer(), error => error === reason);
  assert.equal(incoming.destroyed, true);
});
