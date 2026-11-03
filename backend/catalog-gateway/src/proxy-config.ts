export interface LocalHttpProxy { readonly url: string }
const invalidProxy = () => new Error('TMDB_HTTP_PROXY 只允许 http://127.0.0.1:端口，不含认证、路径、查询或片段');
export function parseLocalHttpProxy(value: unknown): LocalHttpProxy | undefined {
  if (value === undefined || value === '') return undefined;
  if (typeof value !== 'string') throw invalidProxy();
  const match = /^http:\/\/127\.0\.0\.1:([1-9][0-9]{0,4})\/?$/.exec(value);
  if (!match || Number(match[1]) > 65535) throw invalidProxy();
  return Object.freeze({ url: 'http://127.0.0.1:' + match[1] });
}
