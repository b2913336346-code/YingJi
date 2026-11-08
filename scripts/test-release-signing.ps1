[CmdletBinding()]
param([string]$JavaHome)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'tool-paths.ps1')
$JavaHome=Resolve-YingJiJavaHome -JavaHome $JavaHome -RequiredTools @('java','keytool','javac')
$scriptPath=Join-Path $PSScriptRoot 'build-release.ps1'
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('yingji-signing-test-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$results=New-Object Collections.Generic.List[object]
function Check([string]$Name,[scriptblock]$Action) {
    & $Action
    $results.Add(@{name=$Name;status='passed'})
    Write-Host ('PASS '+$Name)
}
$tokens=$null; $parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($scriptPath,[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'Signing script syntax error'}
$ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$false) | ForEach-Object {Invoke-Expression $_.Extent.Text}
$testSecret=ConvertTo-SecureString ([guid]::NewGuid().ToString('N')+'TestOnly') -AsPlainText -Force
# The local console's prompt is replaced only in this test process; no production password is supplied.

$promptAnswers=$null
$pathAnswers=$null
function Read-Host {
    param([string]$Prompt,[switch]$AsSecureString)
    if(-not $AsSecureString){
        if($null -eq $pathAnswers -or $pathAnswers.Count -eq 0){throw 'Unexpected text prompt'}
        return $pathAnswers.Dequeue()
    }
    if($null -ne $promptAnswers){
        if($promptAnswers.Count -eq 0){throw 'Password validation requested an unexpected extra prompt'}
        return $promptAnswers.Dequeue()
    }
    return $testSecret.Copy()
}
try {
    Check 'A short password retries and then accepts a confirmed valid password' {
        $promptAnswers=New-Object Collections.Generic.Queue[Security.SecureString]
        $promptAnswers.Enqueue((ConvertTo-SecureString 'short' -AsPlainText -Force))
        $promptAnswers.Enqueue($testSecret.Copy())
        $promptAnswers.Enqueue($testSecret.Copy())
        $readValue=$null
        try {
            $readValue=Read-SigningPassword -Confirm
            if($readValue.Length -ne $testSecret.Length -or $promptAnswers.Count -ne 0){throw 'Short password retry did not consume the expected prompts'}
        } finally {
            if($null -ne $readValue){$readValue.Dispose()}
            while($promptAnswers.Count){$promptAnswers.Dequeue().Dispose()}
            $promptAnswers=$null
        }
    }
    Check 'Confirmation mismatch retries without creating a key or weakening password length' {
        $promptAnswers=New-Object Collections.Generic.Queue[Security.SecureString]
        $promptAnswers.Enqueue($testSecret.Copy())
        $promptAnswers.Enqueue((ConvertTo-SecureString ([guid]::NewGuid().ToString('N')) -AsPlainText -Force))
        $promptAnswers.Enqueue($testSecret.Copy())
        $promptAnswers.Enqueue($testSecret.Copy())
        $readValue=$null
        try {
            $readValue=Read-SigningPassword -Confirm
            if($readValue.Length -lt 12 -or $promptAnswers.Count -ne 0){throw 'Confirmation retry failed'}
        } finally {
            if($null -ne $readValue){$readValue.Dispose()}
            while($promptAnswers.Count){$promptAnswers.Dequeue().Dispose()}
            $promptAnswers=$null
        }
    }
    Check 'Backup input rejects relative, project, primary and missing paths then accepts an existing quoted folder' {
        $existingBackup=Join-Path $testRoot 'path-check-backup'
        [void][IO.Directory]::CreateDirectory($existingBackup)
        $primary=Join-Path $testRoot 'path-check-primary'
        [void][IO.Directory]::CreateDirectory($primary)
        foreach($invalid in @('not-a-path',$projectRoot,$primary,(Join-Path $testRoot 'missing-folder'),'')) {
            $rejected=$false
            try {$null=Resolve-BackupDirectory $invalid $primary} catch {$rejected=$true}
            if(-not $rejected){throw 'An invalid backup directory was accepted'}
        }
        $driveRoot=[IO.Path]::GetPathRoot($existingBackup)
        if((Resolve-BackupDirectory $driveRoot $primary) -ne $driveRoot){throw 'Drive root path lost its separator'}
        $pathAnswers=New-Object Collections.Generic.Queue[string]
        foreach($answer in @('not-a-path',$projectRoot,$primary,('"'+$existingBackup+'"'))) {$pathAnswers.Enqueue($answer)}
        $resolved=Read-BackupDirectory $primary
        if($resolved -ne $existingBackup -or $pathAnswers.Count -ne 0){throw 'Backup prompt did not retry correctly'}
        $pathAnswers=$null
    }
    Check 'Project-local keystore is rejected' {
        $rejected=$false
        try {$null=Assert-OutsideProject (Join-Path $projectRoot 'private.jks')} catch {$rejected=$true}
        if(-not $rejected){throw 'Project-local key was allowed'}
    }
    Check 'Missing release key fails without a debug fallback' {
        $rejected=$false
        try {& $scriptPath -KeyStore (Join-Path $testRoot 'missing\release.jks') -JavaHome $JavaHome} catch {if($_.Exception.Message -match 'missing'){$rejected=$true}else{throw}}
        if(-not $rejected){throw 'Missing key was allowed'}
    }
    $backupParent=Join-Path $testRoot 'backup'
    [void][IO.Directory]::CreateDirectory($backupParent)
    $fixtureKey=Join-Path $testRoot 'primary\release.jks'
    $runBefore=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build\release-delivery') -Directory -ErrorAction SilentlyContinue | ForEach-Object {$_.FullName})
    Check 'Full build, key generation, backup, alignment and signed APK verification' {
        & $scriptPath -InitializeSigning -KeyStore $fixtureKey -BackupDirectory $backupParent -JavaHome $JavaHome
    }
    $newRuns=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build\release-delivery') -Directory | Where-Object {$_.FullName -notin $runBefore})
    if($newRuns.Count -ne 1){throw 'Expected exactly one test delivery run'}
    $fixtureReceipt=Get-Content -LiteralPath (Join-Path $newRuns[0].FullName 'release-receipt.json') -Raw | ConvertFrom-Json
    $fixtureReceipt | Add-Member -NotePropertyName fixtureOnly -NotePropertyValue $true
    $fixtureReceipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $newRuns[0].FullName 'release-receipt.json') -Encoding UTF8
    Check 'Receipt validates 3.0.0/3, production package, network flag and byte-identical backup' {
        if($fixtureReceipt.versionName -ne '3.0.0' -or $fixtureReceipt.versionCode -ne 3 -or $fixtureReceipt.applicationId -ne 'com.yingji.app' -or -not $fixtureReceipt.directNetworkingEnabled -or $fixtureReceipt.debuggable -or -not $fixtureReceipt.keyBackupHashVerified){throw 'Wrong receipt metadata'}
        if((Get-Sha256 $fixtureKey) -ne (Get-Sha256 $fixtureReceipt.keyBackup)){throw 'Backup differs'}
    }
    $keyBefore=Get-Sha256 $fixtureKey
    Check 'Existing key cannot be overwritten by initialization' {
        $rejected=$false
        try {& $scriptPath -InitializeSigning -KeyStore $fixtureKey -BackupDirectory $backupParent -JavaHome $JavaHome} catch {if($_.Exception.Message -match 'already exists'){$rejected=$true}else{throw}}
        if(-not $rejected -or (Get-Sha256 $fixtureKey) -ne $keyBefore){throw 'Existing key was changed'}
    }
    Check 'Wrong password fails and does not echo credential' {
        $wrong=ConvertTo-SecureString ([guid]::NewGuid().ToString('N')) -AsPlainText -Force
        try {
            $rejected=$false
            try {$null=Invoke-Tool (Join-Path $JavaHome 'bin\keytool.exe') @('-list','-keystore',$fixtureKey,'-storepass:env','YINGJI_SIGNING_PASSWORD') $wrong} catch {if($_.Exception.Message -match '^Signing tool failed'){$rejected=$true}else{throw}}
            if(-not $rejected){throw 'Wrong password was accepted'}
        } finally {$wrong.Dispose()}
    }
    Check 'Windows argument quoting preserves spaces, quotes and trailing backslashes' {
        $echo=Join-Path $testRoot 'ArgumentEcho.java'
        [IO.File]::WriteAllText($echo,'class ArgumentEcho { public static void main(String[] args) { for(String s: args) System.out.println(java.util.Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } }')
        $null=Invoke-Tool (Join-Path $JavaHome 'bin\javac.exe') @($echo)
        $values=@('space path',((Join-Path $testRoot 'folder with space')+[IO.Path]::DirectorySeparatorChar),'a"b','a\"b','')
        # Keep an explicit final empty argument in the expected line stream.
        $expected=($values | ForEach-Object {[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_))}) -join "`n"
        $raw=Invoke-Tool (Join-Path $JavaHome 'bin\java.exe') (@('-cp',$testRoot,'ArgumentEcho')+$values)
        if($raw.Replace("`r`n","`n") -cne ($expected+"`n")){throw 'Native arguments changed'}
    }
    Check 'Existing pinned key can sign another build without replacing the key' {
        & $scriptPath -KeyStore $fixtureKey -JavaHome $JavaHome
        if((Get-Sha256 $fixtureKey) -ne $keyBefore){throw 'Reusing key changed its bytes'}
    }
    $pin=Join-Path (Split-Path -Parent $fixtureKey) 'yingji-release-cert.sha256'
    [IO.File]::WriteAllText($pin,('0'*64))
    Check 'Certificate identity mismatch blocks signing' {
        $rejected=$false
        try {& $scriptPath -KeyStore $fixtureKey -JavaHome $JavaHome} catch {if($_.Exception.Message -match 'pinned signing identity'){$rejected=$true}else{throw}}
        if(-not $rejected){throw 'Wrong certificate identity was accepted'}
    }
    @{status='passed';tests=$results;fixtureOnly=$true;testRoot=$testRoot;fixtureReceipt=$fixtureReceipt;formalKeyCreated=$false} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $testRoot 'results.json') -Encoding UTF8
    Write-Host ('Test results: '+(Join-Path $testRoot 'results.json'))
} finally {$testSecret.Dispose()}
