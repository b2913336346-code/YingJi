import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { CONTRACT, GatewayError, resolveRoute } from './contract.js';
import { CatalogUpstream, readPayload } from './upstream.js';
import { Secret } from './credentials.js';

export interface GatewayOptions {
  accessToken?: Secret;
  timeoutMs?: number;
  maxConcurrent?: number;
  requestsPerMinute?: number;
  now?: () => number;
}
function positive(value: number, max: number): number {
  if (!Number.isInteger(value) || value < 1 || value > max) throw new Error('网关限制配置无效');
  return value;
}
function respond(res: ServerResponse, status: number, bytes: Uint8Array, contentType: string, mode: 'MOCK' | 'LIVE', retryAfter?: number) {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'content-type': contentType, 'content-length': bytes.byteLength,
    'cache-control': 'no-store', 'x-content-type-options': 'nosniff',
    'x-yingji-api-contract': mode === 'MOCK' ? CONTRACT : 'catalog-live-1', 'x-yingji-upstream': mode,
    ...(retryAfter === undefined ? {} : { 'retry-after': String(retryAfter) }) });
  res.end(bytes);
}
function json(res: ServerResponse, status: number, value: unknown, mode: 'MOCK' | 'LIVE', retryAfter?: number) {
  respond(res, status, new TextEncoder().encode(JSON.stringify(value)), 'application/json; charset=utf-8', mode, retryAfter);
}

/** Single-process, loopback prototype. Quota is global and resets when this process restarts. */
export function createGatewayServer(upstream: CatalogUpstream, options: GatewayOptions = {}) {
  const mode = upstream.mode;
  const accessToken = options.accessToken;
  if (mode !== 'MOCK' && mode !== 'LIVE') throw new Error('上游模式无效');
  if (mode === 'LIVE' && accessToken?.kind !== 'gateway') throw new Error('真实模式需要独立的本机网关口令');
  const timeoutMs = positive(options.timeoutMs ?? 5000, 30000);
  const maxConcurrent = positive(options.maxConcurrent ?? 4, 32);
  const requestsPerMinute = positive(options.requestsPerMinute ?? 120, 10000);
  const now = options.now ?? Date.now;
  let active = 0;
  let windowStart = now();
  let count = 0;
  async function handle(req: IncomingMessage, res: ServerResponse) {
    try {
      if (req.method !== 'GET') {
        res.setHeader('allow', 'GET');
        res.setHeader('connection', 'close');
        throw new GatewayError(405, 'METHOD_NOT_ALLOWED', '仅支持 GET 请求');
      }
      if (req.headers['transfer-encoding'] !== undefined ||
        (req.headers['content-length'] !== undefined && req.headers['content-length'] !== '0')) {
        res.setHeader('connection', 'close');
        throw new GatewayError(400, 'INVALID_REQUEST', 'GET 请求不能携带请求体');
      }
      const route = resolveRoute(req.url ?? '');
      if (route.kind === 'health') {
        json(res, 200, { status: 'ok', mode, apiContract: mode === 'MOCK' ? CONTRACT : 'catalog-live-1',
          liveUpstreamEnabled: mode === 'LIVE', ...(mode === 'LIVE' ? { upstreamVerified: false } : {}) }, mode);
        return;
      }
      if (mode === 'LIVE') {
        const authorization = req.headers.authorization ?? '';
        if (!authorization.startsWith('Bearer ') || !accessToken!.matches(authorization.slice(7))) {
          res.setHeader('www-authenticate', 'Bearer');
          throw new GatewayError(401, 'UNAUTHORIZED', '本机网关访问口令无效');
        }
      }
      const time = now();
      if (time < windowStart || time - windowStart >= 60000) { windowStart = time; count = 0; }
      if (count >= requestsPerMinute) throw new GatewayError(429, 'RATE_LIMITED', '本机原型请求过于频繁', Math.max(1, Math.ceil((60000 - time + windowStart) / 1000)));
      count++;
      if (active >= maxConcurrent) throw new GatewayError(503, 'BUSY', '资料请求正在处理中，请稍后重试', 1);
      active++;
      const controller = new AbortController();
      const cancelled = () => { if (!res.writableEnded) controller.abort(new Error('CLIENT_DISCONNECTED')); };
      res.once('close', cancelled);
      let timer: ReturnType<typeof setTimeout> | undefined;
      try {
        const deadline = new Promise<never>((_, reject) => {
          timer = setTimeout(() => {
            const problem = new GatewayError(504, 'UPSTREAM_TIMEOUT', '资料请求超时，请稍后重试');
            reject(problem); controller.abort(problem);
          }, timeoutMs);
        });
        const payload = await Promise.race([readPayload(upstream, route, controller.signal), deadline]);
        if (!controller.signal.aborted) respond(res, 200, payload.bytes, payload.contentType, mode);
      } finally {
        clearTimeout(timer);
        res.removeListener('close', cancelled);
        active--;
      }
    } catch (error) {
      const problem = error instanceof GatewayError ? error : new GatewayError(502, 'UPSTREAM_UNAVAILABLE', '资料服务暂时不可用');
      // Never expose upstream error text, stack traces, body or request headers.
      json(res, problem.status, { schemaVersion: 1, error: { code: problem.code, message: problem.message } }, mode, problem.retryAfter);
    }
  }
  const server = createServer({ maxHeaderSize: 8192, headersTimeout: 5000, requestTimeout: 10000 },
    (req, res) => { void handle(req, res); });
  server.keepAliveTimeout = 1000;
  server.maxRequestsPerSocket = 100;
  server.setTimeout(15000, socket => socket.destroy());
  return server;
}
