[CmdletBinding()]
param(
    [ValidateSet('Enable', 'Disable', 'Status')][string]$Action = 'Status',
    [string]$AdbPath = 'E:\Android SDK Platform 35\platform-tools\adb.exe',
    [string]$Serial = '',
    [ValidateRange(1024, 65535)][int]$HostProxyPort = 7897,
    [ValidateRange(1024, 65535)][int]$DeviceProxyPort = 17897
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stateDirectory = Join-Path $projectRoot 'app\build\tmdb-usb-network'
$stateFile = Join-Path $stateDirectory 'state.json'
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'ADB_NOT_FOUND: supply -AdbPath.' }
function Invoke-Adb([string[]]$Arguments) {
    if (@($Arguments | Where-Object { $_ -notmatch '^[-a-zA-Z0-9_.:\[\]]+$' }).Count -gt 0) { throw 'UNSUPPORTED_ADB_ARGUMENT' }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $AdbPath
    $process.StartInfo.Arguments = $Arguments -join ' '
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        [void]$process.Start()
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) { $process.Kill(); throw 'ADB_TIMEOUT' }
        $result = $output.GetAwaiter().GetResult()
        $errorText = $errors.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw ('ADB_FAILED: ' + $errorText.Trim()) }
        return $result.Trim()
    } finally { $process.Dispose() }
}
function Save-State($Value) {
    [void](New-Item -ItemType Directory -Path $stateDirectory -Force)
    $temporary = Join-Path $stateDirectory ([Guid]::NewGuid().ToString('N') + '.tmp')
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 5), $encoding)
    [System.IO.File]::Move($temporary, $stateFile)
}
function Read-Proxy([string]$Device) {
    return Invoke-Adb -Arguments @('-s', $Device, 'shell', 'settings', 'get', 'global', 'http_proxy')
}
function Read-Reverse([string]$Device) {
    $lines = (Invoke-Adb -Arguments @('-s', $Device, 'reverse', '--list')) -split '\r?\n'
    return @($lines | Where-Object { $_ -match ('\stcp:' + $DeviceProxyPort + '\s') })
}
$lock = New-Object System.Threading.Mutex($false, 'Local\YingJiTmdbUsbNetwork')
$locked = $false
try {
    try { $locked = $lock.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw 'NETWORK_HELPER_BUSY: wait for the other operation.' }
$state = if (Test-Path -LiteralPath $stateFile) { Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json } else { $null }
if ($state) {
    if ($Serial -and $Serial -ne $state.serial) { throw 'STATE_BELONGS_TO_OTHER_DEVICE' }
    $Serial = $state.serial
    $HostProxyPort = [int]$state.hostPort
    $DeviceProxyPort = [int]$state.devicePort
}
if (-not $Serial) {
    $devices = @((Invoke-Adb -Arguments @('devices')) -split '\r?\n' | Where-Object { $_ -match '^\S+\s+device$' })
    if ($devices.Count -ne 1) { throw 'CONNECT_EXACTLY_ONE_DEVICE_OR_SUPPLY_SERIAL' }
    $Serial = ($devices[0] -split '\s+')[0]
}
if ((Invoke-Adb -Arguments @('-s', $Serial, 'get-state')) -ne 'device') { throw 'DEVICE_NOT_READY' }
$ownedProxy = '127.0.0.1:' + $DeviceProxyPort
$mappingPattern = '\stcp:' + $DeviceProxyPort + '\s+tcp:' + $HostProxyPort + '$'
if ($Action -eq 'Status') {
    $mapping = @(Read-Reverse $Serial)
    [pscustomobject]@{ serial = $Serial; managed = [bool]$state; proxy = (Read-Proxy $Serial); reverse = $mapping; active = [bool]($state -and (Read-Proxy $Serial) -eq $ownedProxy -and $mapping.Count -eq 1 -and $mapping[0] -match $mappingPattern) } | ConvertTo-Json
    return
}
if ($Action -eq 'Disable') {
    if (-not $state) { throw 'NO_OWNED_STATE: no settings changed.' }
    $current = Read-Proxy $Serial
    if ($current -eq $ownedProxy) {
        if ($state.originalProxy -eq 'null') { [void](Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'settings', 'delete', 'global', 'http_proxy')) }
        else { [void](Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'settings', 'put', 'global', 'http_proxy', $state.originalProxy)) }
    } elseif ($current -ne $state.originalProxy) { throw 'PROXY_CHANGED_EXTERNALLY: refusing to overwrite.' }
    if ((Read-Proxy $Serial) -ne $state.originalProxy) { throw 'PROXY_RESTORE_NOT_VERIFIED' }
    $mapping = @(Read-Reverse $Serial)
    if ($mapping.Count -gt 0) {
        if ($mapping.Count -ne 1 -or $mapping[0] -notmatch $mappingPattern) { throw 'REVERSE_CHANGED_EXTERNALLY: refusing to remove.' }
        [void](Invoke-Adb -Arguments @('-s', $Serial, 'reverse', '--remove', ('tcp:' + $DeviceProxyPort)))
    }
    Remove-Item -LiteralPath $stateFile
    Write-Output 'DISABLED_AND_ORIGINAL_PROXY_RESTORED'
    return
}
if ($state) {
    $current = Read-Proxy $Serial
    if ($current -ne $ownedProxy -and $current -ne $state.originalProxy) { throw 'PROXY_CHANGED_EXTERNALLY' }
} else {
    if (@(Read-Reverse $Serial).Count -gt 0) { throw 'DEVICE_PORT_ALREADY_IN_USE: no settings changed.' }
    $original = Read-Proxy $Serial
    if ($original -notmatch '^[-a-zA-Z0-9_.:\[\]]+$') { throw 'UNSUPPORTED_ORIGINAL_PROXY: no settings changed.' }
    if ($original -eq $ownedProxy) { throw 'UNOWNED_PROXY_ALREADY_SET: cannot determine original setting.' }
    $state = [pscustomobject]@{ serial = $Serial; originalProxy = $original; hostPort = $HostProxyPort; devicePort = $DeviceProxyPort; startedAt = [DateTime]::UtcNow.ToString('o') }
}
$client = New-Object System.Net.Sockets.TcpClient
try {
    $pending = $client.ConnectAsync('127.0.0.1', $HostProxyPort)
    if (-not $pending.Wait(3000) -or -not $client.Connected) { throw 'COMPUTER_PROXY_NOT_LISTENING' }
} finally { $client.Dispose() }
$mapping = @(Read-Reverse $Serial)
if ($mapping.Count -gt 0 -and ($mapping.Count -ne 1 -or $mapping[0] -notmatch $mappingPattern)) { throw 'REVERSE_CHANGED_EXTERNALLY' }
# Save the original value before changing either device setting; retry Disable after an interruption.
if (-not (Test-Path -LiteralPath $stateFile)) { Save-State $state }
try {
    [void](Invoke-Adb -Arguments @('-s', $Serial, 'reverse', ('tcp:' + $DeviceProxyPort), ('tcp:' + $HostProxyPort)))
    [void](Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'settings', 'put', 'global', 'http_proxy', $ownedProxy))
    if ((Read-Proxy $Serial) -ne $ownedProxy) { throw 'PROXY_ENABLE_NOT_VERIFIED' }
    Write-Output 'ENABLED: keep USB and the computer proxy running; Disable before disconnecting USB.'
} catch {
    Write-Warning 'Enable did not finish. Reconnect this device and run Disable to restore the saved proxy.'
    throw
}

} finally { if ($locked) { $lock.ReleaseMutex() }; $lock.Dispose() }
