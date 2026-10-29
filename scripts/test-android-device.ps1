param(
    [Parameter(Mandatory = $true)][string]$TestClass,
    [string]$Serial = 'ZA222LJBW2',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (!$SkipBuild) {
    & (Join-Path $workspace 'gradlew.bat') :app:assembleDebug :app:assembleDebugAndroidTest -PmotionArtworkTests --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Test build failed' }
}
function Invoke-Adb([string[]]$Arguments) {
    & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB operation failed: $($Arguments[0])" }
}
# Gradle's connected-device task removes the target package on cleanup. Never use it on
# a personal device: install in place and invoke instrumentation directly instead.
Invoke-Adb @('install', '-r', (Join-Path $workspace 'android\app\build\outputs\apk\debug\app-debug.apk'))
Invoke-Adb @('shell', 'am', 'force-stop', 'com.auralis.music')
Invoke-Adb @('shell', 'run-as', 'com.auralis.music', 'mkdir', '-p', 'files', 'shared_prefs', 'databases', 'no_backup')
$backupFolder = Join-Path $env:USERPROFILE ('Downloads\Auralis-checkpoints\phone-before-tests-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backupFolder -Force | Out-Null
$backup = Join-Path $backupFolder 'app-data.tar'
function Start-AdbPipe([string[]]$Arguments) {
    $info = [Diagnostics.ProcessStartInfo]::new($adb)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardError = $true
    foreach ($arg in @('-s', $Serial) + $Arguments) { $info.ArgumentList.Add($arg) }
    return [Diagnostics.Process]::Start($info)
}
$process = Start-AdbPipe @('exec-out', 'run-as', 'com.auralis.music', 'tar', '-cf', '-', 'files', 'shared_prefs', 'databases', 'no_backup')
$file = [IO.File]::Create($backup)
try { $process.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
$process.WaitForExit()
if ($process.ExitCode -ne 0 -or (Get-Item -LiteralPath $backup).Length -lt 1024) { throw 'Phone data backup failed; tests were not started' }
Write-Output "Phone data backed up: $backup"
try {
    Invoke-Adb @('install', '-r', (Join-Path $workspace 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'))
    $result = & $adb -s $Serial shell am instrument -w -r -e class $TestClass com.auralis.music.test/com.auralis.music.MotionArtworkTestRunner
    $text = $result -join "`n"
    Write-Output $text
    if ($text -notmatch 'OK \(\d+ tests?\)' -or $text -match 'FAILURES!!!|Process crashed') { throw 'Device tests failed' }
} finally {
    Invoke-Adb @('shell', 'am', 'force-stop', 'com.auralis.music')
    $process = Start-AdbPipe @('exec-in', 'run-as', 'com.auralis.music', 'tar', '-xf', '-')
    $file = [IO.File]::OpenRead($backup)
    try { $file.CopyTo($process.StandardInput.BaseStream) } finally { $file.Dispose(); $process.StandardInput.Close() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Phone data restoration failed; backup is at $backup" }
    Write-Output 'Phone data restored; application remains installed.'
}
