import { pathToFileURL } from 'node:url';
import { startupConfig, StartupConfig } from '../src/credentials.js';
import { startServer } from '../src/start.js';
import { readSecret, selectPrompt } from './prompt.js';
import { parseLocalHttpProxy } from '../src/proxy-config.js';

export async function configureLive(
  prompt = readSecret,
  launch: (config: StartupConfig) => unknown = startServer,
  env: Readonly<Record<string, string | undefined>> = process.env,
): Promise<void> {
  parseLocalHttpProxy(env.TMDB_HTTP_PROXY);
  const tmdb = await prompt('TMDB API 读访问令牌（不含 Bearer 前缀）：');
  const local = await prompt('独立本机网关口令（32–256 位随机字母/数字/_/-）：');
  const config = startupConfig({ UPSTREAM_MODE: 'LIVE', PORT: env.PORT,
    NODE_TLS_REJECT_UNAUTHORIZED: env.NODE_TLS_REJECT_UNAUTHORIZED, TMDB_HTTP_PROXY: env.TMDB_HTTP_PROXY,
    TMDB_READ_ACCESS_TOKEN: tmdb, YINGJI_GATEWAY_TOKEN: local });
  // Do not write credentials into environment, arguments, files or logs.
  launch(config);
}
export function parseLiveArgs(args: readonly string[], env: Readonly<Record<string,string | undefined>> = process.env) {
  const promptArgs: string[] = [];
  let proxyArgument: string | undefined;
  let providedProxy = false;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === '--proxy') {
      if (providedProxy || index + 1 >= args.length) throw new Error('--proxy 只允许指定一次本机 HTTP 代理');
      providedProxy = true; proxyArgument = args[++index];
      if (!parseLocalHttpProxy(proxyArgument)) throw new Error('--proxy 需要有效的本机 HTTP 代理');
    } else promptArgs.push(args[index]!);
  }
  return { prompt: selectPrompt(promptArgs), env: {
    PORT: env.PORT, NODE_TLS_REJECT_UNAUTHORIZED: env.NODE_TLS_REJECT_UNAUTHORIZED,
    TMDB_HTTP_PROXY: providedProxy ? proxyArgument : env.TMDB_HTTP_PROXY,
  } };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  Promise.resolve().then(() => {
    const options = parseLiveArgs(process.argv.slice(2));
    return configureLive(options.prompt, startServer, options.env);
  }).catch(() => {
    console.error('真实模式未启动：请检查输入的两个独立口令、端口、TLS 及本机代理配置。');
    process.exitCode = 1;
  });
}
