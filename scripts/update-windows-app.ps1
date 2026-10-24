<#
.SYNOPSIS
    Builds the complete Windows distribution, backs up user data, updates the installed app, and launches it for testing.
.PARAMETER TestFilter
    Optional gradle test filter pattern (e.g. "*SidebarLyricsCollapseTest*").
.PARAMETER SkipTests
    Skip running tests before updating.
.PARAMETER NoLaunch
    Leave the updated app closed so an offline settings change can be applied before launch.
.PARAMETER SkipStartupCache
    Skip optional Java class archive generation; the app can generate it after launch.
.PARAMETER Note
    A short description/name for the backup checkpoint.
#>
param(
    [string]$TestFilter = "",
    [switch]$SkipTests,
    [switch]$ClearArtworkCache,
    [switch]$NoLaunch,
    [switch]$SkipStartupCache,
    [string]$Note = "auto"
)

$ErrorActionPreference = "Stop"

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$appLocalDir = "$env:USERPROFILE\AppData\Local\Auralis"
$appExe = "$appLocalDir\Auralis.exe"
$appCfg = "$appLocalDir\app\Auralis.cfg"
$appDataDir = "$env:USERPROFILE\AppData\Roaming\Auralis"
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

# 2. Build the complete distribution, including matching Compose/runtime dependencies
Write-Host "==> Building Windows distribution..." -ForegroundColor Cyan
& "$workspaceRoot\gradlew.bat" :windows:createDistributable
if ($LASTEXITCODE -ne 0) {
    Write-Error "Distribution build failed with exit code $LASTEXITCODE. Aborting update."
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
    # Lyrics are a refetchable cache with a hard provider retention limit, not backup data.
    Get-ChildItem -LiteralPath $appDataDir | Where-Object { $_.Name -ne 'lyrics' } | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $backupDir -Recurse -Force
    }
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

# Credential files belong to the Windows user, never to the application image.
# Verify the deployment itself leaves every saved connection byte-for-byte intact.
$sessionHashes = @{}
foreach ($sessionName in @('account-session.bin', 'youtube-session.properties', 'youtube-accounts.properties', 'youtube-session-source.properties', 'spotify-session.properties')) {
    $sessionPath = Join-Path $appDataDir $sessionName
    if (Test-Path -LiteralPath $sessionPath) {
        $sessionHashes[$sessionName] = (Get-FileHash -LiteralPath $sessionPath -Algorithm SHA256).Hash
    }
}

if ($ClearArtworkCache) {
    $artCache = "$appDataDir\artwork-cache"
    if (Test-Path $artCache) {
        Write-Host "==> Clearing artwork cache at $artCache..." -ForegroundColor Cyan
        Remove-Item -Path "$artCache\*" -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# 6. Update the complete app image. Copying only project jars can leave an incompatible
# installed Compose runtime (including libraries shrunk by a previous release installer).
$distributionDir = "$workspaceRoot\windows\build\compose\binaries\main\app\Auralis"
if (-not (Test-Path -LiteralPath "$distributionDir\Auralis.exe")) {
    throw "Missing Windows distribution at $distributionDir"
}
Write-Host "==> Updating installed application and runtime..." -ForegroundColor Cyan
Get-ChildItem -LiteralPath $distributionDir | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $appLocalDir -Recurse -Force
}

foreach ($sessionName in $sessionHashes.Keys) {
    $sessionPath = Join-Path $appDataDir $sessionName
    if (-not (Test-Path -LiteralPath $sessionPath) -or (Get-FileHash -LiteralPath $sessionPath -Algorithm SHA256).Hash -ne $sessionHashes[$sessionName]) {
        throw "Saved connection changed during deployment: $sessionName. User data backup: $backupDir"
    }
}
Write-Host "    Saved account connections preserved." -ForegroundColor Green

# 6b. Build this build's class archive now. Each build names its own (Auralis.cfg); without it the
# first launch loads every class from the jars and shows a black window for seconds, and the app
# only builds it itself 45s into that launch. Uses the class list recorded by the last full start.
$appDir = "$appLocalDir\app"
$archiveName = (Select-String -LiteralPath $appCfg -Pattern '-Dauralis\.cdsArchive=(\S+)').Matches | Select-Object -First 1 | ForEach-Object { $_.Groups[1].Value }
$classList = "$appDir\auralis-classes.lst"
if (-not $SkipStartupCache -and $archiveName -and -not (Test-Path -LiteralPath "$appDir\$archiveName") -and (Test-Path -LiteralPath $classList) -and (Get-Item -LiteralPath $classList).Length -ge 200000) {
    Write-Host "==> Building class archive $archiveName..." -ForegroundColor Cyan
    Get-ChildItem -LiteralPath $appDir -Filter 'auralis-cds-*.jsa*' | Where-Object { $_.Name -ne $archiveName } | ForEach-Object {
        $_.IsReadOnly = $false; Remove-Item -LiteralPath $_.FullName -Force
    }
    # The same class path, in the same order, as the launcher gives the app, or the JVM rejects the archive.
    $classPath = (Get-Content -LiteralPath $appCfg | Where-Object { $_ -like 'app.classpath=*' } |
        ForEach-Object { $_.Substring('app.classpath='.Length).Replace('$APPDIR', $appDir) }) -join ';'
    $dumpList = "$appDir\auralis-classes.dump.lst"
    $partial = "$appDir\$archiveName.partial"
    Copy-Item -LiteralPath $classList -Destination $dumpList -Force
    & "$appLocalDir\runtime\bin\java.exe" -Xshare:dump "-XX:SharedClassListFile=$dumpList" "-XX:SharedArchiveFile=$partial" -cp $classPath *> "$appDir\auralis-cds.log"
    $dumpExit = $LASTEXITCODE
    Remove-Item -LiteralPath $dumpList -Force -ErrorAction SilentlyContinue
    if ($dumpExit -eq 0 -and (Test-Path -LiteralPath $partial)) {
        Move-Item -LiteralPath $partial -Destination "$appDir\$archiveName" -Force
        Write-Host "    Class archive ready." -ForegroundColor Green
    } else {
        Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
        Write-Warning "Class archive build failed (see $appDir\auralis-cds.log); the app builds it itself after its first launch."
    }
}

# 7. Launch Auralis
if ($NoLaunch) {
    Write-Host "==> Update complete. Launch skipped as requested." -ForegroundColor Green
    exit 0
}
Write-Host "==> Launching $appExe on interactive desktop..." -ForegroundColor Cyan
$shell = New-Object -ComObject "Shell.Application"
$shell.ShellExecute($appExe, "", "", "open", 1)

Start-Sleep -Seconds 3
$newProcesses = Get-Process -Name Auralis -ErrorAction SilentlyContinue
if ($newProcesses) {
    Write-Host "==> Auralis successfully running with PID(s): $(($newProcesses.Id) -join ', ')" -ForegroundColor Green
} else {
    Write-Warning "Could not detect running Auralis process. Please check manually."
}
