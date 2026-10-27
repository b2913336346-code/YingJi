# 异常修复与 B4 重跑验收（2026-10-26）

**结论：本轮 B4 通过。** 最终同一轮完整回归 **144/144**（143 个独立方法；重启 prepare/verify 同方法分两次执行），总数用例独立进程冷启动 **40/40**，JVM **167/167**。本次仅改动 1 个测试源文件；生产应用构建指纹、UI 和 Room Schema 保持不变。

## 1. 已完成功能

修复总数验收用例的同步与清理顺序，并完整重跑 B4。此前测量异常的闭环证据为修正后的原用例 40 次冷启动及本轮完整回归通过。

1. **作品新增、编辑、删除与同名作品：通过**。实际表单/详情页新增、编辑、取消、删除、失败删除重试，同名作品不同 ID；含三种 AI 故障下的实际基础 CRUD。 证据：[B4AiIsolationAcceptanceTest#configurationFailureDoesNotBlockActualBaseCrud](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-068-B4AiIsolationAcceptanceTest%23configurationFailureDoesNotBlockActualBase.log)、[B4AiIsolationAcceptanceTest#recommendationTimeoutDoesNotBlockActualBaseCrud](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-069-B4AiIsolationAcceptanceTest%23recommendationTimeoutDoesNotBlockActualBas.log)。

2. **分类、标签管理及关联：通过**。分类与标签同 ID 的独立定位、名称 trim/空白/重复、系统分类保护、失败重试、ID 与作品关联保留。 证据：[SettingsScreenTest#defaultSortSelectionDispatchesCallback](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-005-SettingsScreenTest%23defaultSortSelectionDispatchesCallback.log)、[SettingsScreenTest#categoryAndTagWithSameDatabaseIdCanBeDisplayed](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-006-SettingsScreenTest%23categoryAndTagWithSameDatabaseIdCanBeDisplayed.log)。

3. **搜索与组合筛选：通过**。中文/拼音/首字母，分类/标签/状态/时间组合筛选，日期确认取消及日区间边界。 证据：[SearchViewModelTest](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-021-SearchViewModelTest.log)、[SearchStage3ScreenTest#scrollingFiltersKeepsCombinedResultsClickableInListAndPosterWall](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-022-SearchStage3ScreenTest%23scrollingFiltersKeepsCombinedResultsClickableIn.log)。

4. **列表、海报墙、排序与默认展示入口：通过**。列表与三列海报墙共用结果，布局切换、长标题、缺失海报、排序点击与默认值保存失败反馈。 证据：[SettingsSortViewModelTest](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-003-SettingsSortViewModelTest.log)、[SettingsSortScreenTest#savedDefaultSortReflectsSelectionAndFailureKeepsPreviousChoice](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-004-SettingsSortScreenTest%23savedDefaultSortReflectsSelectionAndFailureKeep.log)。

5. **观看状态、记录与时间边界：通过**。开始/结束观看、重刷、时间编辑及非法日期拒绝、已有活动记录时阻止回想看；事务回滚与并发唯一活动记录。 证据：[WatchSessionConsistencyTest](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-056-WatchSessionConsistencyTest.log)、[AppDatabaseTest](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-064-AppDatabaseTest.log)。

6. **个人评分：通过**。可选 0—10、0.5 步进，0/0.5/10，非法评分拒绝、清除未评分、取消与重新读取。 证据：[RatingAndPosterScreenTest#ratingValidationRejectsInvalidValuesWithoutSaving](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-029-RatingAndPosterScreenTest%23ratingValidationRejectsInvalidValuesWithoutS.log)、[RatingAndPosterScreenTest#zeroHalfPointsAndTenAreSubmittedWithoutLosingSelectedCategory](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-030-RatingAndPosterScreenTest%23zeroHalfPointsAndTenAreSubmittedWithoutLosin.log)。

7. **海报文件选择、裁剪、本地保存与移除：通过**。真实系统选择器取消、受控图片选择、裁剪、2:3 文件保存及灰阶像素核查；移除、失败保留与重试。 证据：[MovieCrudAcceptanceTest#localPickerCancellationAndActualFileSelectionCropAndSaveUseTheIsolatedImage](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-028-MovieCrudAcceptanceTest%23localPickerCancellationAndActualFileSelectionC.log)、[RatingAndPosterScreenTest#removingPosterIsSubmittedOnSaveAndCanBeCancelled](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-032-RatingAndPosterScreenTest%23removingPosterIsSubmittedOnSaveAndCanBeCance.log)。

8. **我的页总数、分类/标签数量与统计：通过**。作品总数不随重刷重复计数；分类/标签数量与关联；实际我的→统计入口显示作品数、观看次数、重刷次数和时长。 证据：[SettingsScreenTest#themeStatisticsAndDialogCancelCallbacksRemainAvailable](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-010-SettingsScreenTest%23themeStatisticsAndDialogCancelCallbacksRemainAvaila.log)、[MineMovieCountTest#totalUsesUniqueMoviesAndRefreshesAfterInsertRewatchAndDelete](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-017-MineMovieCountTest%23totalUsesUniqueMoviesAndRefreshesAfterInsertRewatch.log)。

9. **重启持久化和默认设置恢复：通过**。真实不同进程 prepare/verify，作品/关系/记录/评分/海报字节及主题、默认展示、默认排序恢复。 证据：[ThemeRepositoryTest](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-057-ThemeRepositoryTest.log)、[WatchSessionRestartTest#sessionsAndPostersPersist](2026-10-26-b4-regression-layout-fix-b4-unified-final-restart-prepare.log)。

10. **数据库迁移与旧数据完整性：通过**。Room v5，旧 schema 1/2/3/4 升级保留业务数据与 AI 共享表，未来版本拒绝破坏性重建。 证据：[RatingMigrationTest](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-061-RatingMigrationTest.log)、[DatabasePreservationTest](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-062-DatabasePreservationTest.log)。

11. **异常提示、数据库保护与导出：通过**。写入失败提示、详情关联失败重试、受控主库/WAL/SHM 保全、备份失败不清除原件、系统导出取消及实际归档读回核查。 证据：[DatabaseProtectionExportTest#actualSystemDocumentPickerCancellationReturnsToProtectedScreen](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-050-DatabaseProtectionExportTest%23actualSystemDocumentPickerCancellationRet.log)、[DatabaseProtectionExportTest#actualSystemDocumentPickerSavesAnArchiveWhoseContentsCanBeReadBack](2026-10-26-b4-regression-layout-fix-b4-unified-final-test-051-DatabaseProtectionExportTest%23actualSystemDocumentPickerSavesAnArchiveW.log)。

完整用例映射见 [结果 JSON](2026-10-26-b4-layout-fix-results.json)，完成事件见 [同一轮原始队列](2026-10-26-b4-regression-layout-fix-b4-unified-final-queue.json)。未使用多轮通过项拼接结论。

## 2. 新增或修改的 UI 入口

无。沿用既有页面、按钮和测试定位。仅独立测试包的执行脚本启用空白前台宿主，不改变正式应用入口。

## 3. 修改文件清单

- [MineMovieCountTest.kt](../../app/src/androidTest/java/com/yingji/app/presentation/settings/MineMovieCountTest.kt)：以组合提交后的实际计数作为纯等待条件，将 Espresso 空闲同步放在轮询外，继续验证实际文字及可见性；数据库阻塞调用与 UI 操作分开；使用已有滚动容器 testTag；先停止 Room 观察再关闭测试数据库。

原用例的 `0→1→1→0`、重刷去重、截图成功及 5 秒超时全部保留。数据库测试源码试改已逐字节恢复，DAO、Repository、ViewModel、依赖及数据库结构均无改动。

调用链证据：[修复过程中捕获的完整异常链](2026-10-26-b4-regression-layout-fixed-cold-mine-34.log) 指向新增后的 `assertCount`，截图尚未开始；条件查询经 `fetchSemanticsNodes/getRoots` 进入 `Espresso.onIdle`，异常链包含 Android 测量重入。该日志和未通过的试改均保留。

## 4. 对原 UI 的影响

无生产 UI 变更。[修复后的总数页截图](2026-10-26-b4-layout-fix-mine-light.png) 与修复前 PNG 逐字节一致，仅代表该浅色总数页截图，不宣称全部页面像素一致。

## 5. 编译结果

Debug、Release 与独立验收 app/test 编译通过；JVM 重新运行通过。最终测试修正后重新编译测试 APK，再执行真机验证。见 [全量构建](2026-10-26-b4-regression-layout-fix-standard-build.log)、[最终总数修正编译](2026-10-26-b4-regression-layout-fix-composition-observer-build.log)、[恢复原始数据库测试后的编译](2026-10-26-b4-regression-layout-fix-restore-db-test-build.log)。

Room 仍为 v5，Schema SHA256 为 b3f7d27b9771fd0659b1c068437bc03c633f96d47420ae6a93fcfdcad9ea17bd。独立 app 的 APK 与修复前逐字节相同，仅测试 APK 改变；[构建指纹](2026-10-26-b4-layout-fix-build-manifest.json)。

## 6. 真实测试结果

- 环境：vivo S9 / V2072A 真机，Android 13 / SDK 33，序列号 3135805504003ED；未使用模拟器。
- 单元测试：167 通过、0 失败、0 错误、0 跳过；[XML 汇总](2026-10-26-b4-layout-fix-unit-results.json)。
- 总数冷启动：40/40；[原始队列](2026-10-26-b4-regression-layout-fix-final-cold-queue.json)。每次都重新运行完整原用例，包含真实 Room 写入、重刷、实际文本显示、截图和删除断言。
- 数据层预检：统一运行条件下 91/91；[队列](2026-10-26-b4-regression-layout-fix-data-foreground-queue.json)。此数据单列，不重复加进完整回归次数。
- 最终完整 B4：73 批次、144/144 执行通过，0 失败/未完成；包括新增编辑删除、分类标签、组合查询、观看时间、评分、本地海报、统计、设置恢复、1—4 到 v5 迁移、数据库保护和系统导出。
- 进程重启：prepare PID 26358，verify PID 26430，同一 UUID 夹具重启后验证；[PID 证据](2026-10-26-b4-layout-fix-final-restart-proof.json)。
- 实际系统选择器导出并读回 ZIP 通过；三份受控 ZIP 二次解压验证 manifest.json/fixture.db 后精确清理，fixture.db 是测试文本，不是正式数据库。

运行条件说明：此前无前台宿主的长数据批次出现停滞。保留原始失败日志，撤回无效源码试改；最终为耗时非 UI 批次提供独立包空白前台宿主启动支持，不在退出阶段反复拉起 Activity，批次超时仍为 100 秒。使用原有全部方法和断言，未跳过、延长超时或修改业务逻辑。运行配置保存在最终队列的 foregroundOnly 字段。

## 7. 未解决问题与未验证项

本阶段复现路径和最终 B4 没有未关闭阻断项。结论限于上述真机、代码与运行条件；未把无前台宿主长批次的运行方式标记为通过。

未执行/未验证：B5 海报边界专项、真实 DeepSeek 联网故障、用户正式数据库内容。AI 配置失败/超时/离线采用受控边界，基础 CRUD 通过。历史旧验收包消失原因仍未知，本阶段未操作该包或对原因下结论。

**冻结建议：B4 可验收。先完成独立 B5 海报边界专项，再确认最终冻结；本轮停在 B4。**

## 8. 是否符合 AGENTS.md

符合本阶段范围、UI 基线、测试断言、真机验证和数据库保护要求。所有失败尝试原样保留；最终结论依据完整成功的一轮。正式数据库与密钥未访问或修改。

[清理记录](2026-10-26-b4-layout-fix-cleanup.json)：只卸载本轮独立 app/test 两包，清理经内容核验的测试 ZIP；最终影迹包列表恢复为阶段前仅 com.yingji.app，正式 APK SHA256 始终 31d1d8e6325fd766be46bb6a480e81694f1216fd50207c059afbaf43ab9c3aab。该指纹证明安装包未变，不能据此推断数据库内容一致。

## 9. Git 变更摘要

本阶段测试源文件 1 个，**+29/-14 行**，另有本阶段日志、执行配置与验收证据。原有未提交/未跟踪改动保留，无提交、推送或远程分支创建。

[阶段独立差异](2026-10-26-b4-layout-fix-phase.patch)、[变更与断言清单](2026-10-26-b4-layout-fix-change-manifest.json)。差异基于本阶段开始时的文件快照，不将前一阶段修改重复计入。
