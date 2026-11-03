import { request as httpsRequest } from 'node:https';
import { HttpsProxyAgent } from 'https-proxy-agent';
import { GatewayError } from './contract.js';
import { createHttpsExecutor, HttpsExecutor, HttpsRequester } from './tmdb.js';

import { LocalHttpProxy, parseLocalHttpProxy } from './proxy-config.js';

/** One CONNECT agent per request; no global/system proxy changes or direct fallback. */
export function createProxyHttpsExecutor(proxy: LocalHttpProxy, requester: HttpsRequester = httpsRequest): HttpsExecutor {
  const validated = parseLocalHttpProxy(proxy.url);
  if (!validated) throw new Error('需要显式本机 HTTP 代理配置');
  return (url,headers,signal) => {
    if (url.protocol !== 'https:' || !['api.themoviedb.org','image.tmdb.org'].includes(url.hostname) ||
      url.port !== '' || url.username || url.password || url.hash)
      return Promise.reject(new GatewayError(502,'INVALID_UPSTREAM_RESPONSE','资料服务目标无效'));
    if (signal.aborted) return Promise.reject(signal.reason);
    const agent = new HttpsProxyAgent(validated.url, { signal, keepAlive:false, maxSockets:1 });
    const throughProxy: HttpsRequester = (target,options,callback) => {
      const req = requester(target,{ ...options, agent },callback);
      req.once('close', () => agent.destroy());
      return req;
    };
    return createHttpsExecutor(throughProxy)(url,headers,signal).catch(error => {
      agent.destroy(); throw error;
    });
  };
}
