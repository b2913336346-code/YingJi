# 脚本使用说明

在项目根目录使用 Windows PowerShell 5.1 运行。路径含空格时请加引号。

## 工具路径查找顺序

- JDK：`-JavaHome` → `JAVA_HOME` → PATH 中的 `java.exe` 所属 JDK；发布需要 `java`、`keytool`，签名测试还需要 `javac`。
- Android SDK：`-AndroidSdkRoot`（发布脚本）→ `ANDROID_SDK_ROOT` → `ANDROID_HOME` → 项目 `local.properties` 的 `sdk.dir`。
- ADB：`-AdbPath` → `ADB_PATH` → 上述 SDK 的 `platform-tools/adb.exe` → PATH 中的 `adb.exe`。
- 发布签名文件：`-KeyStore` → `YINGJI_RELEASE_KEYSTORE`。必须指向项目外的位置，缺少配置时直接报错。

显式配置的 JDK、SDK 或 ADB 路径无效时会报错，便于修正配置；不会悄悄使用另一个显式配置。`tool-paths.ps1` 只负责查找，不下载工具或修改系统环境。

## 发布构建

指定已有签名文件后运行；下列环境变量应在本机单独配置，勿把真实签名路径或密码写入仓库：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1 -KeyStore $env:YINGJI_RELEASE_KEYSTORE
```

也可显式传入 `-JavaHome` 和 `-AndroidSdkRoot`。首次创建签名仍须显式使用 `-InitializeSigning`，并指定项目外的主签名位置和备份目录。原有密码隐藏输入、备份校验、证书固定及禁止覆盖签名文件的检查继续生效。密码不通过脚本参数或持久化环境变量传入。

## 签名回归测试

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-release-signing.ps1
```

测试使用临时目录和临时密码，包含完整 Release 构建、JVM 单元测试、lint、临时签名文件生成与复用、备份和证书核验；不会使用正式签名文件或安装、发布 APK。可用 `-JavaHome` 指定测试 JDK。

## 路径查找测试

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-tool-paths.ps1
```

使用临时工具文件验证优先级、空格路径、配置缺失及失败反馈，并用编译出的本地 ADB 替身验证 `Status` 调用链；不连接手机、不修改代理、不调用模型服务。

## 设备网络辅助

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\tmdb-usb-network.ps1 -Action Status
```

支持已有的 `-AdbPath`、`-Serial`、`-HostProxyPort` 和 `-DeviceProxyPort` 参数。`Enable` / `Disable` 仍会操作所选设备的系统 HTTP 代理及受管 ADB reverse 转发，具体行为沿用脚本现有实现；仅查询状态时使用 `Status`。
