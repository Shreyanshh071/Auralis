<#
.SYNOPSIS
    Builds the latest Windows module jars, backs up user data, updates the installed app, and launches it for testing.
.PARAMETER TestFilter
    Optional gradle test filter pattern (e.g. "*SidebarLyricsCollapseTest*").
.PARAMETER SkipTests
    Skip running tests before updating.
.PARAMETER Note
    A short description/name for the backup checkpoint.
#>
param(
    [string]$TestFilter = "",
    [switch]$SkipTests,
    [switch]$ClearArtworkCache,
    [string]$Note = "auto"
)

$ErrorActionPreference = "Stop"

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$appLocalDir = "$env:LOCALAPPDATA\Auralis"
$appExe = "$appLocalDir\Auralis.exe"
$appCfg = "$appLocalDir\app\Auralis.cfg"
$appDataDir = "$env:APPDATA\Auralis"
$backupBaseDir = "$env:USERPROFILE\Downloads\Auralis-checkpoints"

Write-Host "==> Starting Windows app update workflow..." -ForegroundColor Cyan

# 1. Optionally run tests
if (-not $SkipTests) {
    if ($TestFilter -ne "") {
        Write-Host "==> Running Windows tests with filter: $TestFilter..." -ForegroundColor Cyan
        & "$workspaceRoot\gradlew.bat" :windows:test --tests "$TestFilter"
    } else {
        Write-Host "==> Running Windows unit tests..." -ForegroundColor Cyan
        & "$workspaceRoot\gradlew.bat" :windows:test
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Windows tests failed with exit code $LASTEXITCODE. Aborting update."
        exit $LASTEXITCODE
    }
}

# 2. Build jars
Write-Host "==> Building Windows jars..." -ForegroundColor Cyan
& "$workspaceRoot\gradlew.bat" :windows:jar :windows-core:desktopJar
if ($LASTEXITCODE -ne 0) {
    Write-Error "Jar build failed with exit code $LASTEXITCODE. Aborting update."
    exit $LASTEXITCODE
}

# 3. Check paths
if (-not (Test-Path $appCfg)) {
    Write-Error "Could not find Auralis.cfg at $appCfg"
    exit 1
}

# 4. Backup user data
if (Test-Path $appDataDir) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $cleanNote = ($Note -replace '[^a-zA-Z0-9_-]', '-')
    $backupDir = "$backupBaseDir\before-$cleanNote-$timestamp\Auralis"
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    Write-Host "==> Backing up user data to $backupDir..." -ForegroundColor Cyan
    Copy-Item -Path "$appDataDir\*" -Destination $backupDir -Recurse -Force
    $backupCount = (Get-ChildItem -Path $backupDir -Recurse -File).Count
    Write-Host "    Preserved $backupCount data files." -ForegroundColor Green
}

# 5. Stop running process
$running = Get-Process -Name Auralis -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "==> Closing running Auralis instance(s)..." -ForegroundColor Yellow
    $running | Stop-Process -Force
    Start-Sleep -Milliseconds 1200
}

if ($ClearArtworkCache) {
    $artCache = "$appDataDir\artwork-cache"
    if (Test-Path $artCache) {
        Write-Host "==> Clearing artwork cache at $artCache..." -ForegroundColor Cyan
        Remove-Item -Path "$artCache\*" -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# 6. Resolve destination jar names from cfg
$cfgLines = Get-Content $appCfg
$mainJarName = $null
$coreJarName = $null

foreach ($line in $cfgLines) {
    if ($line -match '^app\.classpath=\$APPDIR\\(windows-[a-f0-9]+\.jar)$') {
        $mainJarName = $Matches[1]
    }
    if ($line -match '^app\.classpath=\$APPDIR\\(windows-core-desktop-[a-f0-9]+\.jar)$') {
        $coreJarName = $Matches[1]
    }
}

if (-not $mainJarName) {
    Write-Error "Could not identify main windows jar name from $appCfg"
    exit 1
}

$destMainJar = "$appLocalDir\app\$mainJarName"
$srcMainJar = "$workspaceRoot\windows\build\libs\windows.jar"
Write-Host "==> Copying $srcMainJar to $destMainJar..." -ForegroundColor Cyan
Copy-Item -Path $srcMainJar -Destination $destMainJar -Force

if ($coreJarName) {
    $destCoreJar = "$appLocalDir\app\$coreJarName"
    $srcCoreJar = "$workspaceRoot\windows\core\build\libs\windows-core-desktop.jar"
    if (Test-Path $srcCoreJar) {
        Write-Host "==> Copying $srcCoreJar to $destCoreJar..." -ForegroundColor Cyan
        Copy-Item -Path $srcCoreJar -Destination $destCoreJar -Force
    }
}

# 7. Launch Auralis
Write-Host "==> Launching $appExe..." -ForegroundColor Cyan
Start-Process -FilePath $appExe

Start-Sleep -Seconds 3
$newProcesses = Get-Process -Name Auralis -ErrorAction SilentlyContinue
if ($newProcesses) {
    Write-Host "==> Auralis successfully running with PID(s): $(($newProcesses.Id) -join ', ')" -ForegroundColor Green
} else {
    Write-Warning "Could not detect running Auralis process. Please check manually."
}
