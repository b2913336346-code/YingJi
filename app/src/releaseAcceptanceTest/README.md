# 手动 Release 端到端验收

此目录只有显式构建脚本会启用。默认应用和默认 androidTest 源集不包含这些测试。

- prepare-bridge.init.gradle：创建独立包名 com.yingji.app.tmdb.uiacceptance 的 Debug 凭据准备包和测试 APK。
- release-e2e.init.gradle：创建相同包名、相同本地签名的非调试 Release 验收包，使用 src/release、R8 和资源压缩，不加入测试 Activity 或测试保留规则。
- ReleaseAcceptanceBridgeTest：一次性 RSA/AES 密文中转、合成防篡改预检、克隆包 Key 验证、只读数据核查及本地海报负载夹具。真实 Key 不以命令参数或明文文件离开应用进程。
- ui-harness.mjs：外部 ADB/UIAutomator 操作辅助。按可见文本、语义及滚动容器定位，保存检查点，等待状态而非固定延时。footerTop 参数用于按设备校准底部导航定位阈值（默认 850，兼容原 1920×1092 横屏；1080×2400 真机传入 2100）。每次以实际 UI 层级确认导航边界，不以屏幕尺寸推断通过。真机可传 swipeFraction=0.25、swipeDuration=450 缩短滚动手势，原模拟器默认手势保持不变。

构建命令（在工程根目录执行）：

```powershell
.\gradlew.bat -I app/src/releaseAcceptanceTest/prepare-bridge.init.gradle :app:assembleTmdbUiAcceptance :app:assembleTmdbUiAcceptanceAndroidTest -Pyingji.ai.directVerificationEnabled=true
.\gradlew.bat -I app/src/releaseAcceptanceTest/release-e2e.init.gradle :app:assembleReleaseAcceptance -Pyingji.ai.directVerificationEnabled=true
```

执行规则：

1. 先核对 Git、设备、原应用数据库及 Key 密文哈希，备份原测试 APK；确认独立包名起初不存在。
2. 手动测试必须精确选择命名方法，并传入 releaseApproved=release-e2e-one-model-task 和新的 32 位十六进制 releaseNonce。准备步骤本身不生成推荐。
3. 原包只执行 exportOriginalAsCiphertextOnly。主机只中转公钥和密文；installAndValidateInsideOwnedClone 只接受空的自建克隆包，完成后删除中转私钥。原测试 APK 随后恢复。
4. 结束仪器测试并卸载克隆测试 APK后，才原位更新克隆包为 Release。使用外部 UI 操作验证真正优化后的应用；不要为使旧 Compose 测试运行而放宽正式混淆规则。
5. 实网生成仅在已授权的单次确认后执行。执行者必须限制任务次数、记录已消费的 nonce，并核对用量，禁止因后续断言失败重复发送付费任务。用量快照不提供供应商账单或 API token 明细。
6. 负载夹具只允许当前克隆包恰有 3 条验收作品时建立，增加 30 张自生成灰度 PNG 和 30 条作品。 标签默认 Release验收标签，也可传入 releaseFixtureTagName 指定当前克隆包内已建立的验收标签（例如 ReleaseTag）。先验证真实 UI 新增/编辑，不能把直接插入夹具算作 UI 写入验收。vivo 设备的后台测试进程可能冻结；负载准备时保持验收包前台，发生超时应先检查正在运行的测试和夹具状态，禁止重放一次性写入；用独立只读核验确认数量、原记录和凭据后再继续。
7. MuMu 的飞行模式会使当前 ADB 连接不可用。应用网络失败验收可使用已保存基线的按包网络阻断，并始终恢复链和包规则；它验证的是连接超时，不等同物理断网检测。
8. 完成后核对原数据与凭据哈希、原测试 APK和网络状态；仅卸载本次自建的克隆包及其测试包，不得卸载或清除 com.yingji.app。设备故障期间未验证的清理不得声称完成。

本轮结果及限制见 artifacts/acceptance/2026-10-25-release-e2e.md；临时日志路径在该报告中。

手动记录器回归：`node --test app/src/releaseAcceptanceTest/harness-record.test.mjs`。多个定位辅助实例必须串行写入同一检查点文件，每次追加时重新读取现有记录，避免覆盖先前断言。
