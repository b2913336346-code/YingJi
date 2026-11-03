import { CatalogRequest } from './contract.js';
import { CatalogUpstream } from './upstream.js';

// Synthetic protocol samples only. IDs and titles must never be treated as real works.
const movies = [1, 2, 3].map(n => ({ id: 2000000000 + n, adult: false,
  title: '影迹协议样例电影 ' + n, release_date: '2024-01-0' + n, runtime: 100 + n,
  genres: [{ id: 18, name: '剧情' }], overview: '仅用于本地接口测试，不是真实影视资料。',
  poster_path: '/yingji-mock-movie.png' }));
const shows = [1, 2, 3].map(n => ({ id: 2000000010 + n, adult: false,
  name: '影迹协议样例剧集 ' + n, first_air_date: '2024-02-0' + n,
  genres: [{ id: 18, name: '剧情' }], overview: '仅用于本地接口测试，不是真实影视资料。',
  poster_path: n === 3 ? null : '/yingji-mock-tv.png' }));
// A valid 1x1 PNG; no remote media or user image is read.
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=', 'base64');

export class MockUpstream implements CatalogUpstream {
  readonly mode = 'MOCK' as const;
  async load(request: CatalogRequest, signal: AbortSignal): Promise<Response> {
    signal.throwIfAborted();
    if (request.kind === 'image') {
      if (!['yingji-mock-movie.png', 'yingji-mock-tv.png'].includes(request.filename)) return new Response(null, { status: 404 });
      return new Response(png, { headers: { 'content-type': 'image/png' } });
    }
    const rows = request.media === 'movie' ? movies : shows;
    if (request.kind === 'discover') return Response.json({ page: 1, results: rows.map(row => ({ id: row.id, adult: row.adult })) });
    const row = rows.find(row => row.id === request.id);
    return row ? Response.json(row) : new Response(null, { status: 404 });
  }
}
