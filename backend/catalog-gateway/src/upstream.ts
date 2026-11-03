import { CatalogRequest, GatewayError, IMAGE_LIMIT, JSON_LIMIT, invalidUpstream, normalizeJson } from './contract.js';

/** The gateway owns deadlines, size validation and error mapping for either upstream. */
export interface CatalogUpstream {
  readonly mode: 'MOCK' | 'LIVE';
  load(request: CatalogRequest, signal: AbortSignal): Promise<Response>;
  assertResponseSafe?(bytes: Uint8Array): void;
}
export type Payload = { bytes: Uint8Array; contentType: string };

async function readBounded(response: Response, limit: number, signal: AbortSignal): Promise<Uint8Array> {
  const encoding = response.headers.get('content-encoding');
  const length = response.headers.get('content-length');
  if ((encoding !== null && encoding.toLowerCase() !== 'identity') ||
    (length !== null && (!/^[0-9]+$/.test(length) || Number(length) > limit))) invalidUpstream();
  if (!response.body) invalidUpstream();
  const reader = response.body!.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  let completed = false;
  const abort = () => { void reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', abort, { once: true });
  try {
    signal.throwIfAborted();
    while (true) {
      const next = await reader.read();
      signal.throwIfAborted();
      if (next.done) { completed = true; break; }
      size += next.value.byteLength;
      if (size > limit) invalidUpstream();
      chunks.push(next.value);
    }
    if (length !== null && Number(length) !== size) invalidUpstream();
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return bytes;
  } finally {
    signal.removeEventListener('abort', abort);
    if (!completed) abort();
    reader.releaseLock();
  }
}

function imageSignature(bytes: Uint8Array, mime: string): boolean {
  if (mime === 'image/jpeg') return bytes.length >= 5 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 &&
    bytes[bytes.length - 2] === 255 && bytes[bytes.length - 1] === 217;
  if (bytes.length < 33 || ![137,80,78,71,13,10,26,10].every((b, i) => bytes[i] === b) ||
    new TextDecoder().decode(bytes.slice(12,16)) !== 'IHDR') return false;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const width = view.getUint32(16); const height = view.getUint32(20);
  return width >= 1 && width <= 10000 && height >= 1 && height <= 10000 && width * height <= 40000000;
}

export async function readPayload(upstream: CatalogUpstream, request: CatalogRequest, signal: AbortSignal): Promise<Payload> {
  const response = await upstream.load(request, signal);
  try {
    signal.throwIfAborted();
    if (response.status !== 200) {
      void response.body?.cancel().catch(() => {});
      if (response.status === 404) throw new GatewayError(404, 'NOT_FOUND', '作品或海报不存在');
      if (response.status === 401 || response.status === 403)
        throw new GatewayError(502, 'UPSTREAM_AUTH', '资料服务凭据或权限不可用');
      if (response.status === 429) {
        const value = response.headers.get('retry-after') ?? '';
        const retryAfter = /^[0-9]{1,4}$/.test(value) && Number(value) >= 1 && Number(value) <= 3600 ? Number(value) : 60;
        throw new GatewayError(503, 'UPSTREAM_RATE_LIMIT', '资料服务请求受限，请稍后重试', retryAfter);
      }
      if (response.status >= 500) throw new GatewayError(502, 'UPSTREAM_UNAVAILABLE', '资料服务暂时不可用');
      invalidUpstream();
    }
    const mime = response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase();
    if (request.kind === 'image') {
      if (mime !== 'image/jpeg' && mime !== 'image/png') { void response.body?.cancel().catch(() => {}); invalidUpstream(); }
      const bytes = await readBounded(response, IMAGE_LIMIT, signal);
      upstream.assertResponseSafe?.(bytes);
      if (!imageSignature(bytes, mime!)) invalidUpstream();
      return { bytes, contentType: mime! };
    }
    if (mime !== 'application/json') { void response.body?.cancel().catch(() => {}); invalidUpstream(); }
    const bytes = await readBounded(response, JSON_LIMIT, signal);
    upstream.assertResponseSafe?.(bytes);
    let parsed: unknown;
    try { parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
    catch { invalidUpstream(); }
    const normalized = normalizeJson(parsed, request);
    const output = new TextEncoder().encode(JSON.stringify(normalized));
    if (output.byteLength > JSON_LIMIT) invalidUpstream();
    upstream.assertResponseSafe?.(output);
    return { bytes: output, contentType: 'application/json; charset=utf-8' };
  } catch (error) {
    if (!response.body?.locked) void response.body?.cancel().catch(() => {});
    throw error;
  }
}
