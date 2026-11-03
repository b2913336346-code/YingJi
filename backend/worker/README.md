# 历史 Worker 回归夹具

当前 AI 开发基线为 docs/archive/v2/md/影迹 AI V2.0 开发手册.md，模型请求迁移为 Android HTTPS 直连 DeepSeek。此目录保留历史实现与测试，不作为当前应用模型调用入口，不要求部署。以下内容仅用于理解和运行旧方案回归；旧 Worker 配置不启用新的直连路径。

# 影迹 AI Worker · BYOK 本地联调

Android 已适配按次携带 Key、Keystore 客户端签名与受控本地 Mock 联调。未部署、未配置线上 Secret、未调用真实付费 API。保留原推荐提示、服务端成功额度、幂等、版本防迟到及费用账本；Room 及用户原始数据不变。Release 继续关闭网络验证与 Worker 入口。

## 默认安全状态与服务端配置

LIVE_AI_ENABLED=false、UPSTREAM_MODE=LIVE、workers_dev=false、preview_urls=false、observability.enabled=false。BILLING_POLICY 保持 UNVERIFIED 和零预算，不默认启用真实模型。

只有 Worker 自身的 CLIENT_PUBLIC_KEYS、FINGERPRINT_SECRET 放 Secret；用户 DeepSeek Key 不属于绑定或 Secret。旧 ACCESS_TOKEN 和 DEEPSEEK_API_KEY 不再被运行代码使用，部署配置时应移除，禁止共享模型 Key 回退。

CLIENT_PUBLIC_KEYS 为部署方预先批准的 1–4 项 {id,jwk} JSON 数组；id 是 8–100 位字母/数字/下划线/连字符，jwk 仅包含 kty=EC、crv=P-256、x、y 四个公有字段，严禁 d 等私有材料。未经批准的公钥不能自行注册：生产无注册、自动信任或发令牌接口。所有已批准签名公钥属于同一位个人用户，沿用 yingji-personal-v1 主体及原 SQLite namespace；轮换客户端签名公钥不重置额度、任务或版本。

客户端私钥应在 Android Keystore 生成并保持不可导出，将公钥经受控方式交给部署方加入允许列表。客户端私钥和模型 Key 均不能内置 APK。FINGERPRINT_SECRET 是部署方独立生成的 32–256 位字母/数字/下划线/连字符 Secret，用于任务的不可逆凭据绑定；必须稳定保存，轮换前须另行制定旧绑定兼容方案，不能通过换 Secret 清空账本。

## 版本化 HTTP 契约

全部要求 HTTPS，无查询串、压缩请求或任意代理目标。响应头 X-Yingji-Api-Contract=byok-1；业务 JSON schemaVersion 继续为 1，以复用原提示、模式、画像版本和额度结构。旧 /v1/recommendations/hints 与 /v1/quota 路径不再提供服务。

- POST /v1/ai/recommend：沿用全部原业务字段，见下文；本次请求须携带 X-DeepSeek-Key。
- POST /v1/ai/verify：JSON 仅 {schemaVersion:1,requestId:UUID-v4}，本次请求须携带 X-DeepSeek-Key。
- GET /v1/ai/quota：返回原 schemaVersion/status/quota/daily/total；不携带模型 Key、不消耗模型任务。
- GET /v1/ai/health：返回 schemaVersion/status/apiContract/model/liveAiEnabled/fees；需要签名但不携带模型 Key、不访问供应商。

所有接口均要求 Authorization: Signature 客户端公钥id，以及 X-Yingji-Auth-Version: 1、X-Yingji-Timestamp（13 位 Unix 毫秒）、X-Yingji-Nonce（新 UUID-v4）、X-Yingji-Signature。签名为 ECDSA P-256/SHA-256、64 字节 r||s（P1363）转无填充 base64url；Android SHA256withECDSA 的 DER 输出须在客户端转换后发送。签名文本是以下八行 UTF-8，以 LF 连接、末尾无换行：

    YINGJI-BYOK-1
    HTTP方法（大写）
    origin + pathname（无查询串）
    Authorization完整值
    X-Yingji-Timestamp完整值
    X-Yingji-Nonce完整值
    请求体原始UTF-8字节的SHA-256小写hex；GET按空字节计算
    X-DeepSeek-Key值的SHA-256小写hex；GET按空字符串计算

密钥摘要只用于本次签名验证，不持久化。业务输入规范化后的 SHA-256 用于原任务指纹；新增 credentialBinding 是服务端 Secret 的 HMAC，包含当前模型 Key，但不保存 Key 或可还原的密钥材料。

签名绑定 URL origin/路径、方法、原始请求体、模型 Key、时间和 nonce。客户端时差最多正负 60 秒，服务端再次检查。nonce 和访问频率在原 Durable Object 的短事务中持久化；原包重放返回 NONCE_REPLAY，换新签名/nonce 查询相同 requestId 才是业务幂等回放。网络重试必须重新签名，不能重发原签名包；requestId 及业务内容保持一致。更换模型 Key 后必须使用新 requestId，同 ID 换 Key 返回 REQUEST_ID_CONFLICT。存量无 credentialBinding 的旧任务仍可由获准个人身份读取，不重新调用供应商。

## 请求、提示和验证响应

recommend 必填字段保持：schemaVersion、requestId、mode（FOR_YOU/TONIGHT/EXPLORE/QUERY）、userQuery、externalProcessingConsent=true、profileVersion、sourceSettingsVersion、profileSummary、excludeExternalIds、hardConditions。未知字段拒绝；QUERY 最多 500 字符且非空，其他模式查询为空。profileSummary 仅三项来源布尔值、明确偏好和获准的评分/反馈推断；明确/推断各最多 20 条；排除身份最多 50 个；硬条件数组最多 12 项。原分类、作品主键和完整作品库不上传。

recommend 响应沿用 schemaVersion/requestId/profileVersion/sourceSettingsVersion/status/recommendationHints/usage/quota/error。只有 preferredTopics 和 conditions，不返回虚构作品、海报或候选 ID。继续严格校验模型、用量、硬条件与明确排除；候选核验和本地排序仍由 Android 执行。旧画像版本的迟到结果不能成功返回。

verify 调用固定 [DeepSeek 账户查询端点](https://api-docs.deepseek.com/api/get-user-balance/)，不调用聊天生成，不向客户端返回余额数值。成功响应为 schemaVersion/requestId/status=success/verified=true/verifiedAt/usage/quota/error=null；失败 verified=false、verifiedAt=null，客户端不得据此覆盖已保存 Key。账户不可用或上游 402 返回 UPSTREAM_BILLING，401 返回 UPSTREAM_AUTH，403 返回 UPSTREAM_FORBIDDEN，429 返回 UPSTREAM_RATE_LIMIT，超时 UPSTREAM_TIMEOUT，5xx UPSTREAM_UNAVAILABLE。验证最多一次 HTTP 尝试、总期限 15 秒、响应最多 8192 字节；不自动重试。相同验证 ID 可回放结果或返回 202 pending，不重复查询。

验证不消耗每日 30 次成功推荐任务；验证任务仅保存状态、时间、不可逆指纹、结构化响应/错误和 HTTP 用量。原查询/画像、余额正文、用户 Key、客户端私钥及服务端 Secret 不保存到 KV/D1/R2/DO storage/日志/分析/错误消息。原始 Key 只在当前请求头与请求调用栈内转交 RequestGate 并用于固定上游鉴权，不放进 env、对象成员、持久化值或 JSON。Worker 能临时接触 Key，并非端到端保密。

## 限流、幂等与费用

个人主体每分钟最多 120 次已验证签名的访问；新推荐最多 6 次/分钟，新验证最多 6 次/分钟。推荐和验证共用一个在途位置，其他新任务拒绝；相同业务 ID 可查询 pending 或已保存结果。访问记录损坏或已初始化记录丢失 fail closed。nonce 只在签名已过期后清理，保留独立版本标记；不删除业务账本或已有任务。未决在途记录超过 20 秒返回 OUTCOME_UNKNOWN 并阻止盲目重做，需人工只读核查，不提供自动清账/重建端点。

沿用每日 30 次成功推荐、北京时间日界、原子预留、失败不扣成功次数、重放不重复结算、服务器权威时间及增量迁移。每次推荐 HTTP 尝试在发出前记录派发意图和费用上限，结束后记录可信 Token/状态/费率快照；用量不明保留费用占用，最终存储失败不报告成功。原 A5 老记录保留并进入 REVIEW_REQUIRED，不猜测历史费用；不改 namespace、既有 job: 键或原业务数据。

DeepSeek API 实际费用归用户账户；Worker 平台费用归部署方。Worker 业务额度和本地费率估算均不是 DeepSeek 真实余额或账户支出上限。BILLING_POLICY 沿用原字段、整数 micro-CNY、费率快照、日/累计预算与未知费用预留；actualCostVerified=false。MOCK 费率只允许 UPSTREAM_MODE=MOCK，且上游代码拒绝非 TEST_ONLY_ 凭据；LIVE 使用 CONFIRMED 才允许新模型任务。真实价格、真实模型返回标识、真实用量和云端行为尚未核验。

模型仅发往固定 https://api.deepseek.com/chat/completions，保持 deepseek-flash、非流式、关闭思考、max_tokens=512、JSON 提示，响应最多 32768 字节。总期限 15 秒，仅网络故障或 429/500/502/503/504 至多重试一次；存在 Retry-After 不提前重试。401/403/402、重定向、格式错误不重试，不切换模型。两类上游都使用 manual 重定向并拒绝 3xx，不转发凭据到其他地址。输出在 JSON 解码、主题规范化前检查敏感字段，转义回显也不能写入缓存。

## 本地测试与未完成项

在本目录使用锁定的 Node/依赖，运行 npm run typecheck、npm test、node --test test/local-http.integration.mjs 和 npm run build。build 仅 wrangler deploy --dry-run 打包，不发布。测试全部使用合成签名/模型凭据、固定响应和禁止透传的网络拦截；不会加载用户模型 Key 发起真实调用。原测试的业务/额度/迁移/存储失败断言保留，只适配新路由、签名和配置构造。

test/local-integration-server.mjs 仍只监听 127.0.0.1；/__test/ready、/__test/state 与 X-Yingji-Mock-Session 仅属于本地夹具，不是生产认证或部署路由。它转发新的 BYOK 签名协议，不再替旧共享 Bearer 客户端授权。测试会话隔离在 .wrangler/a8-local-*，整个运行器重启创建新测试存储，不可用于真实额度重置。控制故障的请求不生成推荐；日志仅含白名单元数据，无查询、画像正文或凭据。

### Android / MuMu 本地 Mock

Debug 入口沿用首页 Mock 场景菜单中的“本地 Worker 联调（Mock）”及“AI 模型配置”。仅接受 TEST_ONLY_BYOK_ 开头的虚构测试 Key。验证通过固定账户查询的拦截响应完成，不调用真实模型；保存仍使用原有 Android Keystore AES-GCM 加密文件。输入、读取和请求期间的原始 Key 不进入业务 DTO、ViewModel、日志或备份；更换、删除后旧响应和历史写入被版本检查拒绝。

客户端身份是独立、不可导出的 Keystore EC P-256 私钥；只输出 id 和公有 JWK。身份不随模型 Key 删除而清空，也不重置 Worker 额度。每次 HTTP 尝试生成新 nonce 和签名，业务重试保留原 requestId；更换模型 Key 使用新业务 ID，原候选核验、排除、历史、许可缓存和故障降级保留。无 Key 时点击推荐转到已有配置页，不发起 Worker 请求。切换本地场景仅表示同意发送虚构演示摘要，不读取真实 Room 画像或作品库。

测试运行方式（先安装 Debug APK 与测试 APK，保留现有数据）：

1. 在原 MuMu 执行测试类 com.yingji.app.data.ai.WorkerIdentityTest，生成/复用设备身份并写入 no_backup/byok-worker-public-pin.json。测试断言私钥不可导出；文件只含公钥，绝不能导出或传入私钥 d。
2. 通过 adb exec-out run-as com.yingji.app cat no_backup/byok-worker-public-pin.json 获取公钥文件，人工检查后保存为本地主机 android-public-pin.json。
3. 在 backend/worker 目录运行 node test/local-integration-server.mjs --android-public-key <公钥文件绝对路径>，再配置 adb reverse tcp:8787 tcp:8787。
4. 在配置页显式验证并保存虚构 Key，回到首页选择本地 Worker 场景并提交。启动、查看配置、无 Key 切场景及原本地 Mock 均不发送模型请求。

批准只在本地主机启动时生效；服务没有公钥注册、自助批准或凭据替代接口。/__test/* 只存在测试运行器，生产 Worker 不包含它们。测试夹具另含仅供后端测试的合成签名身份，Android APK 不包含其私钥；设备公钥不是身份认证的共享令牌。测试服务重启只重建独立临时测试账本，不能用于真实额度重置。

补充主机测试：node --test test/byok-client.integration.mjs。设备测试 WorkerByokIntegrationTest 覆盖验证/替换/删除/费用错误/并发/迟到历史，WorkerByokScreenTest 通过现有页面完成整个操作链。

尚未验证：真实 DeepSeek 认证、计费与响应，真机硬件密钥行为、生产公钥批准及云端部署。真实付费调用、生产公钥批准和部署均需另行确认。

实现参考：[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[Java Signature 算法格式](https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html)。

参考：[Cloudflare Web Crypto](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/)、[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)。


## 正式验证接入准备（默认关闭）

本阶段只准备真实 Key 的验证通路，首页推荐、候选和摘要继续使用明确标记的 Mock，不接入真实 Room 画像，不发送真实作品库。没有部署或真实 API 测试证据时，不能据此声称真实服务已接通。

Android Release 增加两个公开的构建参数：yingji.ai.workerOrigin（默认空）和 yingji.ai.liveVerificationEnabled（默认 false）。只有显式启用并提供合法、受控的 HTTPS workers.dev 根地址时，才创建真实验证器；地址不得包含路径、查询串、片段或用户信息，默认 443 端口与域名大小写会规范化。其他域名仍沿用当前拒绝策略，使用自定义域名须先审查。启用却缺地址、非法地址或非法布尔配置会拒绝构建，不能把包含凭据的输入打包进 APK。参数只接受公开连接信息，严禁填入任何模型 Key、客户端私钥或服务端 Secret。

Debug 保留原本地 Worker Mock 验证器。Release 的真实验证器只在用户主动点击现有配置页的“验证并保存/更换”后调用固定 Worker 验证接口；成功后才加密替换旧 Key，失败、取消、重复点击和删除后的迟到结果均不得写入。配置页显示将要信任的公开 Worker 地址、传输范围与既有费用说明；验证不发送画像。即使保存了 LIVE Key，首页推荐仍为 Mock，不会因此启用真实推荐、模型调用或画像上传。

本阶段的真实验证器测试全部通过 Fake 服务在内存中执行；Fake 的 LIVE 状态仅供测试契约，不是实际上游验证证据。真实 Key 文件不作为构建输入，不复制进项目、测试夹具、APK、日志或验收附件；实际 Key 应在后续获得真实联调授权后，由用户在设备配置页输入。

正式启用前的确认顺序：

1. 明确受控 Worker 公开地址及部署方；把当前设备签名身份的公钥提交给受控部署方批准，不传私钥，不增加自助注册或共享令牌回退。
2. 核对个人版安装签名、现有数据及 Keystore 身份能否随升级保留；Release 产物目前仅编译，不能通过卸载原应用解决签名不匹配。
3. 保留当前默认关闭状态，确认部署、真实鉴权验证、少量推荐调用的授权和费用边界后，再分别启用。Worker 仍使用原每日 30 次成功任务限制；未确认的费率及零预算不自动改成可付费配置，也不代表 DeepSeek 账户余额上限。
4. 真实验收分别记录无 Key/错误 Key/403/计费不可用/429/超时/重启/更换/删除，验证失败不能覆盖旧 Key；部署和真实调用完成前均标为未验证。

当前尚待提供受控 Worker 地址和正式公钥批准配置。用户提供 API 凭据不自动启用网络验证或授权付费调用；本阶段不部署、不提交或推送。
