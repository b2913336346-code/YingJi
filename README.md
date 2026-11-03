# 影迹 YingJi

影迹是一款基于 Kotlin 与 Jetpack Compose 的 Android 作品记录应用，帮助你整理想看、观看中和已看完的作品，管理分类、标签、评分与观看历史。支持列表与海报墙、AI 推荐和本地加密备份，采用黑白极简界面，支持浅色、深色及跟随系统主题。

## 主要功能

- **作品管理**：新增、编辑作品，记录评分、观看进度与历史；分类必选，标签可选。
- **分类与标签**：管理自定义分类和标签，按分类或标签浏览作品。
- **搜索与展示**：组合关键词、分类、标签、观看状态和时间筛选，支持多种排序、列表与海报墙及默认展示设置。
- **海报与统计**：选择本地海报、确认使用在线海报，查看作品统计。
- **AI 与 TMDB**：读取 TMDB 候选，使用自己的 DeepSeek Key 获取推荐，确认候选后添加作品。
- **备份与恢复**：导出加密 `.yjb` 文件，核验后恢复到符合条件的空资料库。

## 技术栈

- Kotlin、Jetpack Compose、Material 3、Navigation Compose
- ViewModel、Coroutines / Flow
- Room、DataStore Preferences、Android Keystore
- Gradle、JUnit 4、Compose UI Test

具体版本见 [`build.gradle.kts`](build.gradle.kts) 和 [`app/build.gradle.kts`](app/build.gradle.kts)。

## 快速开始

### 环境要求

- Android Studio、JDK 17
- Android SDK Platform 35、Platform-Tools
- Android 8.0（API 26）或更高版本的模拟器或真机

### 构建与运行

1. 使用 Android Studio 打开项目根目录，配置 SDK 路径并完成 Gradle 同步。本机配置 `local.properties` 不提交。
2. 在项目根目录构建 Debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

3. 选择目标设备，使用 Android Studio 的 Run，或执行安装命令：

```powershell
.\gradlew.bat :app:installDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。已有安装升级时需使用兼容签名，保留原有数据。

macOS / Linux 将 `.\gradlew.bat` 替换为 `sh ./gradlew`。项目自带 Gradle Wrapper，首次构建需要下载依赖；默认 Debug 构建无需启动后端。

## 测试

编译与单元测试：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

在专用测试设备或模拟器上运行 UI 和数据库仪器测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

专项验收说明见[测试文档](docs/testing/)与[开发手册](docs/README.md)。

## AI 与 TMDB 配置

本地作品管理无需 API 凭据。Debug 的 DeepSeek 链路默认使用 Mock；启用真实验证与推荐时使用：

```powershell
.\gradlew.bat :app:assembleDebug -Pyingji.ai.directVerificationEnabled=true
```

在“我的 → AI 模型配置”保存 DeepSeek Key，在“AI 管理 → TMDB 数据源配置”保存 TMDB 令牌。切换构建模式后，Key 需重新验证并保存。Mock 仅接受 `TEST_ONLY_BYOK_` 开头的测试 Key；TMDB 读取独立于模型开关。

真实推荐需确认画像摘要，费用由对应 DeepSeek 账户承担。凭据通过应用内入口配置，不写入源码或 Gradle 属性。

## 备份与恢复

入口为“我的 → 备份与恢复”。导出时设置 12–256 个字符的密码，并通过系统文件选择器保存 `.yjb` 文件；密码不保存，请自行保管。备份包含作品、分类、标签、观看记录、本地海报及相关偏好，不包含 API 凭据。恢复仅支持符合条件的空资料库，不支持覆盖或合并。个人备份、云盘与跨设备恢复仍未验证。

## 目录与文档

- [`app/`](app/)：Android 源码、测试、资源与 Room Schema。
- [`backend/catalog-gateway/`](backend/catalog-gateway/README.md)：目录网关及本地运行说明。
- [`backend/worker/`](backend/worker/README.md)：历史 Worker 回归夹具，无需部署。
- [`docs/`](docs/README.md)：需求、设计、开发手册与测试文档索引。
- [`design/`](design/README.md)：图标设计资源。
- [`scripts/`](scripts/)：发布、签名检查与设备网络辅助脚本。

参与开发前请阅读 [`AGENTS.md`](AGENTS.md)，遵守现有 UI 与数据库兼容规范。凭据、签名材料、个人数据库、备份文件和本地验收产物不提交到仓库。
