# 影迹专用资料网关：本地 Mock / TMDB 真实上游

提供电影/剧集发现、详情、海报及健康检查接口。默认使用合成 Mock 样例；显式 LIVE 模式才连接官方 TMDB。Android 尚未接入。

只监听 127.0.0.1，默认端口 8788；两种模式都不写数据库或缓存。当前是本机开发服务，生产部署、终端身份注册、HTTPS 入站、CDN 及计费控制属于后续阶段。真实上游直连不依赖已有 CDN。

## 准备与编译

需要 Node.js 22+。本机同时安装旧 Node 16，可在当前 PowerShell 终端选择已验证的 Node 22；不修改系统设置：

~~~powershell
$env:PATHEXT = '.COM;.EXE;.BAT;.CMD;' + $env:PATHEXT
$env:Path = 'C:\Users\29133\AppData\Local\hermes\node;' + $env:Path
Set-Location 'E:\AIPM\Project\YingJi\backend\catalog-gateway'
node --version
npm.cmd ci --ignore-scripts
npm.cmd run typecheck
npm.cmd test
~~~

PowerShell 中显式调用 npm.cmd，避免同名 npm.ps1 被执行策略拦截。本机启动的终端可能只包含 .CPL 扩展名，上述 PATHEXT 命令补齐当前会话的常用可执行扩展名；不修改系统执行策略或持久环境变量。

依赖锁定于 package-lock.json；代理传输使用 https-proxy-agent 7.0.6。selfsigned 2.4.1 仅用于在测试进程内生成临时 TLS 证书，不参与生产证书验证或保存证书文件。npm.cmd test 包含编译；npm.cmd run build 可单独编译。

## Mock 模式

~~~powershell
npm.cmd start
~~~

默认 UPSTREAM_MODE=MOCK；不读取 TMDB 凭据、不请求外部上游。电影样例 ID 为 2000000001–2000000003，剧集为 2000000011–2000000013。最后一个剧集无海报；图片样例仅有 yingji-mock-movie.png、yingji-mock-tv.png，使用有效的 1x1 PNG。样例不可作为真实 TMDB 来源入库。

~~~powershell
Invoke-RestMethod 'http://127.0.0.1:8788/health'
Invoke-RestMethod 'http://127.0.0.1:8788/v1/tmdb/discover/movie'
~~~

## 真实模式：无需把令牌写进命令或文件

第一个终端执行：

~~~powershell
npm.cmd run start:live
~~~

默认隐藏输入；需要可见输入时，使用下面“输入回显”中的显式选项。按提示输入两项：

1. TMDB API Read Access Token（读访问令牌），不带 Bearer 前缀，不是 v3 API Key。字符范围为字母、数字、点、下划线、波浪号或连字符，长度 32–4096。
2. 独立本机网关口令：建议用密码管理器生成 32–256 位随机字母/数字/下划线/连字符；请保留在密码管理器中用于第二个终端，不能与 TMDB 令牌相同。这是本机开发口令，不是最终 App 身份系统。

启动助手直接把验证后的内存凭据交给服务，不写入环境变量、文件、命令行参数或日志。Ctrl+C 结束服务。命令使用 Node 的终端输入，不依赖执行 PowerShell 脚本或修改系统执行策略；非交互管道输入会被拒绝。

如使用外部服务管理器，也可显式配置 UPSTREAM_MODE=LIVE、TMDB_READ_ACCESS_TOKEN 和 YINGJI_GATEWAY_TOKEN，再运行 npm.cmd start。不得把真实值写进源码或命令历史。main 读取成功后移除子进程环境中的两个凭据变量；凭据仍需在服务内存中使用，不承诺运行时不可提取。缺失、格式不合法、口令相同、未知模式或 NODE_TLS_REJECT_UNAUTHORIZED=0 均在监听前拒绝。

可通过 PORT 指定 1024–65535 的端口；两个终端应使用相同 PORT。地址固定为本机。

## 本机代理模式（当前联调用）

当前电脑直连 TMDB 不通，但现有 HTTP 代理 127.0.0.1:7897 可以连接官方 API 和图片主机。在第一个窗口先按 Ctrl+C 停止旧服务，再执行：

~~~powershell
npm.cmd run start:live:proxy -- --show-input
~~~

重新输入 TMDB 读访问令牌和独立网关口令，保持代理程序和后端窗口运行。启动日志会显示“TMDB 出站：显式本机 HTTP 代理”。第二个窗口仍执行 npm.cmd run verify:live -- --show-input，使用同一个独立网关口令。

也可显式指定其他本机端口：

~~~powershell
npm.cmd run start:live -- --proxy http://127.0.0.1:7897 --show-input
~~~

服务管理器可设置 TMDB_HTTP_PROXY；命令行 --proxy 优先。未配置或环境值为空时仍为直连；不会自动读取 HTTP_PROXY、HTTPS_PROXY 或系统代理设置。Mock 忽略此配置，不连接代理。

仅支持 http://127.0.0.1:1–65535，可带末尾斜杠；拒绝远程地址、localhost、HTTPS/SOCKS、认证信息、路径、查询及片段。无效配置不会回退直连，也不会在交互助手中继续索取凭据。代理不可用时返回错误，不自动换通道或重试。

CONNECT 仅包含官方目标主机及端口，不附加 TMDB 或网关口令。之后仍通过 TLS 1.2+ 验证 TMDB 的主机名及证书，API 令牌只发送在加密通道内，海报请求不携带该令牌。每个请求有独立代理 Agent，沿用原有总期限、并发、响应限额和取消处理。

这里的 127.0.0.1 指运行后端的电脑。它用于当前开发联调；未来部署服务器时需要单独验证服务器到 TMDB 的网络。

## 输入回显

需要在终端看见输入时，显式传递 --show-input：

~~~powershell
npm.cmd run start:live -- --show-input
~~~

第二个窗口使用：

~~~powershell
npm.cmd run verify:live -- --show-input
~~~

提示会标记“输入可见”；令牌和口令会出现在本机终端及可能的录屏中，请勿发送输入区域截图。该选项只影响本次交互输入，不把凭据写入文件、命令行参数或业务日志。默认命令仍隐藏输入，两种模式均禁用输入历史。

## 真实只读联调

保持第一个终端运行，在第二个终端选择同样的 Node 22、进入同一目录，然后执行：

~~~powershell
npm.cmd run verify:live
~~~

仅输入第一终端使用的独立网关口令；此处不需要 TMDB 令牌。验证命令只访问 127.0.0.1，依次检查健康状态、电影发现及一条详情、剧集发现及一条详情，以及一张海报，最多六个本地请求。不保存资料或图片文件，不读取应用数据库。候选为空、缺少样例海报或任一断言失败，均不会报告整体验证通过。

命令成功仅证明当前电脑到 TMDB 的只读调用与海报传输，不能证明 Android 展示、国内运营商网络或未来服务器链路可用。本机代理模式的真实只读联调已复核通过，详见文末实网验收记录。

本阶段支持直接 HTTPS 或显式本机 HTTP CONNECT 代理；不自动发现系统代理，不跟随重定向，不禁用证书校验。真实模式按所选通道访问 api.themoviedb.org 和 image.tmdb.org；网络不通会返回明确错误。

## 接口契约

- GET /health：检查本机服务，不请求上游。LIVE 返回 liveUpstreamEnabled=true、upstreamVerified=false；这表示真实适配已启用，不能作为 TMDB 可用性证据。
- GET /v1/tmdb/discover/{movie|tv}：page=1、results 的 id/adult。查询参数可省略；提供时仅允许 language=zh-CN、page=1、include_adult=false、sort_by=popularity.desc。
- GET /v1/tmdb/{movie|tv}/{id}：ID 为 1–2147483647，无前导零；只允许 language=zh-CN。电影返回 title/release_date/runtime；剧集返回 name/first_air_date；共有 id/adult/genres/overview/poster_path。
- GET /v1/tmdb/images/w500/{filename}：只允许相对 jpg/jpeg/png 文件名，不允许查询参数。

LIVE 资料和图片接口要求 Authorization: Bearer 加独立本机网关口令；健康检查无需口令。TMDB 令牌只由后端附加到 api.themoviedb.org，图片主机不接收该令牌。客户端网关口令不会转发上游。

成功 JSON 无包裹层，只保留现有 Android 解析器需要的字段。成人条目不作为候选返回，发现列表去重。重复或未知参数、任意目标 URL、编码路径及写请求均拒绝。真实适配器在内部调用时也重新构建并核对官方目标。

响应带 X-Yingji-Upstream: MOCK/LIVE，分别使用 X-Yingji-Api-Contract: catalog-mock-1/catalog-live-1；始终 Cache-Control: no-store。模式标记必须用于后续 App 来源校验；不得把 Mock 数据标成真实来源，或把服务端缓存冒充刚完成的上游复核。

## 异常与资源限制

错误 JSON 为 schemaVersion=1、error.code 和 error.message，不转发原始错误正文、堆栈、Cookie 或凭据。

- 400 INVALID_REQUEST：参数或请求体无效；404 NOT_FOUND：接口、作品或海报不存在；405 METHOD_NOT_ALLOWED：仅支持 GET。
- 401 UNAUTHORIZED：LIVE 本机网关口令无效，不会调用 TMDB。
- 429 RATE_LIMITED：每进程每分钟 120 次资料请求；503 BUSY：4 个在途请求已满，不排队。
- 502 UPSTREAM_AUTH：TMDB 401/403，属于服务端上游权限问题，与客户端 401 区分。
- 503 UPSTREAM_RATE_LIMIT：上游 429；Retry-After 限 1–3600 秒，否则使用 60 秒。
- 502 UPSTREAM_UNAVAILABLE：连接异常或上游 5xx；502 INVALID_UPSTREAM_RESPONSE：格式、身份、类型、长度或字段校验失败，也拒绝 3xx。
- 504 UPSTREAM_TIMEOUT：获取及读取响应超过 5 秒。

不自动重试。超时或客户端断开会取消读取；完成、拒绝或中断均释放响应资源。健康检查不占资料配额；并发拒绝仍计入分钟配额。限流保存在内存中，重启重置，不具备生产身份配额含义。

JSON 输入和输出最多 128 KiB，图片最多 2 MiB；上游响应头最多 8 KiB。使用 HTTPS、TLS 1.2+、证书及主机验证，不接受压缩体或任意代理地址。正常及 JSON 转义后的响应若回显上游令牌会被拒绝。

图片检查 MIME、PNG/JPEG 基本签名，PNG 限制尺寸和像素量；不等于完整图片解码。真实图片解码和 Android 展示仍须设备验证。

## 文件与验证范围

- package.json、package-lock.json、tsconfig.json、.gitignore：命令、锁定依赖及本目录配置。
- src/contract.ts、src/upstream.ts：路由、字段校验、有界读取和错误映射。
- src/mock.ts：合成资料和 PNG。
- src/credentials.ts、src/tmdb.ts：内存凭据、配置及真实 HTTPS 适配。
- src/proxy-config.ts、src/proxy.ts：显式本机代理校验和 CONNECT 传输。
- src/gateway.ts、src/start.ts、src/main.ts：HTTP 接入、访问控制及本机启动。
- scripts/prompt.ts、scripts/start-live.ts、scripts/test-live.ts：隐藏/显式回显输入与只读联调。
- test/contract.test.ts、test/gateway.test.ts、test/tmdb.test.ts、test/scripts.test.ts：契约、Mock、LIVE 模拟网络及辅助命令测试。
- test/proxy.test.ts：配置、命令参数、真实本地 TLS 隧道、凭据隔离、代理拒绝/重定向/断开/不可用、证书不可信及取消释放。

自动化测试不使用真实令牌，也不访问真实 TMDB。模拟网络覆盖固定目标、API/海报凭据隔离、TLS 参数、重定向拒绝、原始流取消、凭据脱敏及回显拒绝、启动配置、LIVE 认证、超时、限流和 Node 隐藏输入。

Android、数据库及旧 Worker 未修改；真实 TMDB、Android 界面、国内网络、生产身份认证及 CDN 均未验证。

参考：[TMDB 认证](https://developer.themoviedb.org/docs/authentication-application)、[TMDB 图片](https://developer.themoviedb.org/docs/image-basics)、[TMDB 限流](https://developer.themoviedb.org/docs/rate-limiting)、[Node HTTPS](https://nodejs.org/api/https.html)。

## 本阶段执行结果

- Node.js 22.23.2、TypeScript 5.9.3；npm.cmd run typecheck 通过，npm.cmd test 编译成功，157 项通过，0 失败、0 跳过、0 取消；代理相关 27 项测试包含完整本地 TLS 隧道。
- LIVE 命令行入口使用 TEST_ONLY 合成凭据启动；健康检查 HTTP 200，未认证资料请求 HTTP 401，无真实 TMDB 请求，无凭据回显。验证进程已关闭。
- Android 离线执行 :app:assembleDebug 和 :app:testDebugUnitTest --tests com.yingji.app.domain.ai.Tmdb* 成功，任务均 UP-TO-DATE，沿用已有 33 项通过结果。
- 新代理适配器经现有 127.0.0.1:7897 的真实通道检查：官方 API 返回 401，图片主机返回 404（均未发送令牌，仅说明网络可达）。
- 没有读取用户 TMDB 令牌、手机令牌或其他凭据文件。旧直连服务此前返回 504；代理模式现已完成认证后的真实资料及海报复核，详见下方实网验收记录。

本次代理阶段修改：新增 src/proxy-config.ts、src/proxy.ts、test/proxy.test.ts；更新 src/credentials.ts、src/start.ts、scripts/start-live.ts、package.json、package-lock.json、README.md。Android、数据库、旧 Worker 及根目录配置未修改。

## 2026-11-03 本机实网验收

经正在运行的 LIVE 网关及其服务端凭据，独立执行完整只读验证：健康检查、电影发现、电影详情、剧集发现、剧集详情、JPEG 海报均 HTTP 200；海报传输 52,365 字节。未读取或保存用户 TMDB 令牌，未写应用数据库。

截图中此前的 502 在本次复核未复现，无法仅据此确定那次失败原因；本次成功仅代表当前电脑及代理链路下的这一轮验证。Android 解码/展示、国内手机网络、服务器部署和 CDN 仍未验证。
