# 基础影迹冻结记录

> 仓库整理说明：本文件保留历史验收结论。`artifacts/` 原始证据已改为仅本地保留，不随仓库上传；下文证据路径需在保存原文件的本机查阅，详见[文档索引](../../README.md)。

**状态：基础应用验收基线已冻结。** 基线标识：yingji-base-2026-10-26；验收日期：2026-10-26；本次归档日期：2026-10-27。版本保持 3.0.0（versionCode 3），Room 保持 v5。

冻结提交由包含本文件的 Git 提交确定，见 git log --oneline -- docs/freeze/2026-10-26-base/README.md。提交哈希不写入其自身内容，以避免循环依赖；推送是否成功以远端核验回执为准。

## 1. 已完成功能

冻结现有基础作品管理、分类标签、搜索筛选、观看状态/时间、评分、本地海报、设置统计、迁移/保护，以及 B5 规定的 AI 海报边界。保持当前 UI 与数据库业务规则。本次没有新增业务功能或执行 AI 后续开发。

冻结保存当前 APK 构建所需共享代码，包含已存在的 AI 来源、配置及海报接线，以保证版本可构建；这不代表所有 AI 功能已完成专项验收。[源码基线清单](source-baseline.json) 保存 323 个输入的本机 SHA256 和 Git blob 标识，跨平台换行核对优先使用 Git blob。

## 2. 新增或修改的 UI 入口

无。当前页面结构、顶部/底部导航、字体、主题、间距和交互入口保持原样。

## 3. 修改文件清单

本次新增本目录的冻结说明、源码/证据清单、B4/B5 验收构建配置副本，以及 artifacts/freeze/2026-10-26-base 下的构建与核查证据。业务源码、测试断言、Schema、应用版本未修改。

提交同时收录此前已经实现并验收的代码和必要证据，避免仅提交文档而使新检出版本缺少源文件。README、.gitignore 的既有修改、旧文档删除、后端、发布脚本、未参与本次验收的实网/发布专项源集和其余历史资料保留在原工作区，不加入冻结提交。

## 4. 对原 UI 的影响

无新 UI 改动。沿用 B4/B5 的真机交互及截图证据；本冻结步骤没有重新安装或操作手机。

## 5. 编译结果

本步骤重新执行 Debug、Release 构建和 JVM 测试：99 个任务重新执行，构建成功。见 冻结构建日志（本地 `artifacts/freeze/2026-10-26-base/build.log`）、本机构建产物指纹（本地 `artifacts/freeze/2026-10-26-base/build-manifest.json`）。Release 产物为 unsigned APK，仅记录指纹，不作为发布包；APK、签名材料、local.properties 和用户数据库不提交。

基础复核命令（JDK 17、Android SDK 35）：

~~~powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
~~~

B4/B5 独立验收构建入口从忽略目录归档为本目录文件。重建前恢复各自的标签 Manifest：

~~~powershell
New-Item -ItemType Directory -Force app/build/b4-regression
Copy-Item docs/freeze/2026-10-26-base/b4-AndroidManifest.xml app/build/b4-regression/AndroidManifest.xml
.\gradlew.bat -I docs/freeze/2026-10-26-base/b4-acceptance.init.gradle :app:assembleB4RegressionAcceptance :app:assembleB4RegressionAcceptanceAndroidTest
New-Item -ItemType Directory -Force app/build/b5-poster-boundary
Copy-Item docs/freeze/2026-10-26-base/b5-AndroidManifest.xml app/build/b5-poster-boundary/AndroidManifest.xml
.\gradlew.bat -I docs/freeze/2026-10-26-base/b5-acceptance.init.gradle :app:assembleB5PosterAcceptance :app:assembleB5PosterAcceptanceAndroidTest
~~~

## 6. 测试结果

- 本冻结步骤 JVM：167/167，0 失败、0 错误、0 跳过；结果汇总（本地 `artifacts/freeze/2026-10-26-base/unit-results.json`）。
- B4：最终同一轮 144/144，总数原用例冷启动 40/40，11 项验收通过；完整报告（本地 `artifacts/acceptance/2026-10-26-b4-layout-fix-acceptance.md`）。
- B5：最终同一轮 79/79，11 项海报边界通过；完整报告（本地 `artifacts/acceptance/2026-10-26-b5-poster-boundary-acceptance.md`）。
- B4/B5 使用 vivo S9 / V2072A 真机。原始失败尝试保留；通过结论限于报告记载的独立包和前台宿主运行条件，未拼接不同轮次结果。

[验收证据清单](acceptance-evidence.json) 收录 354 个文件的指纹与 Git blob；包括原始队列、日志、XML、截图和失败记录。暂存提交前检查本阶段输入指纹一致，并扫描高置信凭据模式，无命中；此扫描不声称能发现所有形式的敏感信息。

## 7. 未解决问题与未验证项

B4/B5 验收范围内没有未关闭 P0/P1 阻断项。真实 TMDB/DeepSeek 在线可用性、真实断网、真实磁盘耗尽及正式用户数据库内容仍未验证；相关异常使用 Mock/故障注入，正式数据库未访问。

其他启动条件下曾出现验收进程启动超时，底层系统原因未证实，未将其标为通过。历史验收包消失原因未确定，不推断为人工卸载或本次操作。

后续修改冻结范围内代码、Schema 或业务行为时，应建立新的修改记录并重新运行相关回归。当前冻结不启动其他开发阶段。

## 8. 是否符合 AGENTS.md

符合。阶段开始检查 Git，保留全部既有修改；无 UI/数据库业务改动、无破坏性命令、无签名发布。用户已明确授权本次 Git 提交和推送，执行普通提交及非强制推送。Git 连接保持 TLS 证书校验，不修改全局代理或证书策略。

## 9. Git 变更摘要

本步骤仅新增冻结资料，源码运行行为没有变化。冻结提交还包含此前相对旧 HEAD 的已验收实现及本 APK 所需共享代码；不把这些历史差异计为本步骤新功能。冻结前 HEAD 为 d5fa94fac7560a6a9fb371b36ae2693edcc79bfa，当前分支 main，目标 origin/main。

以最终暂存清单与提交差异为准；无关工作区改动保持未提交。成功推送后核对远端 main 与本地冻结提交一致，再结束本次工作。
