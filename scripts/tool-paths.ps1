# Shared tool discovery only. Importing this file does not start tools or change settings.
function Get-YingJiApplicationPath([string]$Name) {
    $command = Get-Command -Name $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    return $null
}

function Resolve-YingJiJavaHome {
    param([string]$JavaHome, [string[]]$RequiredTools = @('java', 'keytool'))
    $candidate = $JavaHome
    if (-not $candidate) { $candidate = $env:JAVA_HOME }
    if (-not $candidate) {
        $java = Get-YingJiApplicationPath 'java.exe'
        if ($java) { $candidate = Split-Path -Parent (Split-Path -Parent $java) }
    }
    if (-not $candidate) { throw 'JDK_NOT_FOUND: pass -JavaHome, set JAVA_HOME, or add a JDK bin folder to PATH.' }
    $candidate = [IO.Path]::GetFullPath($candidate)
    foreach ($tool in $RequiredTools) {
        if (-not (Test-Path -LiteralPath (Join-Path $candidate ('bin\' + $tool + '.exe')) -PathType Leaf)) {
            throw ('JDK_TOOL_NOT_FOUND: ' + $tool + '.exe. Pass -JavaHome pointing to a complete JDK.')
        }
    }
    return $candidate
}

function Resolve-YingJiAndroidSdkRoot {
    param([string]$AndroidSdkRoot, [string]$ProjectRoot, [switch]$AllowMissing)
    $candidate = $AndroidSdkRoot
    if (-not $candidate) { $candidate = $env:ANDROID_SDK_ROOT }
    if (-not $candidate) { $candidate = $env:ANDROID_HOME }
    if (-not $candidate -and $ProjectRoot) {
        $properties = Join-Path $ProjectRoot 'local.properties'
        if (Test-Path -LiteralPath $properties -PathType Leaf) {
            $sdkLine = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
            if ($sdkLine) { $candidate = ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Replace('\:', ':').Replace('\\', '\') }
        }
    }
    if (-not $candidate) {
        if ($AllowMissing) { return $null }
        throw 'ANDROID_SDK_NOT_FOUND: pass -AndroidSdkRoot, set ANDROID_SDK_ROOT/ANDROID_HOME, or configure sdk.dir in local.properties.'
    }
    $candidate = [IO.Path]::GetFullPath($candidate)
    if (-not (Test-Path -LiteralPath $candidate -PathType Container)) { throw 'ANDROID_SDK_NOT_FOUND: the configured SDK folder does not exist.' }
    return $candidate
}

function Resolve-YingJiAdbPath {
    param([string]$AdbPath, [string]$ProjectRoot)
    $candidate = $AdbPath
    if (-not $candidate) { $candidate = $env:ADB_PATH }
    if ($candidate) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw 'ADB_NOT_FOUND: pass -AdbPath pointing to adb.exe, or correct ADB_PATH.' }
        return [IO.Path]::GetFullPath($candidate)
    }
    $sdk = Resolve-YingJiAndroidSdkRoot -ProjectRoot $ProjectRoot -AllowMissing
    if ($sdk) {
        $candidate = Join-Path $sdk 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return [IO.Path]::GetFullPath($candidate) }
    }
    $candidate = Get-YingJiApplicationPath 'adb.exe'
    if ($candidate) { return $candidate }
    throw 'ADB_NOT_FOUND: pass -AdbPath, set ADB_PATH, install SDK platform-tools, or add adb.exe to PATH.'
}
