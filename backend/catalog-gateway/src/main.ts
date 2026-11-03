import { startupConfig } from './credentials.js';
import { startServer } from './start.js';

try {
  const config = startupConfig(process.env);
  if (config.mode === 'LIVE') {
    delete process.env.TMDB_READ_ACCESS_TOKEN;
    delete process.env.YINGJI_GATEWAY_TOKEN;
  }
  startServer(config);
} catch (error) {
  console.error(error instanceof Error ? error.message : '本地服务配置无效');
  process.exitCode = 1;
}
