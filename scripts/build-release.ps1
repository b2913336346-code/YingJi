# Run from a local PowerShell console. Passwords are never command-line arguments or files.
[CmdletBinding()]
param(
    [switch]$InitializeSigning,
    [string]$KeyStore = 'E:\AIPM\Signing\YingJi\yingji-release.jks',
    [string]$BackupDirectory,
    [string]$JavaHome = 'C:\Program Files\Android\openjdk\jdk-21.0.8',
    [string]$AndroidSdkRoot
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$keyAlias = 'yingji-release'

function Assert-OutsideProject([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if ($full.Equals($projectRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $full.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Signing material and its backup must be outside the project.'
    }
    return $full
}
function Quote-Argument([string]$Value) {
    # Windows CommandLineToArgvW quoting, including paths ending with a backslash.
    return '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}
function Invoke-Tool {
    param([string]$Executable, [string[]]$Arguments, [Security.SecureString]$Secret)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $Executable
    $info.Arguments = ($Arguments | ForEach-Object { Quote-Argument $_ }) -join ' '
    $info.WorkingDirectory = $projectRoot
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardInput = $true
    $ptr = [IntPtr]::Zero
    $child = New-Object Diagnostics.Process
    $child.StartInfo = $info
    try {
        if ($null -ne $Secret) {
            $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
            $info.EnvironmentVariables['YINGJI_SIGNING_PASSWORD'] = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
        }
        [void]$child.Start()
        $info.EnvironmentVariables.Remove('YINGJI_SIGNING_PASSWORD')
        $child.StandardInput.Close()
        $stdout = $child.StandardOutput.ReadToEndAsync()
        $stderr = $child.StandardError.ReadToEndAsync()
        $child.WaitForExit()
        $out = $stdout.GetAwaiter().GetResult()
        $err = $stderr.GetAwaiter().GetResult()
        if ($child.ExitCode -ne 0) {
            # Do not echo tool output from password-bearing invocations.
            if ($null -ne $Secret) { throw ('Signing tool failed (exit ' + $child.ExitCode + '). Check password, alias and key file locally.') }
            throw ($Executable + ' failed: ' + $err + $out)
        }
        return $out + $err
    } finally {
        $info.EnvironmentVariables.Remove('YINGJI_SIGNING_PASSWORD')
        if ($ptr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
        $child.Dispose()
    }
}
function New-PrivateDirectory([string]$Path) {
    if (Test-Path -LiteralPath $Path) { throw 'Refusing to replace an existing signing directory.' }
    [void][IO.Directory]::CreateDirectory($Path)
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($owner)
    foreach ($sid in @($owner, (New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))) {
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        [void]$acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Get-Sha256([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Resolve-BackupDirectory([string]$Candidate, [string]$PrimaryDirectory) {
    if (-not $Candidate) { throw 'Enter a complete existing folder path, not the signing password.' }
    $candidatePath = $Candidate.Trim().Trim('"')
    if ($candidatePath -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\]+\\[^\\]+)') {
        throw 'A full folder path is required (for example C:\Users\29133\YingJiSigningBackup). Do not enter a password here.'
    }
    $backupFull = Assert-OutsideProject $candidatePath
    if ($backupFull.Length -gt [IO.Path]::GetPathRoot($backupFull).Length) { $backupFull = $backupFull.TrimEnd([IO.Path]::DirectorySeparatorChar) }
    $primaryFull = [IO.Path]::GetFullPath($PrimaryDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if ($backupFull.Equals($primaryFull, [StringComparison]::OrdinalIgnoreCase) -or $backupFull.StartsWith($primaryFull + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Choose a backup folder outside the primary key folder.'
    }
    if (-not (Test-Path -LiteralPath $backupFull -PathType Container)) { throw 'That folder does not exist. Create or select an existing backup folder.' }
    return $backupFull
}
function Read-BackupDirectory([string]$PrimaryDirectory) {
    Write-Host 'The next input is a FOLDER PATH, not a password. It will be visible.'
    while ($true) {
        $candidatePath = Read-Host 'Backup folder full path (example C:\Users\29133\YingJiSigningBackup)'
        try { return (Resolve-BackupDirectory $candidatePath $PrimaryDirectory) }
        catch { Write-Host $_.Exception.Message }
    }
}

function Read-SigningPassword([switch]$Confirm) {
    while ($true) {
        $value = Read-Host 'Signing password (at least 12 characters; keep it separately from the key backup)' -AsSecureString
        if ($value.Length -lt 12) {
            $value.Dispose()
            Write-Host 'Password too short. Please enter at least 12 characters; input is hidden.'
            continue
        }
        if ($Confirm) {
            $again = Read-Host 'Repeat signing password' -AsSecureString
            $a = [IntPtr]::Zero; $b = [IntPtr]::Zero
            $passwordsMatch = $false
            try {
                $a = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
                $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($again)
                $passwordsMatch = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($a) -ceq [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b)
            } finally {
                if ($a -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($a) }
                if ($b -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
                $again.Dispose()
            }
            if (-not $passwordsMatch) {
                $value.Dispose()
                Write-Host 'Passwords do not match. Please enter and repeat the password again.'
                continue
            }
        }
        return $value
    }
}

$secret = $null
$runDirectory = $null
try {
    $KeyStore = Assert-OutsideProject $KeyStore
    $keyDirectory = Split-Path -Parent $KeyStore
    $pinPath = Join-Path $keyDirectory 'yingji-release-cert.sha256'
    $java = Join-Path $JavaHome 'bin\java.exe'
    $keytool = Join-Path $JavaHome 'bin\keytool.exe'
    if (-not $AndroidSdkRoot) {
        $sdkLine = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
        if (-not $sdkLine) { throw 'Pass -AndroidSdkRoot or configure sdk.dir in local.properties.' }
        $AndroidSdkRoot = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
    }
    $tools = Join-Path $AndroidSdkRoot 'build-tools\34.0.0'
    $signer = Join-Path $tools 'lib\apksigner.jar'
    $aapt = Join-Path $tools 'aapt2.exe'
    $align = Join-Path $tools 'zipalign.exe'
    foreach ($required in @($java, $keytool, $signer, $aapt, $align)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw ('Missing tool: ' + $required) }
    }
    if ($InitializeSigning -and (Test-Path -LiteralPath $KeyStore)) { throw 'Key already exists. Run without -InitializeSigning; never replace a release key.' }
    if (-not $InitializeSigning -and (-not (Test-Path -LiteralPath $KeyStore) -or -not (Test-Path -LiteralPath $pinPath))) { throw 'Release key or pinned certificate is missing. No debug signing fallback is allowed.' }
    $gradleText = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw
    $versionName = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    $versionCode = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    if (-not $versionName -or -not $versionCode) { throw 'Cannot read application version.' }
    $runDirectory = Join-Path $projectRoot ('build\release-delivery\' + $versionName + '-' + [guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($runDirectory)
    $buildLog = Join-Path $runDirectory 'build.log'
    Write-Host 'Building Release, JVM tests and complete Release lint. No paid API task is invoked.'
    $previousJavaHome = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = $JavaHome
        Push-Location $projectRoot
        try {
            $buildOutput = Invoke-Tool $java @('-Dorg.gradle.appname=gradlew','-classpath',(Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain',':app:assembleRelease',':app:testDebugUnitTest',':app:lintRelease','--offline','--console=plain','-Pkotlin.incremental=false','-Pyingji.ai.directVerificationEnabled=true')
            [IO.File]::WriteAllText($buildLog, $buildOutput)
            Write-Host $buildOutput
            if ($buildOutput -notmatch 'BUILD SUCCESSFUL') { throw 'Build or verification failed. APK was not signed.' }
        } finally { Pop-Location }
    } finally { $env:JAVA_HOME = $previousJavaHome }
    $unsigned = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
    $aligned = Join-Path $runDirectory 'aligned-unsigned.apk'
    $null = Invoke-Tool $align @('-f','-p','4',$unsigned,$aligned)
    $null = Invoke-Tool $align @('-c','-p','4',$aligned)
    $badging = Invoke-Tool $aapt @('dump','badging',$aligned)
    $manifest = Invoke-Tool $aapt @('dump','xmltree',$aligned,'--file','AndroidManifest.xml')
    if ($badging -notmatch "package: name='com.yingji.app' versionCode='$versionCode' versionName='$([regex]::Escape($versionName))'" -or
        $badging -match 'application-debuggable' -or $manifest -match 'android:(debuggable|testOnly).*0xffffffff') { throw 'Unexpected package/version or debuggable/testOnly build.' }
    $config = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build\generated\source\buildConfig\release\com\yingji\app\BuildConfig.java') -Raw
    if ($config -notmatch 'AI_DIRECT_VERIFICATION_ENABLED = true' -or $config -notmatch 'DEBUG = false') { throw 'Release networking or DEBUG configuration is incorrect.' }
    $secret = Read-SigningPassword -Confirm:$InitializeSigning
    $backupPath = $null
    if ($InitializeSigning) {
        if ($BackupDirectory) { $BackupDirectory = Resolve-BackupDirectory $BackupDirectory $keyDirectory }
        else { $BackupDirectory = Read-BackupDirectory $keyDirectory }
        $backupFolder = Join-Path $BackupDirectory ('YingJi-signing-' + [guid]::NewGuid().ToString('N'))
        New-PrivateDirectory $backupFolder
        if (-not (Test-Path -LiteralPath $keyDirectory)) { New-PrivateDirectory $keyDirectory }
        if (Test-Path -LiteralPath $pinPath) { throw 'Certificate pin already exists; inspect the existing key material locally.' }
        $null = Invoke-Tool $keytool @('-genkeypair','-keystore',$KeyStore,'-storetype','JKS','-alias',$keyAlias,'-keyalg','RSA','-keysize','3072','-sigalg','SHA256withRSA','-validity','10958','-dname','CN=YingJi Release','-storepass:env','YINGJI_SIGNING_PASSWORD','-keypass:env','YINGJI_SIGNING_PASSWORD') $secret
        $backupPath = Join-Path $backupFolder 'yingji-release.jks'
        [IO.File]::Copy($KeyStore, $backupPath, $false)
        if ((Get-Sha256 $KeyStore) -ne (Get-Sha256 $backupPath)) { throw 'Backup hash mismatch. Do not distribute.' }
        $certificatePath = Join-Path $runDirectory 'release-certificate.cer'
        $null = Invoke-Tool $keytool @('-exportcert','-keystore',$KeyStore,'-alias',$keyAlias,'-storepass:env','YINGJI_SIGNING_PASSWORD','-file',$certificatePath) $secret
        $backupCertificate = Join-Path $runDirectory 'backup-certificate.cer'
        $null = Invoke-Tool $keytool @('-exportcert','-keystore',$backupPath,'-alias',$keyAlias,'-storepass:env','YINGJI_SIGNING_PASSWORD','-file',$backupCertificate) $secret
        $fingerprint = Get-Sha256 $certificatePath
        if ($fingerprint -ne (Get-Sha256 $backupCertificate)) { throw 'Backup certificate mismatch. Do not distribute.' }
        [IO.File]::WriteAllText($pinPath, $fingerprint, (New-Object Text.UTF8Encoding($false)))
        [IO.File]::Copy($pinPath, (Join-Path $backupFolder 'yingji-release-cert.sha256'), $false)
    } else {
        $certificatePath = Join-Path $runDirectory 'release-certificate.cer'
        $null = Invoke-Tool $keytool @('-exportcert','-keystore',$KeyStore,'-alias',$keyAlias,'-storepass:env','YINGJI_SIGNING_PASSWORD','-file',$certificatePath) $secret
        $fingerprint = Get-Sha256 $certificatePath
        if ($fingerprint -ne (Get-Content -LiteralPath $pinPath -Raw).Trim()) { throw 'Release certificate does not match the pinned signing identity.' }
    }
    $apk = Join-Path $runDirectory ('YingJi-' + $versionName + '-' + $versionCode + '.apk')
    $null = Invoke-Tool $java @('-jar',$signer,'sign','--ks',$KeyStore,'--ks-key-alias',$keyAlias,'--ks-pass','env:YINGJI_SIGNING_PASSWORD','--key-pass','env:YINGJI_SIGNING_PASSWORD','--v4-signing-enabled','false','--out',$apk,$aligned) $secret
    $verification = Invoke-Tool $java @('-jar',$signer,'verify','--verbose','--print-certs',$apk)
    if ($verification -notmatch ('certificate SHA-256 digest: ' + $fingerprint) -or $verification -match 'CN=Android Debug') { throw 'Signed APK certificate mismatch or debug certificate.' }
    $null = Invoke-Tool $align @('-c','-p','4',$apk)
    $signedBadging = Invoke-Tool $aapt @('dump','badging',$apk)
    if ($signedBadging -ne $badging) { throw 'APK package metadata changed after signing.' }
    [IO.File]::WriteAllText((Join-Path $runDirectory 'signature-verification.txt'), $verification)
    [IO.File]::WriteAllText((Join-Path $runDirectory 'badging.txt'), $signedBadging)
    [IO.File]::WriteAllText((Join-Path $runDirectory 'manifest.txt'), $manifest)
    $receipt = [ordered]@{ status='verified'; applicationId='com.yingji.app'; versionName=$versionName; versionCode=[int]$versionCode; apk=$apk; apkSha256=(Get-Sha256 $apk); certificateSha256=$fingerprint; keyStore=$KeyStore; keyBackup=$backupPath; keyBackupHashVerified=[bool]$InitializeSigning; directNetworkingEnabled=$true; debuggable=$false; installed=$false; distributed=$false }
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'release-receipt.json') -Encoding UTF8
    Write-Host ('Verified signed APK: ' + $apk)
    Write-Host ('Certificate SHA-256: ' + $fingerprint)
    Write-Host 'No device installation or distribution was performed. Keep password and key backup separately.'
} catch {
    if ($runDirectory) { @{status='failed'; message=$_.Exception.Message} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDirectory 'failure.json') -Encoding UTF8 }
    throw
} finally {
    if ($null -ne $secret) { $secret.Dispose() }
}
