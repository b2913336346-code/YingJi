import { request } from 'node:http';
import { pathToFileURL } from 'node:url';
import { IMAGE_LIMIT, JSON_LIMIT, POSTER_NAME, resolveRoute } from '../src/contract.js';
import { Secret, startupConfig } from '../src/credentials.js';
import { readSecret, selectPrompt } from './prompt.js';

export type LocalResponse = { status: number; headers: Record<string, string | undefined>; bytes: Uint8Array };
export type LocalReader = (port: number, path: string, authorization?: string) => Promise<LocalResponse>;
class VerificationError extends Error {}
const failed = (detail = '') => new VerificationError('本机只读联调未通过' + detail + '；不输出响应正文或凭据');

/** A bounded loopback-only client. It never follows redirects or accepts a remote host. */
export const readLocal: LocalReader = (port,path,authorization) => new Promise((resolve,reject) => {
  if (!Number.isInteger(port) || port < 1024 || port > 65535) { reject(failed('：端口无效')); return; }
  let route;
  try { route = resolveRoute(path); } catch { reject(failed('：路径无效')); return; }
  const limit = route.kind === 'image' ? IMAGE_LIMIT : JSON_LIMIT;
  const active = AbortSignal.timeout(15000);
  const req = request({ host: '127.0.0.1', port, path, method: 'GET', signal: active,
    agent: false, maxHeaderSize: 8192, headers: authorization === undefined ? {} : { authorization } }, response => {
    const chunks: Buffer[] = []; let size = 0;
    response.on('error', () => reject(failed('：响应中断')));
    response.on('data', (chunk: Buffer) => {
      size += chunk.byteLength;
      if (size > limit) { response.destroy(); reject(failed('：响应过大')); return; }
      chunks.push(chunk);
    });
    response.on('end', () => {
      const headers: Record<string, string | undefined> = {};
      for (const name of ['content-type','cache-control','x-yingji-upstream','x-yingji-api-contract']) {
        const value = response.headers[name];
        if (typeof value === 'string') headers[name] = value;
      }
      resolve({ status: response.statusCode ?? 0, headers, bytes: Buffer.concat(chunks) });
    });
  });
  req.once('error', () => reject(failed('：无法连接或请求超时')));
  req.end();
});

export async function verifyLive(port: number, token: Secret, read: LocalReader = readLocal) {
  if (token.kind !== 'gateway') throw failed('：需要独立网关口令');
  const checked = async (path: string, json: boolean, authenticated = true) => {
    const response = await read(port,path,authenticated ? token.use(value => 'Bearer ' + value) : undefined);
    if (response.status !== 200) throw failed('：HTTP ' + response.status);
    if (response.headers['x-yingji-upstream'] !== 'LIVE' || response.headers['x-yingji-api-contract'] !== 'catalog-live-1')
      throw failed('：需要 LIVE 响应标记');
    const limit = json ? JSON_LIMIT : IMAGE_LIMIT;
    if (response.bytes.byteLength < 1 || response.bytes.byteLength > limit) throw failed('：响应长度无效');
    return response;
  };
  const asJson = async (path: string, authenticated = true): Promise<Record<string, unknown>> => {
    const response = await checked(path,true,authenticated);
    if (response.headers['content-type']?.split(';')[0] !== 'application/json') throw failed('：JSON 类型无效');
    try {
      const value: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(response.bytes));
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw failed();
      return value as Record<string, unknown>;
    } catch { throw failed('：JSON 格式无效'); }
  };
  const health = await asJson('/health',false);
  if (health.mode !== 'LIVE' || health.liveUpstreamEnabled !== true) throw failed('：真实模式未启用');
  let poster: string | undefined;
  for (const media of ['movie','tv']) {
    const discovery = await asJson('/v1/tmdb/discover/' + media);
    if (discovery.page !== 1 || !Array.isArray(discovery.results) || discovery.results.length < 1 || discovery.results.length > 20)
      throw failed('：没有可验证的候选，详情与海报未验证');
    const row = discovery.results[0] as Record<string, unknown> | undefined;
    const id = row?.id;
    if (typeof id !== 'number' || !Number.isInteger(id) || id < 1 || id > 2147483647) throw failed('：候选 ID 无效');
    const detail = await asJson('/v1/tmdb/' + media + '/' + id);
    const title = detail[media === 'movie' ? 'title' : 'name'];
    if (detail.id !== id || typeof title !== 'string' || !title.trim() || detail.adult === true) throw failed('：详情身份或字段无效');
    if (typeof detail.poster_path === 'string' && detail.poster_path.startsWith('/') && POSTER_NAME.test(detail.poster_path.slice(1)))
      poster = detail.poster_path.slice(1);
  }
  if (!poster) throw failed('：样例无海报，图片未验证');
  const image = await checked('/v1/tmdb/images/w500/' + poster,false);
  if (!['image/jpeg','image/png'].includes(image.headers['content-type'] ?? '')) throw failed('：海报类型无效');
  return { movie: 'passed', tv: 'passed', poster: 'passed', posterBytes: image.bytes.byteLength,
    androidDisplayVerified: false } as const;
}

export async function runVerification(prompt = readSecret, read: LocalReader = readLocal,
  env: Readonly<Record<string, string | undefined>> = process.env) {
  const port = startupConfig({ PORT: env.PORT }).port;
  const token = Secret.gateway(await prompt('本机网关口令（与启动时相同）：'));
  return verifyLive(port,token,read);
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  Promise.resolve().then(() => runVerification(selectPrompt(process.argv.slice(2)))).then(result => {
    console.log('真实 TMDB 只读联调通过：电影发现/详情、剧集发现/详情、海报传输；图片字节数 ' + result.posterBytes);
    console.log('未验证 Android 解码、界面展示及中国内地网络效果。');
  }).catch(error => {
    console.error(error instanceof VerificationError ? error.message :
      '真实只读联调未通过：请检查服务是否已启动、独立网关口令及上游连接；未将未执行项目视为通过。');
    process.exitCode = 1;
  });
}
