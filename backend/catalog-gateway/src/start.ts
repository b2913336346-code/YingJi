import { createGatewayServer } from './gateway.js';
import { MockUpstream } from './mock.js';
import { TmdbUpstream } from './tmdb.js';
import { StartupConfig } from './credentials.js';
import { createProxyHttpsExecutor } from './proxy.js';

/** Listening never fetches upstream; a user request is required. */
export function startServer(config: StartupConfig) {
  const upstream = config.mode === 'MOCK' ? new MockUpstream() : new TmdbUpstream(config.tmdbToken, config.httpProxy ? createProxyHttpsExecutor(config.httpProxy) : undefined);
  const server = createGatewayServer(upstream, config.mode === 'LIVE' ? { accessToken: config.accessToken } : {});
  server.on('error', () => { console.error('本地服务启动失败，请检查端口是否被占用'); process.exitCode = 1; });
  server.listen(config.port, '127.0.0.1', () => {
    console.log('影迹资料网关 ' + config.mode + '：http://127.0.0.1:' + config.port + '/health');
    if (config.mode === 'LIVE') console.log(config.httpProxy ? 'TMDB 出站：显式本机 HTTP 代理' : 'TMDB 出站：直接连接');
  });
  const stop = () => {
    server.close();
    const deadline = setTimeout(() => server.closeAllConnections(), 5000);
    deadline.unref();
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  server.once('close', () => { process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop); });
  return server;
}
