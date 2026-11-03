import test from 'node:test';
import assert from 'node:assert/strict';
import { GatewayError, normalizeJson, resolveRoute } from '../src/contract.js';

for (const media of ['movie', 'tv'] as const) {
  test(media + ' discovery is fixed to Chinese page one and excludes adult content', () => {
    const route = resolveRoute('/v1/tmdb/discover/' + media + '?language=zh-CN&page=1&include_adult=false&sort_by=popularity.desc');
    assert.equal(route.kind, 'discover');
    if (route.kind !== 'discover') assert.fail();
    assert.equal(route.target, 'https://api.themoviedb.org/3/discover/' + media + '?language=zh-CN&include_adult=false&page=1&sort_by=popularity.desc');
    assert.equal(route.media, media);
  });
  test(media + ' detail keeps typed ID including the Android Int boundary', () => {
    const route = resolveRoute('/v1/tmdb/' + media + '/2147483647');
    assert.deepEqual(route, { kind: 'detail', media, id: 2147483647,
      target: 'https://api.themoviedb.org/3/' + media + '/2147483647?language=zh-CN' });
  });
}

test('poster is a fixed w500 provider path, never an arbitrary URL', () => {
  assert.deepEqual(resolveRoute('/v1/tmdb/images/w500/abc-_01.png'), {
    kind: 'image', filename: 'abc-_01.png', target: 'https://image.tmdb.org/t/p/w500/abc-_01.png' });
});

for (const path of [
  '/v1/tmdb/discover/movie?page=2', '/v1/tmdb/discover/movie?include_adult=true',
  '/v1/tmdb/discover/movie?sort_by=vote_average.desc', '/v1/tmdb/movie/1?language=en-US',
  '/v1/tmdb/movie/1?language=zh-CN&language=zh-CN', '/v1/tmdb/movie/1?url=https://evil.example',
  '/v1/tmdb/movie/1?api_key=TEST_ONLY_SECRET', '/health?token=TEST_ONLY_SECRET',
  '/v1/tmdb/images/w500/a.png?x=1', '/v1/tmdb/images/w500/a.svg',
  '/v1/tmdb/images/w500/a..png', '/v1/tmdb/images/w500/../a.png',
  '/v1/tmdb/images/w500/%2e%2e/a.png', '/v1/tmdb/images/w500/a%2epng',
  '/v1/tmdb/movie/0', '/v1/tmdb/movie/-1', '/v1/tmdb/movie/01', '/v1/tmdb/movie/2147483648',
  '/v1/tmdb/movie/1#fragment', '/v1/tmdb/movie/1?', '/v1/tmdb/movie/1??x=1',
  '//evil.example/v1/tmdb/movie/1', 'https://evil.example/v1/tmdb/movie/1',
  '/v1/tmdb/movie/1' + String.fromCharCode(92) + 'extra',
  '/v1/tmdb/search/movie', '/v1/tmdb/images/original/a.png',
]) {
  test('reject unsupported or unsafe path: ' + path, () => {
    assert.throws(() => resolveRoute(path), (error: unknown) => error instanceof GatewayError && [400,404].includes(error.status));
  });
}

test('discovery keeps compatible page/results fields, deduplicates and filters adults', () => {
  const route = resolveRoute('/v1/tmdb/discover/movie');
  if (route.kind !== 'discover') assert.fail();
  assert.deepEqual(normalizeJson({ page: 1, results: [ { id: 5 }, { id: 5 }, { id: 6, adult: true } ], secret: 'TEST_ONLY' }, route),
    { page: 1, results: [{ id: 5, adult: false }] });
  assert.deepEqual(normalizeJson({ page: 1, results: [] }, route), { page: 1, results: [] });
});

const detail = () => ({ id: 7, adult: false, title: '电影样例', release_date: '2024-02-29', runtime: 90,
  genres: [{ id: 18, name: '剧情' }], overview: '说明\n第二行', poster_path: '/sample.png' });
test('detail preserves required Android fields and strips unsupported upstream fields', () => {
  const route = resolveRoute('/v1/tmdb/movie/7');
  if (route.kind !== 'detail') assert.fail();
  assert.deepEqual(normalizeJson({ ...detail(), api_key: 'TEST_ONLY_SECRET', privateInfo: {} }, route), detail());
  const missing = normalizeJson({ id: 7, title: '空字段样例', release_date: '' }, route);
  assert.equal(missing.overview, null); assert.equal(missing.poster_path, null);
  assert.equal(missing.runtime, null); assert.deepEqual(missing.genres, []);
});

for (const [label, patch] of Object.entries({
  identity: { id: 8 }, title: { title: '' }, titleControl: { title: 'x\u0000' },
  invalidDate: { release_date: '2023-02-29' }, dateBoundary: { release_date: '1799-12-31' },
  hugeRuntime: { runtime: 10001 }, fractionalRuntime: { runtime: 1.5 },
  genres: { genres: [{ id: 0, name: 'x' }] }, overview: { overview: 'x'.repeat(4001) },
  poster: { poster_path: 'https://evil.example/a.png' },
})) {
  test('reject malformed detail field: ' + label, () => {
    const route = resolveRoute('/v1/tmdb/movie/7');
    if (route.kind !== 'detail') assert.fail();
    assert.throws(() => normalizeJson({ ...detail(), ...patch }, route), GatewayError);
  });
}

for (const [label, value] of Object.entries({
  wrongPage: { page: 2, results: [] },
  tooMany: { page: 1, results: Array.from({ length: 21 }, (_, i) => ({ id: i + 1 })) },
  invalidId: { page: 1, results: [{ id: 2147483648 }] },
  invalidAdult: { page: 1, results: [{ id: 1, adult: 'false' }] },
})) {
  test('discovery rejects invalid upstream bounds: ' + label, () => {
    const route = resolveRoute('/v1/tmdb/discover/movie');
    if (route.kind !== 'discover') assert.fail();
    assert.throws(() => normalizeJson(value, route), GatewayError);
  });
}

test('TV uses name and first_air_date and does not copy movie fields', () => {
  const route = resolveRoute('/v1/tmdb/tv/7');
  if (route.kind !== 'detail') assert.fail();
  const result = normalizeJson({ id: 7, name: '剧集样例', first_air_date: '2024-01-01', runtime: 9000, title: '不应采用' }, route);
  assert.equal(result.name, '剧集样例'); assert.equal(result.first_air_date, '2024-01-01');
  assert.equal('title' in result, false); assert.equal('runtime' in result, false);
});
