import test, { TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:http';
import { spawnSync } from 'node:child_process';
import { inflateSync } from 'node:zlib';
import { CatalogRequest, IMAGE_LIMIT, JSON_LIMIT } from '../src/contract.js';
import { createGatewayServer, GatewayOptions } from '../src/gateway.js';
import { MockUpstream } from '../src/mock.js';
import { CatalogUpstream } from '../src/upstream.js';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}
async function fixture(t: TestContext, load?: CatalogUpstream['load'], options: GatewayOptions = {}) {
  const mock = new MockUpstream();
  let calls = 0;
  const seen: CatalogRequest[] = [];
  const upstream: CatalogUpstream = { mode: 'MOCK', load: async (route, signal) => {
    calls++; seen.push(route); return load ? load(route, signal) : mock.load(route, signal);
  } };
  const server = createGatewayServer(upstream, options);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise<void>((resolve, reject) => {
    server.close(error => error ? reject(error) : resolve()); server.closeAllConnections();
  }));
  const address = server.address();
  if (!address || typeof address === 'string') assert.fail();
  const port = address.port;
  async function get(path: string, method = 'GET', headers: Record<string, string> = {}) {
    return new Promise<{ status: number; headers: import('node:http').IncomingHttpHeaders; bytes: Buffer; json: any }>((resolve, reject) => {
      const req = request({ host: '127.0.0.1', port, path, method, headers }, res => {
        const chunks: Buffer[] = [];
        res.on('data', chunk => chunks.push(Buffer.from(chunk)));
        res.on('end', () => {
          const bytes = Buffer.concat(chunks);
          let json: unknown;
          if (res.headers['content-type']?.startsWith('application/json')) json = JSON.parse(bytes.toString());
          resolve({ status: res.statusCode!, headers: res.headers, bytes, json });
        });
        res.on('error', reject);
      });
      req.on('error', reject); req.end();
    });
  }
  return { get, port, seen, calls: () => calls };
}

test('health is explicit MOCK and never contacts upstream', async t => {
  const app = await fixture(t);
  const result = await app.get('/health');
  assert.equal(result.status, 200);
  assert.deepEqual(result.json, { status: 'ok', mode: 'MOCK', apiContract: 'catalog-mock-1', liveUpstreamEnabled: false });
  assert.equal(result.headers['cache-control'], 'no-store');
  assert.equal(result.headers['x-yingji-upstream'], 'MOCK');
  assert.equal(app.calls(), 0);
});

test('HTTP discovery -> six typed details -> poster supplies the Android field contract', async t => {
  const app = await fixture(t);
  const identities = new Set<string>();
  for (const media of ['movie', 'tv']) {
    const discovery = await app.get('/v1/tmdb/discover/' + media);
    assert.equal(discovery.status, 200); assert.equal(discovery.json.page, 1);
    assert.equal(discovery.json.results.length, 3);
    for (const item of discovery.json.results) {
      assert.equal(item.adult, false);
      const result = await app.get('/v1/tmdb/' + media + '/' + item.id);
      assert.equal(result.status, 200); assert.equal(result.json.id, item.id);
      assert.match(result.json[media === 'movie' ? 'title' : 'name'], /^影迹协议样例/);
      assert.equal(result.json.adult, false); assert.deepEqual(result.json.genres, [{ id: 18, name: '剧情' }]);
      assert.match(result.json[media === 'movie' ? 'release_date' : 'first_air_date'], /^2024-/);
      assert.equal(result.headers['cache-control'], 'no-store');
      assert.ok(result.bytes.length <= JSON_LIMIT); identities.add(media + ':' + item.id);
    }
  }
  assert.equal(identities.size, 6); assert.equal(app.calls(), 8);
  const image = await app.get('/v1/tmdb/images/w500/yingji-mock-movie.png');
  assert.equal(image.status, 200); assert.equal(image.headers['content-type'], 'image/png');
  assert.deepEqual([...image.bytes.subarray(0,8)], [137,80,78,71,13,10,26,10]);
  assert.equal(image.bytes.readUInt32BE(16), 1); assert.equal(image.bytes.readUInt32BE(20), 1);
  assert.equal(image.headers['cache-control'], 'no-store');
  const chunkTypes: string[] = [];
  for (let pos = 8; pos < image.bytes.length;) {
    const length = image.bytes.readUInt32BE(pos);
    const type = image.bytes.toString('ascii', pos + 4, pos + 8);
    let crc = 0xffffffff;
    for (const byte of image.bytes.subarray(pos + 4, pos + 8 + length)) {
      crc ^= byte;
      for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    assert.equal((crc ^ 0xffffffff) >>> 0, image.bytes.readUInt32BE(pos + 8 + length), type + ' CRC');
    if (type === 'IDAT') assert.equal(inflateSync(image.bytes.subarray(pos + 8, pos + 8 + length)).length, 3);
    chunkTypes.push(type); pos += length + 12;
  }
  assert.deepEqual(chunkTypes, ['IHDR', 'IDAT', 'IEND']);
  const missingPoster = await app.get('/v1/tmdb/tv/2000000013');
  assert.equal(missingPoster.json.poster_path, null);
});

test('HTTP unknown work and image return bounded errors', async t => {
  const app = await fixture(t);
  for (const path of ['/v1/tmdb/movie/1','/v1/tmdb/images/w500/missing.png']) {
    const result = await app.get(path); assert.equal(result.status, 404); assert.equal(result.json.error.code, 'NOT_FOUND');
  }
});

test('unsafe queries, traversal and arbitrary URLs never reach upstream over HTTP', async t => {
  const app = await fixture(t);
  for (const path of ['/v1/tmdb/movie/1?url=https://evil.example','/v1/tmdb/discover/movie?page=2',
    '/v1/tmdb/movie/1?language=zh-CN&language=zh-CN','/v1/tmdb/images/w500/%2e%2e/a.png',
    '//evil.example/a', '/v1/tmdb/movie/0','/v1/tmdb/search/movie']) {
    assert.ok([400,404].includes((await app.get(path)).status));
  }
  assert.equal(app.calls(), 0);
});

test('write methods and GET bodies are rejected without upstream calls', async t => {
  const app = await fixture(t);
  const post = await app.get('/v1/tmdb/movie/1', 'POST');
  assert.equal(post.status, 405); assert.equal(post.headers.allow, 'GET');
  const body = await app.get('/v1/tmdb/movie/1', 'GET', { 'content-length': '1' });
  assert.equal(body.status, 400); assert.equal(app.calls(), 0);
});

for (const [status, expectedStatus, expectedCode] of [
  [401,502,'UPSTREAM_AUTH'], [403,502,'UPSTREAM_AUTH'], [404,404,'NOT_FOUND'],
  [429,503,'UPSTREAM_RATE_LIMIT'], [500,502,'UPSTREAM_UNAVAILABLE'], [503,502,'UPSTREAM_UNAVAILABLE'],
  [302,502,'INVALID_UPSTREAM_RESPONSE'],
] as const) {
  test('upstream ' + status + ' maps safely without retries or body/header leakage', async t => {
    const app = await fixture(t, async () => new Response('TEST_ONLY_SECRET', { status,
      headers: { 'retry-after': '17', 'set-cookie': 'secret=TEST_ONLY', location: 'https://evil.example' } }));
    const result = await app.get('/v1/tmdb/movie/1', 'GET', { authorization: 'Bearer TEST_ONLY_CLIENT_SECRET' });
    assert.equal(result.status, expectedStatus); assert.equal(result.json.error.code, expectedCode);
    assert.equal(result.headers['set-cookie'], undefined); assert.equal(result.headers.location, undefined);
    assert.equal(result.bytes.toString().includes('TEST_ONLY'), false); assert.equal(app.calls(), 1);
    if (status === 429) assert.equal(result.headers['retry-after'], '17');
  });
}

test('network exceptions return generic errors and never leak thrown text', async t => {
  const app = await fixture(t, async () => { throw Error('TEST_ONLY_PRIVATE_KEY'); });
  const result = await app.get('/v1/tmdb/movie/1');
  assert.equal(result.status, 502); assert.equal(result.json.error.code, 'UPSTREAM_UNAVAILABLE');
  assert.equal(result.bytes.toString().includes('TEST_ONLY_PRIVATE_KEY'), false); assert.equal(app.calls(), 1);
});

for (const [label, response] of [
  ['html', () => new Response('<html>oops</html>', { headers: { 'content-type': 'text/html' } })],
  ['bad json', () => new Response('{', { headers: { 'content-type': 'application/json' } })],
  ['bad utf8', () => new Response(new Uint8Array([255]), { headers: { 'content-type': 'application/json' } })],
  ['wrong identity', () => Response.json({ id: 2, title: '错误作品' })],
  ['large declared JSON', () => new Response('{}', { headers: { 'content-type': 'application/json', 'content-length': String(JSON_LIMIT + 1) } })],
  ['large actual JSON', () => new Response(' '.repeat(JSON_LIMIT + 1), { headers: { 'content-type': 'application/json' } })],
  ['compressed body', () => new Response('{}', { headers: { 'content-type': 'application/json', 'content-encoding': 'gzip' } })],
  ['wrong length', () => new Response('{}', { headers: { 'content-type': 'application/json', 'content-length': '1' } })],
] as const) {
  test('invalid upstream content is rejected: ' + label, async t => {
    const app = await fixture(t, async () => response());
    const result = await app.get('/v1/tmdb/movie/1');
    assert.equal(result.status, 502); assert.equal(result.json.error.code, 'INVALID_UPSTREAM_RESPONSE');
  });
}

for (const [label, response] of [
  ['wrong MIME', () => new Response('image', { headers: { 'content-type': 'image/svg+xml' } })],
  ['fake PNG', () => new Response('not a PNG', { headers: { 'content-type': 'image/png' } })],
  ['oversize image', () => new Response(new Uint8Array(IMAGE_LIMIT + 1), { headers: { 'content-type': 'image/png' } })],
] as const) {
  test('unsafe image rejected: ' + label, async t => {
    const app = await fixture(t, async () => response());
    const result = await app.get('/v1/tmdb/images/w500/sample.png');
    assert.equal(result.status, 502); assert.equal(result.json.error.code, 'INVALID_UPSTREAM_RESPONSE');
  });
}

test('timeout aborts a stalled response body and a later request can succeed', async t => {
  let cancelled = false; let first = true;
  const app = await fixture(t, async (route, signal) => {
    if (!first) return new MockUpstream().load(route, signal);
    first = false;
    return new Response(new ReadableStream({ cancel() { cancelled = true; } }), { headers: { 'content-type': 'application/json' } });
  }, { timeoutMs: 30, maxConcurrent: 1 });
  const timedOut = await app.get('/v1/tmdb/movie/2000000001');
  assert.equal(timedOut.status, 504); assert.equal(timedOut.json.error.code, 'UPSTREAM_TIMEOUT');
  assert.equal(cancelled, true);
  assert.equal((await app.get('/v1/tmdb/movie/2000000001')).status, 200);
});

test('rapid concurrent requests are rejected rather than queued', async t => {
  const entered = deferred<void>(); const release = deferred<void>();
  const app = await fixture(t, async (route, signal) => {
    entered.resolve(); await release.promise; return new MockUpstream().load(route, signal);
  }, { maxConcurrent: 1 });
  const first = app.get('/v1/tmdb/movie/2000000001');
  await entered.promise;
  try {
    const second = await app.get('/v1/tmdb/movie/2000000001');
    assert.equal(second.status, 503); assert.equal(second.json.error.code, 'BUSY'); assert.equal(app.calls(), 1);
  } finally { release.resolve(); }
  assert.equal((await first).status, 200);
});

test('quota boundaries recover with a new minute and health remains available', async t => {
  let now = 1000;
  const app = await fixture(t, undefined, { requestsPerMinute: 1, now: () => now });
  assert.equal((await app.get('/v1/tmdb/discover/movie')).status, 200);
  now = 60999;
  const limited = await app.get('/v1/tmdb/discover/movie');
  assert.equal(limited.status, 429); assert.equal(limited.headers['retry-after'], '1'); assert.equal(app.calls(), 1);
  assert.equal((await app.get('/health')).status, 200);
  now = 61000;
  assert.equal((await app.get('/v1/tmdb/discover/movie')).status, 200); assert.equal(app.calls(), 2);
});

test('client disconnect aborts upstream and releases admission for the next request', async t => {
  const entered = deferred<void>(); const aborted = deferred<void>(); let first = true;
  const app = await fixture(t, async (route, signal) => {
    if (!first) return new MockUpstream().load(route, signal);
    first = false; entered.resolve();
    return new Promise<Response>((_, reject) => signal.addEventListener('abort', () => {
      aborted.resolve(); reject(signal.reason);
    }, { once: true }));
  }, { maxConcurrent: 1 });
  const client = request({ host: '127.0.0.1', port: app.port, path: '/v1/tmdb/movie/2000000001' });
  client.on('error', () => {}); client.end();
  await entered.promise; client.destroy(); await aborted.promise;
  assert.equal((await app.get('/v1/tmdb/movie/2000000001')).status, 200);
});

test('entry point rejects incomplete LIVE configuration and invalid ports before listening', () => {
  for (const env of [{ UPSTREAM_MODE: 'LIVE' }, { PORT: '0' }, { PORT: '65536' }, { PORT: 'TEST_ONLY_SECRET' }]) {
    const result = spawnSync(process.execPath, ['dist/src/main.js'], { cwd: process.cwd(),
      env: { ...process.env, TMDB_READ_ACCESS_TOKEN: '', YINGJI_GATEWAY_TOKEN: '', UPSTREAM_MODE: 'MOCK', PORT: '8788', ...env }, encoding: 'utf8' });
    assert.equal(result.status, 1); assert.equal(result.stdout, ''); assert.equal(result.stderr.includes('TEST_ONLY_SECRET'), false);
  }
  assert.throws(() => createGatewayServer({ mode: 'LIVE' } as unknown as CatalogUpstream));
  assert.throws(() => createGatewayServer(new MockUpstream(), { maxConcurrent: 0 }));
});

test('adult details are unavailable and an empty discovery remains empty', async t => {
  const app = await fixture(t, async route => route.kind === 'discover'
    ? Response.json({ page: 1, results: [] }) : Response.json({ id: 1, adult: true }));
  const empty = await app.get('/v1/tmdb/discover/movie');
  assert.equal(empty.status, 200); assert.deepEqual(empty.json, { page: 1, results: [] });
  const adult = await app.get('/v1/tmdb/movie/1');
  assert.equal(adult.status, 404); assert.equal(adult.json.error.code, 'NOT_FOUND');
});

test('invalid headers cancel an unread upstream body', async t => {
  let cancelled = false;
  const app = await fixture(t, async () => new Response(new ReadableStream({ cancel() { cancelled = true; } }),
    { headers: { 'content-type': 'application/json', 'content-length': String(JSON_LIMIT + 1) } }));
  assert.equal((await app.get('/v1/tmdb/movie/1')).status, 502);
  assert.equal(cancelled, true);
});

test('timeout also covers upstream connection establishment', async t => {
  let cancelled = false;
  const app = await fixture(t, async (_route, signal) => new Promise<Response>((_, reject) => {
    signal.addEventListener('abort', () => { cancelled = true; reject(signal.reason); }, { once: true });
  }), { timeoutMs: 30 });
  const result = await app.get('/v1/tmdb/movie/1');
  assert.equal(result.status, 504); assert.equal(result.json.error.code, 'UPSTREAM_TIMEOUT');
  assert.equal(cancelled, true); assert.equal(app.calls(), 1);
});

test('repeated reads invoke upstream again: no implicit metadata or image cache', async t => {
  const app = await fixture(t);
  for (let n = 0; n < 2; n++) {
    assert.equal((await app.get('/v1/tmdb/movie/2000000001')).status, 200);
    assert.equal((await app.get('/v1/tmdb/images/w500/yingji-mock-movie.png')).status, 200);
  }
  assert.equal(app.calls(), 4);
});
