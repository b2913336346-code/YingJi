export const CONTRACT = 'catalog-mock-1';
export const JSON_LIMIT = 128 * 1024;
export const IMAGE_LIMIT = 2 * 1024 * 1024;
export const POSTER_NAME = /^[A-Za-z0-9_-]{1,120}\.(?:jpg|jpeg|png)$/;
export type MediaKind = 'movie' | 'tv';
export type CatalogRequest =
  | { kind: 'discover'; media: MediaKind; target: string }
  | { kind: 'detail'; media: MediaKind; id: number; target: string }
  | { kind: 'image'; filename: string; target: string };
export type Route = CatalogRequest | { kind: 'health' };

export class GatewayError extends Error {
  constructor(public readonly status: number, public readonly code: string,
    message: string, public readonly retryAfter?: number) { super(message); }
}
const invalidRequest = (): never => { throw new GatewayError(400, 'INVALID_REQUEST', '请求参数无效'); };
export const invalidUpstream = (): never => {
  throw new GatewayError(502, 'INVALID_UPSTREAM_RESPONSE', '资料服务返回了无效内容');
};

/** Raw origin-form paths are checked before URL normalization. No caller-controlled host. */
export function resolveRoute(raw: string): Route {
  if (raw.length > 1024 || !raw.startsWith('/') || raw.startsWith('//') || /[%\\#\s]/u.test(raw)) invalidRequest();
  const [path, query, extra] = raw.split('?');
  if (extra !== undefined || query === '') invalidRequest();
  const params = new URLSearchParams(query);
  const expect = (allowed: Record<string, string>) => {
    const seen = new Set<string>();
    for (const [key, value] of params) {
      if (seen.has(key) || !Object.hasOwn(allowed, key) || allowed[key] !== value) invalidRequest();
      seen.add(key);
    }
  };
  if (path === '/health') { expect({}); return { kind: 'health' }; }
  const discovery = /^\/v1\/tmdb\/discover\/(movie|tv)$/.exec(path ?? '');
  if (discovery) {
    expect({ language: 'zh-CN', page: '1', include_adult: 'false', sort_by: 'popularity.desc' });
    const media = discovery[1] as MediaKind;
    return { kind: 'discover', media,
      target: 'https://api.themoviedb.org/3/discover/' + media + '?language=zh-CN&include_adult=false&page=1&sort_by=popularity.desc' };
  }
  const detail = /^\/v1\/tmdb\/(movie|tv)\/([1-9][0-9]{0,9})$/.exec(path ?? '');
  if (detail) {
    expect({ language: 'zh-CN' });
    const media = detail[1] as MediaKind;
    const id = Number(detail[2]);
    if (id > 2147483647) invalidRequest();
    return { kind: 'detail', media, id,
      target: 'https://api.themoviedb.org/3/' + media + '/' + id + '?language=zh-CN' };
  }
  const image = /^\/v1\/tmdb\/images\/w500\/([^/]+)$/.exec(path ?? '');
  if (image) {
    expect({});
    const filename = image[1]!;
    if (!POSTER_NAME.test(filename)) invalidRequest();
    return { kind: 'image', filename, target: 'https://image.tmdb.org/t/p/w500/' + filename };
  }
  throw new GatewayError(404, 'NOT_FOUND', '接口不存在');
}

type JsonObject = Record<string, unknown>;
function object(value: unknown): JsonObject {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) invalidUpstream();
  return value as JsonObject;
}
function integer(value: unknown, min = 1, max = 2147483647): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) invalidUpstream();
  return value as number;
}
function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > max || /[\x00-\x1f\x7f]/u.test(value)) invalidUpstream();
  return (value as string).trim();
}
function adult(value: unknown): boolean {
  if (value === undefined || value === null) return false;
  if (typeof value !== 'boolean') invalidUpstream();
  return value as boolean;
}
function date(value: unknown): string | null {
  if (value === undefined || value === null || value === '') return null;
  if (typeof value !== 'string' || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value)) invalidUpstream();
  const input = value as string;
  const year = Number(input.slice(0, 4));
  const parsed = new Date(input + 'T00:00:00Z');
  if (year < 1800 || year > 3000 || !Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== input) invalidUpstream();
  return input;
}

/** Only fields consumed by Android are returned; upstream extras and credentials never pass through. */
export function normalizeJson(value: unknown, request: Exclude<CatalogRequest, { kind: 'image' }>): JsonObject {
  const row = object(value);
  if (request.kind === 'discover') {
    if (row.page !== 1 || !Array.isArray(row.results) || row.results.length > 20) invalidUpstream();
    const seen = new Set<number>();
    const results = (row.results as unknown[]).flatMap(value => {
      const item = object(value);
      const id = integer(item.id);
      if (adult(item.adult) || seen.has(id)) return [];
      seen.add(id);
      return [{ id, adult: false }];
    });
    return { page: 1, results };
  }
  if (integer(row.id) !== request.id) invalidUpstream();
  if (adult(row.adult)) throw new GatewayError(404, 'NOT_FOUND', '作品不可用');
  const titleKey = request.media === 'movie' ? 'title' : 'name';
  const dateKey = request.media === 'movie' ? 'release_date' : 'first_air_date';
  const genres = row.genres ?? [];
  if (!Array.isArray(genres) || genres.length > 32) invalidUpstream();
  let overview: string | null = null;
  if (row.overview !== undefined && row.overview !== null) {
    if (typeof row.overview !== 'string' || row.overview.length > 4000 || /[\x00-\x08\x0b\x0c\x0e-\x1f]/u.test(row.overview)) invalidUpstream();
    overview = (row.overview as string).trim() || null;
  }
  let poster: string | null = null;
  if (row.poster_path !== undefined && row.poster_path !== null) {
    if (typeof row.poster_path !== 'string' || !row.poster_path.startsWith('/') || !POSTER_NAME.test(row.poster_path.slice(1))) invalidUpstream();
    poster = row.poster_path as string;
  }
  return { id: request.id, adult: false, [titleKey]: text(row[titleKey], 500),
    [dateKey]: date(row[dateKey]),
    ...(request.media === 'movie' ? { runtime: row.runtime == null ? null : integer(row.runtime, 0, 10000) } : {}),
    genres: (genres as unknown[]).map(value => { const genre = object(value); return { id: integer(genre.id), name: text(genre.name, 200) }; }),
    overview, poster_path: poster };
}
