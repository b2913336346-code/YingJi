import { timingSafeEqual } from 'node:crypto';
import { inspect } from 'node:util';
import { invalidUpstream } from './contract.js';
import { LocalHttpProxy, parseLocalHttpProxy } from './proxy-config.js';

/** Process-memory credential. Native private fields avoid accidental JSON/inspect disclosure. */
export class Secret {
  #value: string;
  private constructor(public readonly kind: 'tmdb' | 'gateway', value: string) { this.#value = value; }
  static tmdb(value: unknown): Secret {
    if (typeof value !== 'string' || !/^[A-Za-z0-9._~-]{32,4096}$/.test(value))
      throw new Error('TMDB_READ_ACCESS_TOKEN 缺失或格式无效；请配置读访问令牌，不含 Bearer 前缀');
    return new Secret('tmdb', value);
  }
  static gateway(value: unknown): Secret {
    if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{32,256}$/.test(value))
      throw new Error('YINGJI_GATEWAY_TOKEN 缺失或格式无效；需要独立的 32–256 位随机口令');
    return new Secret('gateway', value);
  }
  use<T>(block: (value: string) => T): T { return block(this.#value); }
  matches(candidate: string): boolean {
    const expected = Buffer.from(this.#value); const supplied = Buffer.from(candidate);
    return supplied.length === expected.length && timingSafeEqual(expected, supplied);
  }
  assertAbsent(bytes: Uint8Array): void {
    if (Buffer.from(bytes).includes(this.#value)) invalidUpstream();
  }
  toString() { return 'Secret([REDACTED])'; }
  toJSON() { return '[REDACTED]'; }
  [inspect.custom]() { return this.toString(); }
}

export type StartupConfig =
  | { mode: 'MOCK'; port: number }
  | { mode: 'LIVE'; port: number; tmdbToken: Secret; accessToken: Secret; httpProxy?: LocalHttpProxy };
export function startupConfig(env: Readonly<Record<string, string | undefined>>): StartupConfig {
  const mode = env.UPSTREAM_MODE ?? 'MOCK';
  if (mode !== 'MOCK' && mode !== 'LIVE') throw new Error('UPSTREAM_MODE 只能为 MOCK 或 LIVE');
  const rawPort = env.PORT ?? '8788';
  if (!/^[0-9]{4,5}$/.test(rawPort) || Number(rawPort) < 1024 || Number(rawPort) > 65535)
    throw new Error('PORT 必须为 1024–65535 的端口号');
  if (mode === 'MOCK') return { mode, port: Number(rawPort) };
  if (env.NODE_TLS_REJECT_UNAUTHORIZED === '0') throw new Error('真实模式不允许关闭 TLS 证书验证');
  const httpProxy = parseLocalHttpProxy(env.TMDB_HTTP_PROXY);
  const tmdbToken = Secret.tmdb(env.TMDB_READ_ACCESS_TOKEN);
  const accessToken = Secret.gateway(env.YINGJI_GATEWAY_TOKEN);
  if (tmdbToken.use(value => accessToken.matches(value))) throw new Error('上游令牌与网关口令必须不同');
  return { mode, port: Number(rawPort), tmdbToken, accessToken, ...(httpProxy ? { httpProxy } : {}) };
}
