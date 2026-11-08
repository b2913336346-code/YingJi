[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tool-paths.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('yingji-tool-path-tests-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$results = New-Object Collections.Generic.List[object]
$names = @('JAVA_HOME','ANDROID_SDK_ROOT','ANDROID_HOME','ADB_PATH','YINGJI_RELEASE_KEYSTORE','PATH')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Clear-ToolEnvironment {
    foreach ($name in @('JAVA_HOME','ANDROID_SDK_ROOT','ANDROID_HOME','ADB_PATH','YINGJI_RELEASE_KEYSTORE')) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    $env:PATH = ''
}
function Check([string]$Name, [scriptblock]$Action) {
    Clear-ToolEnvironment
    & $Action
    $results.Add(@{name=$Name;status='passed'})
    Write-Host ('PASS ' + $Name)
}
function Equal($Actual, $Expected) { if ($Actual -cne $Expected) { throw ('Expected ' + $Expected + ', got ' + $Actual) } }
function Reject([scriptblock]$Action, [string]$Pattern) {
    $caught = $false
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw }
        $caught = $true
    }
    if (-not $caught) { throw ('Expected error: ' + $Pattern) }
}
try {
    $jdkA = Join-Path $testRoot 'jdk with spaces'
    $jdkB = Join-Path $testRoot 'other-jdk'
    $jre = Join-Path $testRoot 'incomplete-jre'
    foreach ($jdk in @($jdkA,$jdkB,$jre)) {
        [void][IO.Directory]::CreateDirectory((Join-Path $jdk 'bin'))
        [IO.File]::WriteAllText((Join-Path $jdk 'bin\java.exe'), '')
    }
    foreach ($jdk in @($jdkA,$jdkB)) { foreach ($tool in @('keytool','javac')) { [IO.File]::WriteAllText((Join-Path $jdk ('bin\'+$tool+'.exe')), '') } }
    $sdkA = Join-Path $testRoot 'sdk with spaces'
    $sdkB = Join-Path $testRoot 'other-sdk'
    foreach ($sdk in @($sdkA,$sdkB)) {
        [void][IO.Directory]::CreateDirectory((Join-Path $sdk 'platform-tools'))
        [IO.File]::WriteAllText((Join-Path $sdk 'platform-tools\adb.exe'), '')
    }
    $fixtureProject = Join-Path $testRoot 'project'
    $emptyProject = Join-Path $testRoot 'empty-project'
    [void][IO.Directory]::CreateDirectory($fixtureProject)
    [void][IO.Directory]::CreateDirectory($emptyProject)
    $escapedSdk = $sdkA.Replace('\','\\').Replace(':','\:')
    [IO.File]::WriteAllText((Join-Path $fixtureProject 'local.properties'), ('# SDK fixture' + [Environment]::NewLine + 'sdk.dir=' + $escapedSdk))
    $adbA = Join-Path $sdkA 'platform-tools\adb.exe'
    $adbB = Join-Path $sdkB 'platform-tools\adb.exe'
    Check 'All public scripts parse without executing their bodies' {
        foreach ($script in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
            $tokens=$null; $errors=$null
            $null=[Management.Automation.Language.Parser]::ParseFile($script.FullName,[ref]$tokens,[ref]$errors)
            if ($errors.Count) { throw ('Syntax errors in '+$script.Name) }
        }
    }
    Check 'Explicit JavaHome wins over JAVA_HOME and PATH' { $env:JAVA_HOME=$jdkB; $env:PATH=Join-Path $jdkB 'bin'; Equal (Resolve-YingJiJavaHome -JavaHome $jdkA) $jdkA }
    Check 'JAVA_HOME resolves a complete JDK' { $env:JAVA_HOME=$jdkA; Equal (Resolve-YingJiJavaHome) $jdkA }
    Check 'PATH resolves a JDK when JAVA_HOME is absent' { $env:PATH=Join-Path $jdkA 'bin'; Equal (Resolve-YingJiJavaHome) $jdkA }
    Check 'Missing JDK gives an actionable error' { Reject { Resolve-YingJiJavaHome } 'JDK_NOT_FOUND' }
    Check 'Invalid explicit JDK cannot silently fall back to JAVA_HOME' { $env:JAVA_HOME=$jdkA; Reject { Resolve-YingJiJavaHome -JavaHome $jre } 'JDK_TOOL_NOT_FOUND' }
    Check 'A JRE without keytool is rejected' { $env:JAVA_HOME=$jre; Reject { Resolve-YingJiJavaHome } 'keytool' }
    Check 'Signing tests require javac as well as runtime tools' { Equal (Resolve-YingJiJavaHome -JavaHome $jdkA -RequiredTools @('java','keytool','javac')) $jdkA }
    Check 'Explicit SDK wins over environment and local.properties' { $env:ANDROID_SDK_ROOT=$sdkB; Equal (Resolve-YingJiAndroidSdkRoot -AndroidSdkRoot $sdkA -ProjectRoot $fixtureProject) $sdkA }
    Check 'ANDROID_SDK_ROOT wins over ANDROID_HOME' { $env:ANDROID_SDK_ROOT=$sdkA; $env:ANDROID_HOME=$sdkB; Equal (Resolve-YingJiAndroidSdkRoot -ProjectRoot $emptyProject) $sdkA }
    Check 'ANDROID_HOME is supported' { $env:ANDROID_HOME=$sdkB; Equal (Resolve-YingJiAndroidSdkRoot -ProjectRoot $fixtureProject) $sdkB }
    Check 'Escaped local.properties SDK path and spaces are preserved' { Equal (Resolve-YingJiAndroidSdkRoot -ProjectRoot $fixtureProject) $sdkA }
    Check 'No SDK configuration gives an actionable error' { Reject { Resolve-YingJiAndroidSdkRoot -ProjectRoot $emptyProject } 'ANDROID_SDK_NOT_FOUND' }
    Check 'Optional SDK lookup permits missing configuration' { Equal (Resolve-YingJiAndroidSdkRoot -ProjectRoot $emptyProject -AllowMissing) $null }
    Check 'Invalid explicit SDK cannot silently use local.properties' { Reject { Resolve-YingJiAndroidSdkRoot -AndroidSdkRoot (Join-Path $testRoot 'missing-sdk') -ProjectRoot $fixtureProject } 'ANDROID_SDK_NOT_FOUND' }
    Check 'Explicit ADB wins over ADB_PATH and SDK' { $env:ADB_PATH=$adbB; $env:ANDROID_SDK_ROOT=$sdkB; Equal (Resolve-YingJiAdbPath -AdbPath $adbA -ProjectRoot $fixtureProject) $adbA }
    Check 'ADB_PATH wins over SDK lookup' { $env:ADB_PATH=$adbB; Equal (Resolve-YingJiAdbPath -ProjectRoot $fixtureProject) $adbB }
    Check 'SDK platform-tools resolves ADB without PATH' { $env:ANDROID_SDK_ROOT=$sdkA; Equal (Resolve-YingJiAdbPath -ProjectRoot $emptyProject) $adbA }
    Check 'Local SDK configuration still resolves ADB' { Equal (Resolve-YingJiAdbPath -ProjectRoot $fixtureProject) $adbA }
    Check 'PATH resolves ADB without an SDK configuration' { $env:PATH=Join-Path $sdkB 'platform-tools'; Equal (Resolve-YingJiAdbPath -ProjectRoot $emptyProject) $adbB }
    Check 'Invalid explicit ADB fails without using ADB_PATH' { $env:ADB_PATH=$adbA; Reject { Resolve-YingJiAdbPath -AdbPath (Join-Path $testRoot 'missing.exe') -ProjectRoot $fixtureProject } 'ADB_NOT_FOUND' }
    Check 'Missing ADB gives an actionable error' { Reject { Resolve-YingJiAdbPath -ProjectRoot $emptyProject } 'ADB_NOT_FOUND' }
    Check 'Network Status runs a resolved ADB path with spaces without changing device settings' {
        $fixtureRoot = Join-Path $testRoot 'network-project'
        $fixtureScripts = Join-Path $fixtureRoot 'scripts'
        $fakeTools = Join-Path $testRoot 'fake adb tools'
        [void][IO.Directory]::CreateDirectory($fixtureScripts)
        [void][IO.Directory]::CreateDirectory($fakeTools)
        foreach ($file in @('tmdb-usb-network.ps1','tool-paths.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $fixtureScripts $file) }
        $fakeAdb = Join-Path $fakeTools 'adb.exe'
        $source = @'
using System;
using System.IO;
class YingJiAdbFixture {
    public static int Main(string[] args) {
        string command = string.Join(" ", args);
        File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "commands.log"), command + "\n");
        if (command == "-s FIXTURE_DEVICE get-state") { Console.WriteLine("device"); return 0; }
        if (command == "-s FIXTURE_DEVICE reverse --list") { return 0; }
        if (command == "-s FIXTURE_DEVICE shell settings get global http_proxy") { Console.WriteLine("null"); return 0; }
        Console.Error.WriteLine("Unexpected command in Status fixture");
        return 9;
    }
}
'@
        Add-Type -TypeDefinition $source -Language CSharp -OutputAssembly $fakeAdb -OutputType ConsoleApplication
        $env:ADB_PATH = $fakeAdb
        $status = (& (Join-Path $fixtureScripts 'tmdb-usb-network.ps1') -Action Status -Serial FIXTURE_DEVICE) | ConvertFrom-Json
        Equal $status.serial 'FIXTURE_DEVICE'
        Equal $status.proxy 'null'
        if ($status.active -or $status.managed -or @($status.reverse).Count) { throw 'Unexpected managed state in Status fixture' }
        if (Test-Path -LiteralPath (Join-Path $fixtureRoot 'app\build\tmdb-usb-network\state.json')) { throw 'Status created a state file' }
        $commands = Get-Content -LiteralPath (Join-Path $fakeTools 'commands.log') -Raw
        if ($commands -match 'settings (put|delete)|reverse (--remove|tcp:)') { throw 'Status tried to modify device settings' }
    }
    Check 'Release cannot start without an explicit signing location' { Reject { & (Join-Path $PSScriptRoot 'build-release.ps1') } 'RELEASE_KEYSTORE_REQUIRED' }
    Check 'Environment-provided project-local signing material is rejected' { $env:YINGJI_RELEASE_KEYSTORE=Join-Path $projectRoot 'invalid.jks'; Reject { & (Join-Path $PSScriptRoot 'build-release.ps1') } 'outside the project' }
    Check 'Explicit project-local signing material overrides environment and is rejected' { $env:YINGJI_RELEASE_KEYSTORE=Join-Path $testRoot 'unused.jks'; Reject { & (Join-Path $PSScriptRoot 'build-release.ps1') -KeyStore (Join-Path $projectRoot 'invalid.jks') } 'outside the project' }
    @{status='passed';tests=$results;fixtureOnly=$true;testRoot=$testRoot} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $testRoot 'results.json') -Encoding UTF8
    Write-Host ('Test results: '+(Join-Path $testRoot 'results.json'))
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
