# B5 基础作品库与 AI 海报边界验收（2026-10-26）

**结论：B5 通过，未发现需要补改的业务功能。** 最终同一轮完整真机执行 **79/79**，42 批次、77 个独立方法；两组重启方法各执行 prepare/verify，因此总执行数多于方法数。单元测试 **167/167**。本阶段为只读验收，未改动生产源码、测试源码、UI、数据库结构或项目构建配置。

## 1. 已完成功能

按 [V3.1 手册 B5](../../docs/ai-v3/基础影迹开发手册V3.1.md) 核对现有实现，使用受控图片、协议 Mock、Room 夹具和故障注入验证以下边界；不调用真实 TMDB/DeepSeek 服务，不读取用户 Key。

1. **基础作品库继续使用本地海报：通过**。证据：[RemotePosterDetailScreenTest#anOrdinaryWorkDoesNotGainTheTmdbEntryOrStartAnyRequest](2026-10-26-b5-poster-boundary-unified-RemotePosterDetailScreenTest-anOrdinaryWorkDoesNotGainTheTmdbEntryOrStartAnyRequest.log)、[RemotePosterNavigationTest#ordinaryWorkKeepsActualDetailLayoutWithoutATmdbEntry](2026-10-26-b5-poster-boundary-unified-RemotePosterNavigationTest-ordinaryWorkKeepsActualDetailLayoutWithoutATmdbEntry.log)。

2. **推荐在线预览不落盘：通过**。证据：[TmdbPosterPreviewLoaderTest#officialHttpsPreviewDecodesInMemoryWithoutCredentialsCacheOrLocalFileWrites](2026-10-26-b5-poster-boundary-unified-TmdbPosterPreviewLoaderTest.log)、[TmdbPosterPreviewScreenTest#loadingThenDecodedPosterKeepsTheExistingAddActionInLightAndDarkThemes](2026-10-26-b5-poster-boundary-unified-TmdbPosterPreviewScreenTest-loadingThenDecodedPosterKeepsTheExistingAddActionInLightAndDarkThemes.log)。

3. **加入作品不自动下载海报：通过**。证据：[TmdbConfirmedImportTest#oneTypedDetailPerSaveUsesExistingTransactionAndPreservesLocalRelations](2026-10-26-b5-poster-boundary-unified-TmdbConfirmedImportTest.log)、[TmdbImportScreenTest#categoryIsRequiredAndConfirmedSavePreservesSelectedCategoryAndTagWithOnlyNamePrefilled](2026-10-26-b5-poster-boundary-unified-TmdbImportScreenTest-categoryIsRequiredAndConfirmedSavePreservesSelectedCategoryAndTagWithOnlyNamePrefilled.log)。

4. **作品存在且用户主动确认后才保存：通过**。证据：[RemotePosterSaveTest#noConfirmationMissingMovieOrMissingTmdbMappingNeverDownloadsOrWrites](2026-10-26-b5-poster-boundary-unified-RemotePosterSaveTest.log)、[RemotePosterDetailScreenTest#openingOnlyPreviewsInMemoryAndCancelPreservesTheOldImageAndRecord](2026-10-26-b5-poster-boundary-unified-RemotePosterDetailScreenTest-openingOnlyPreviewsInMemoryAndCancelPreservesTheOldImageAndRecord.log)、[RemotePosterDetailScreenTest#savingDisablesRepeatedConfirmationAndCannotStartAnotherDownload](2026-10-26-b5-poster-boundary-unified-RemotePosterDetailScreenTest-savingDisablesRepeatedConfirmationAndCannotStartAnotherDownload.log)。

5. **成功留在详情、立即显示本地海报并提示：通过**。证据：[RemotePosterDetailScreenTest#successfulConfirmationImmediatelyShowsTheLocalImageAndKeepsDetailAndRelations](2026-10-26-b5-poster-boundary-unified-RemotePosterDetailScreenTest-successfulConfirmationImmediatelyShowsTheLocalImageAndKeepsDetailAndRelations.log)、[RemotePosterNavigationTest#confirmedSaveStaysInActualDetailAndSurvivesBackAndReopeningWithoutAutomaticReplacement](2026-10-26-b5-poster-boundary-unified-RemotePosterNavigationTest-confirmedSaveStaysInActualDetailAndSurvivesBackAndReopeningWithoutAutomaticReplacement.log)。

6. **下载失败不影响作品和旧海报：通过**。证据：[RemotePosterSaveTest#networkFailurePreservesTheWholeOriginalRecordAndItsFile](2026-10-26-b5-poster-boundary-unified-RemotePosterSaveTest.log)、[RemotePosterDetailScreenTest#imageDownloadFailureKeepsTheOldImageAndCanBeRetried](2026-10-26-b5-poster-boundary-unified-RemotePosterDetailScreenTest-imageDownloadFailureKeepsTheOldImageAndCanBeRetried.log)。

7. **重开和进程重启不自动下载或替换本地海报：通过**。证据：[RemotePosterNavigationTest#confirmedSaveStaysInActualDetailAndSurvivesBackAndReopeningWithoutAutomaticReplacement](2026-10-26-b5-poster-boundary-unified-RemotePosterNavigationTest-confirmedSaveStaysInActualDetailAndSurvivesBackAndReopeningWithoutAutomaticReplacement.log)、[RemotePosterRestartTest#localPosterAndRelationsSurviveARealProcessRestartWithoutAnAutomaticDownload](2026-10-26-b5-poster-boundary-unified-restart-data-prepare.log)、[RemotePosterNavigationRestartTest#savedLocalPosterRemainsVisibleAfterARealApplicationProcessRestart](2026-10-26-b5-poster-boundary-unified-restart-ui-prepare.log)。

8. **在线元数据与本地文件引用分离：通过**。证据：[RemotePosterSaveTest#successChangesOnlyThePosterAndRemainsValidAfterReopeningTheDatabase](2026-10-26-b5-poster-boundary-unified-RemotePosterSaveTest.log)、[TmdbConfirmedImportTest#oneTypedDetailPerSaveUsesExistingTransactionAndPreservesLocalRelations](2026-10-26-b5-poster-boundary-unified-TmdbConfirmedImportTest.log)。

9. **临时文件校验后发布，失败不留错误引用：通过**。证据：[RemotePosterFileStoreTest#publisherSeesOnlyAValidatedTemporaryFileBeforeFinalPublication](2026-10-26-b5-poster-boundary-unified-RemotePosterFileStoreTest.log)、[RemotePosterFileStoreTest#failedPublicationRemovesOnlyItsTemporaryAndNewFinalFiles](2026-10-26-b5-poster-boundary-unified-RemotePosterFileStoreTest.log)、[RemotePosterSaveTest#cancellationAfterFilePublicationCleansTheNewFileBeforeAnyDatabaseWrite](2026-10-26-b5-poster-boundary-unified-RemotePosterSaveTest.log)。

10. **数据库更新失败保留旧引用和旧文件，可重试：通过**。证据：[RemotePosterSaveTest#failedDatabaseUpdateRemovesOnlyTheNewFileAndCanRetry](2026-10-26-b5-poster-boundary-unified-RemotePosterSaveTest.log)、[RemotePosterDetailScreenTest#failedDatabaseWriteHasNoFalseSuccessAndRetainsOriginalDataUntilRetry](2026-10-26-b5-poster-boundary-unified-RemotePosterDetailScreenTest-failedDatabaseWriteHasNoFalseSuccessAndRetainsOriginalDataUntilRetry.log)。

11. **共享引用和目录外文件保护：通过**。证据：[RemotePosterSaveTest#aSharedOldPosterSurvivesUntilTheLastReferenceIsReplaced](2026-10-26-b5-poster-boundary-unified-RemotePosterSaveTest.log)、[RemotePosterFileStoreTest#managedCleanupCannotDeleteAnOutsideFile](2026-10-26-b5-poster-boundary-unified-RemotePosterFileStoreTest.log)、[RemotePosterSaveTest#aConcurrentPosterChangeIsNotOverwrittenAndOnlyOurNewFileIsDiscarded](2026-10-26-b5-poster-boundary-unified-RemotePosterSaveTest.log)。

完整方法映射见 [结果 JSON](2026-10-26-b5-poster-boundary-results.json) 和 [逐项证据](2026-10-26-b5-poster-boundary-final-areas.json)。最终结论依据 [同一轮原始队列](2026-10-26-b5-poster-boundary-unified-queue.json)，未拼接不同轮次的通过项。

## 2. 新增或修改的 UI 入口

无。沿用现有推荐候选、共用作品表单、详情页“使用此海报”和确认弹窗。仅建立独立包“影迹B5海报验收”用于测试，验收结束后已卸载。

## 3. 修改文件清单

生产源码和测试源码 **0 个修改**。仅新增本阶段验收日志、执行配置、XML、截图和报告；临时构建入口在忽略目录 app/build/b5-poster-boundary，未修改 app/build.gradle.kts。

源码审查覆盖本地 LocalPoster、MemoryOnlyTmdbPosterLoader、TmdbImportUseCase、MovieViewModel、RemotePosterControls/ViewModel、SaveRemotePosterUseCase、PosterRepository、MovieRepository 及生产导航接线。推荐在线地址属于内存 TmdbPoster；表单不预填远程海报，持久化层不接受在线 URL。保存经临时文件写入、同步与解码校验，再发布文件并更新海报列；旧文件仅在受保护的引用检查通过后删除。

[30 个范围内文件的前后指纹](2026-10-26-b5-poster-boundary-source-verification.json) 一致，其中包含 Room v5 Schema 与项目构建文件；无 Schema/Migration 变更。

## 4. 对原 UI 的影响

无生产 UI 变更。保留既有风格和布局，真机截图已归档：[保存前](2026-10-26-b5-poster-boundary-before-light.png)、[确认弹窗](2026-10-26-b5-poster-boundary-confirm-light.png)、[保存后浅色](2026-10-26-b5-poster-boundary-after-light.png)、[保存后深色](2026-10-26-b5-poster-boundary-after-dark.png)、[候选在线预览](2026-10-26-b5-poster-boundary-poster-preview-light.png)。自动化额外断言实际海报区域像素、详情页可见性和成功提示；截图中的灰色图片是受控测试图片。

## 5. 编译结果

Debug、Release、独立验收 app/test 均通过；全量重新执行 169 个构建任务。见 [构建日志](2026-10-26-b5-poster-boundary-build.log)、[APK 与 Schema 指纹](2026-10-26-b5-poster-boundary-build-manifest.json)。Room 保持 v5。

## 6. 真实测试结果

- 环境：vivo S9 / V2072A，Android 13 / SDK 33，序列号 3135805504003ED；只使用真机。
- JVM：167 通过，0 失败、0 错误、0 跳过；[XML 汇总](2026-10-26-b5-poster-boundary-unit-results.json)。
- 最终完整 B5：79/79 通过，42/42 批次通过，0 未完成；[原始队列](2026-10-26-b5-poster-boundary-unified-queue.json)。
- 重启：数据层和实际导航各 prepare/verify 通过。详情页夹具进程 PID 为 14609 → 14799，并执行“与保存进程不同”的原有断言；保存的本地海报重新显示，下载计数为 0。数据层快速方法未捕获 PID，依据每次启动前只对独立包 force-stop 的进程边界；不虚构 PID。见 [重启证据](2026-10-26-b5-poster-boundary-restart-proof.json)。
- 覆盖取消、空海报、非法图片/响应、重复点击、并发修改/删除、数据库拒绝写入、失败重试、临时文件清理、共享引用和目录外文件保护。

执行器异常如实保留：首轮 77/79 通过，数据重启 prepare 在测试开始前启动超时，verify 缺少准备数据；[首轮记录](2026-10-26-b5-poster-boundary-first-queue.json)。第二轮 78/79 通过，另一次 UI 调用在测试开始前启动超时；[第二轮记录](2026-10-26-b5-poster-boundary-final-queue.json)。这些轮次均未标为通过。

调整仅限验收脚本：每批调用 instrumentation 前先启动独立包空白 ComponentActivity，检测到终止标记后停止启动辅助。随后用新 UUID 夹具完整重跑 79 次并通过。保留全部原测试和核心断言，批次超时仍为 100 秒；未增加自动重试、固定等待以掩盖失败或合并多轮结果。见 [最终运行配置](2026-10-26-b5-poster-boundary-unified-config.json)、[执行器](2026-10-26-b5-poster-boundary-runner-v2.js)。启动超时的底层系统原因未证实，结论限于最终统一运行条件。

## 7. 未解决问题与未验证项

本阶段最终运行条件下无未关闭 P0/P1 阻断项，无需新增业务功能或修改现有实现。

未验证：真实 TMDB/DeepSeek 在线可用性、真实断网和手机磁盘耗尽；本阶段按 B5 要求使用 Mock/故障注入。用户正式数据库内容未访问或核对。未把其他启动条件下的批次稳定性标为通过。

**建议：B4 与本阶段 B5 可作为基础应用冻结的验收依据。当前停止在 B5；未自动执行最终冻结、提交、发布或下一阶段。**

## 8. 是否符合 AGENTS.md

符合只读范围、UI 基线、真实测试、Git 控制和数据库保护要求。未访问或修改正式数据库、密钥及网络/VPN 配置，未实现恢复逻辑。

[清理记录](2026-10-26-b5-poster-boundary-cleanup.json)：核对已安装 APK 与本轮构建指纹后，仅卸载本轮独立 app/test 两包；其私有测试夹具随包清理。包列表恢复为开始时仅 com.yingji.app。正式 APK SHA256 前后均为 31d1d8e6325fd766be46bb6a480e81694f1216fd50207c059afbaf43ab9c3aab；此项仅证明安装包未变，不能据此推断数据库内容一致。

## 9. Git 变更摘要

阶段开始已执行 git status。生产与测试源码差异均为 **0**；新增范围仅本阶段验收证据。保留原有全部未提交/未跟踪内容，无提交、推送或分支创建。详见 [Git 变更核对](2026-10-26-b5-poster-boundary-git-summary.json)。
