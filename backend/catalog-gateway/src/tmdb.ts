import { request as httpsRequest, RequestOptions } from 'node:https';
import { ClientRequest, IncomingMessage } from 'node:http';
import { CatalogRequest, GatewayError, invalidUpstream, resolveRoute } from './contract.js';
import { Secret } from './credentials.js';
import { CatalogUpstream } from './upstream.js';

export type HttpsExecutor = (url: URL, headers: Readonly<Record<string, string>>, signal: AbortSignal) => Promise<Response>;
export type HttpsRequester = (url: URL, options: RequestOptions, callback: (response: IncomingMessage) => void) => ClientRequest;
const unavailable = () => new GatewayError(502, 'UPSTREAM_UNAVAILABLE', '资料服务暂时不可用');

/** Guard all terminal events: cancelling a completed Node response must not close a Web stream twice. */
function responseBody(incoming: IncomingMessage, signal: AbortSignal): ReadableStream<Uint8Array> {
  let terminal = false;
  let detachAbort = () => {};
  return new ReadableStream<Uint8Array>({
    start(controller) {
      const fail = () => {
        if (terminal) return;
        terminal = true; detachAbort(); controller.error(unavailable()); incoming.destroy();
      };
      const abort = () => {
        if (terminal) return;
        terminal = true; detachAbort(); controller.error(signal.reason); incoming.destroy();
      };
      detachAbort = () => signal.removeEventListener('abort', abort);
      signal.addEventListener('abort', abort, { once: true });
      incoming.on('data', (chunk: Buffer) => {
        if (terminal) return;
        controller.enqueue(new Uint8Array(chunk));
        if ((controller.desiredSize ?? 0) <= 0) incoming.pause();
      });
      incoming.once('end', () => {
        if (terminal) return;
        terminal = true; detachAbort(); controller.close();
      });
      incoming.once('error', fail);
      incoming.once('aborted', fail);
      incoming.once('close', fail);
      if (signal.aborted) abort();
    },
    pull() { if (!terminal) incoming.resume(); },
    cancel() { terminal = true; detachAbort(); incoming.destroy(); },
  });
}

/** Raw HTTPS preserves byte/encoding limits; no automatic redirects, retries or proxy discovery. */
export function createHttpsExecutor(requester: HttpsRequester = httpsRequest): HttpsExecutor {
  return (url, headers, signal) => new Promise<Response>((resolve, reject) => {
    if (signal.aborted) { reject(signal.reason); return; }
    try {
      const req = requester(url, { method: 'GET', headers, signal,
        rejectUnauthorized: true, minVersion: 'TLSv1.2', maxHeaderSize: 8192, agent: false }, incoming => {
        try {
          if (signal.aborted) { incoming.destroy(); reject(signal.reason); return; }
          const output = new Headers();
          for (const name of ['content-type', 'content-length', 'content-encoding', 'retry-after']) {
            const value = incoming.headers[name];
            if (typeof value === 'string') output.set(name, value);
            else if (value !== undefined) { incoming.destroy(); reject(unavailable()); return; }
          }
          const status = incoming.statusCode ?? 502;
          if ([204,205,304].includes(status)) { incoming.destroy(); resolve(new Response(null, { status, headers: output })); return; }
          const body = responseBody(incoming, signal);
          resolve(new Response(body, { status, headers: output }));
        } catch { incoming.destroy(); reject(unavailable()); }
      });
      req.once('error', () => reject(signal.aborted ? signal.reason : unavailable()));
      req.end();
    } catch { reject(unavailable()); }
  });
}

/** Rebuild and compare the target before attaching any credential, even for internal callers. */
function fixedTarget(request: CatalogRequest): URL {
  let path: string;
  if (request.kind === 'discover') path = '/v1/tmdb/discover/' + request.media;
  else if (request.kind === 'detail') path = '/v1/tmdb/' + request.media + '/' + request.id;
  else if (request.kind === 'image') path = '/v1/tmdb/images/w500/' + request.filename;
  else return invalidUpstream();
  let resolved;
  try { resolved = resolveRoute(path); } catch { return invalidUpstream(); }
  if (resolved.kind === 'health' || resolved.target !== request.target) return invalidUpstream();
  return new URL(resolved.target);
}

export class TmdbUpstream implements CatalogUpstream {
  readonly mode = 'LIVE' as const;
  #token: Secret;
  #execute: HttpsExecutor;
  constructor(token: Secret, execute: HttpsExecutor = createHttpsExecutor()) {
    if (token.kind !== 'tmdb') throw new Error('需要有效的 TMDB 读访问令牌');
    this.#token = token; this.#execute = execute;
  }
  async load(request: CatalogRequest, signal: AbortSignal): Promise<Response> {
    signal.throwIfAborted();
    const target = fixedTarget(request);
    const headers: Record<string, string> = {
      accept: request.kind === 'image' ? 'image/jpeg,image/png' : 'application/json',
      'accept-encoding': 'identity', 'cache-control': 'no-store',
    };
    if (request.kind !== 'image') headers.authorization = this.#token.use(value => 'Bearer ' + value);
    try { return await this.#execute(target, headers, signal); }
    catch { if (signal.aborted) throw signal.reason; throw unavailable(); }
  }
  assertResponseSafe(bytes: Uint8Array): void { this.#token.assertAbsent(bytes); }
}
