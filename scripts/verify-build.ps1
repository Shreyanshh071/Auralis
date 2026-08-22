# Auralis - build verification (Windows)
#
# Runs the build steps that could not be executed in the Linux sandbox where the
# baseline fixes were made, and captures exact output so failures are diagnosable.
#
#   1. npm run build            (tsc -b && vite build)
#   2. npx cap sync android     (refreshes the bundled web assets under android/)
#   3. gradlew.bat assembleDebug
#
# Usage, from the repo root in PowerShell:
#     .\scripts\verify-build.ps1
#
# Check the syntax without executing anything:
#     $e = $null
#     [System.Management.Automation.Language.Parser]::ParseFile(
#         (Resolve-Path .\scripts\verify-build.ps1), [ref]$null, [ref]$e) | Out-Null
#     $e
#
# ENCODING NOTE - do not reintroduce non-ASCII characters into this file.
# A previous revision used en/em dashes. Saved as UTF-8 with no BOM, Windows
# PowerShell decodes .ps1 using the ANSI codepage (Windows-1252), so each dash
# became three characters ending in a curly double quote. PowerShell accepts a
# curly quote as a string delimiter, so strings terminated early and the file
# failed to parse. This file is plain ASCII with CRLF endings, which decodes
# identically under Windows-1252, UTF-8, and UTF-8-with-BOM.
#
# Nothing here modifies source files. A full transcript is written to
# build-verification.log in the repo root.

$ErrorActionPreference = 'Continue'

# Native tools (node, vite, gradle) emit UTF-8. Windows PowerShell otherwise
# decodes their output with the console's OEM codepage, which turns box-drawing
# and check-mark glyphs into mojibake in the transcript. Decode as UTF-8 so the
# captured log is faithful. Wrapped so a locked-down console cannot abort the run.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { $OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -Path $repoRoot

$log = Join-Path $repoRoot 'build-verification.log'
$stamp = Get-Date -Format 'o'
"Auralis build verification - $stamp" | Set-Content -Path $log -Encoding UTF8

$results = [ordered]@{}
$script:StepExit = 0

function Write-Section {
    param([string] $Title)

    $bar = '=' * 72
    Write-Host ''
    Write-Host $bar -ForegroundColor DarkGray
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host $bar -ForegroundColor DarkGray
    '' | Add-Content -Path $log -Encoding UTF8
    "=== $Title ===" | Add-Content -Path $log -Encoding UTF8
}

function Test-Tool {
    param([string] $Name)

    $found = Get-Command -Name $Name -ErrorAction SilentlyContinue
    return ($null -ne $found)
}

# Runs one native command, logs everything, records PASS/FAIL, and leaves the
# exit code in $script:StepExit. It deliberately returns nothing: a function that
# both writes to the pipeline and returns a value is easy to misread, and callers
# only ever need the exit code.
function Invoke-Step {
    param(
        [string]   $Label,
        [string]   $Exe,
        [string[]] $ExeArgs = @(),
        [string]   $WorkDir = ''
    )

    if ([string]::IsNullOrWhiteSpace($WorkDir)) {
        $WorkDir = $repoRoot
    }

    $shown = $ExeArgs -join ' '
    Write-Host "> $Exe $shown" -ForegroundColor DarkGray
    "> $Exe $shown  (cwd: $WorkDir)" | Add-Content -Path $log -Encoding UTF8

    $previous = Get-Location
    Set-Location -Path $WorkDir

    $global:LASTEXITCODE = 0
    # 2>&1 merges stderr so the log captures compiler and Gradle diagnostics.
    # Under Windows PowerShell a native command's stderr lines arrive as
    # ErrorRecord objects; left as-is they render with a "NativeCommandError /
    # At line:.." banner that reads like a script failure even when the exit
    # code is 0 (java -version and vite both write progress to stderr). Convert
    # each item to its plain string so the log shows the real tool output. A
    # wide Out-String keeps long compiler/Gradle lines from wrapping.
    $out = & $Exe @ExeArgs 2>&1 |
        ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() } else { "$_" }
        } |
        Out-String -Width 4096
    $code = $LASTEXITCODE

    Set-Location -Path $previous

    if ($null -eq $code) { $code = 0 }
    if ($null -eq $out)  { $out = '' }

    $out | Add-Content -Path $log -Encoding UTF8
    "exit code: $code" | Add-Content -Path $log -Encoding UTF8

    $trimmed = $out.Trim()
    if ($trimmed.Length -gt 0) {
        # "\r?\n" is a regex, not a PowerShell escape. Backticks are avoided
        # throughout this file so no escape processing can be misread.
        $lines = $trimmed -split "\r?\n"
        if ($lines.Count -gt 40) {
            $tailStart = $lines.Count - 15
            foreach ($line in $lines[0..14]) {
                Write-Host "  $line"
            }
            $omitted = $lines.Count - 30
            Write-Host "  ... ($omitted lines omitted; full text in build-verification.log)" -ForegroundColor DarkGray
            foreach ($line in $lines[$tailStart..($lines.Count - 1)]) {
                Write-Host "  $line"
            }
        } else {
            foreach ($line in $lines) {
                Write-Host "  $line"
            }
        }
    }

    if ($code -eq 0) {
        Write-Host "  [OK] $Label" -ForegroundColor Green
        $results[$Label] = 'PASS'
    } else {
        Write-Host "  [FAIL] $Label  (exit $code)" -ForegroundColor Red
        $results[$Label] = "FAIL (exit $code)"
    }

    $script:StepExit = $code
}

# ---------------------------------------------------------------------------
Write-Section 'Preflight - toolchain'

if (Test-Tool 'node') {
    Invoke-Step 'node version' 'node' @('--version')
} else {
    Write-Host '  [FAIL] node is not on PATH.' -ForegroundColor Red
    $results['node version'] = 'FAIL (not on PATH)'
}

if (Test-Tool 'npm') {
    Invoke-Step 'npm version' 'npm' @('--version')
} else {
    Write-Host '  [FAIL] npm is not on PATH.' -ForegroundColor Red
    $results['npm version'] = 'FAIL (not on PATH)'
}

Write-Host ''
$javaHome = $env:JAVA_HOME
$androidHome = $env:ANDROID_HOME
$androidSdkRoot = $env:ANDROID_SDK_ROOT
Write-Host "JAVA_HOME        = $javaHome"
Write-Host "ANDROID_HOME     = $androidHome"
Write-Host "ANDROID_SDK_ROOT = $androidSdkRoot"
"JAVA_HOME=$javaHome" | Add-Content -Path $log -Encoding UTF8
"ANDROID_HOME=$androidHome" | Add-Content -Path $log -Encoding UTF8
"ANDROID_SDK_ROOT=$androidSdkRoot" | Add-Content -Path $log -Encoding UTF8

# Gradle locates the JDK through JAVA_HOME, not PATH. detect-android-toolchain.ps1
# -Apply sets JAVA_HOME for the user, but a shell that was already open (or one
# spawned by another tool) will not have inherited it, so java can be absent from
# PATH while a perfectly good JDK 21 is configured. If JAVA_HOME points at a real
# JDK, put its bin on PATH for this process so both the version check below and
# Gradle find it. This makes the check match what Gradle actually requires.
if (-not (Test-Tool 'java') -and -not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaFromHome = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -Path $javaFromHome) {
        $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
        Write-Host "  [info] java not on PATH; using JAVA_HOME ($env:JAVA_HOME)." -ForegroundColor DarkGray
    }
}

# JDK 21 is required: android/app/capacitor.build.gradle targets VERSION_21 and
# Android Gradle Plugin 8.13.0 requires JDK 17 or newer.
$javaOk = $false
if (Test-Tool 'java') {
    # java writes its version banner to stderr; stringify each merged line so it
    # is not rendered as a NativeCommandError (see Invoke-Step for the detail).
    $javaVer = (& java -version 2>&1 | ForEach-Object { "$_" } | Out-String)
    if ($null -eq $javaVer) { $javaVer = '' }
    $javaVer | Add-Content -Path $log -Encoding UTF8
    Write-Host ''
    Write-Host $javaVer.Trim()

    if ($javaVer -match 'version "(\d+)') {
        $major = [int] $Matches[1]
        if ($major -ge 21) {
            Write-Host "  [OK] JDK $major detected (21 or newer required)" -ForegroundColor Green
            $javaOk = $true
            $results['JDK 21+'] = "PASS (JDK $major)"
        } else {
            Write-Host "  [FAIL] JDK $major is too old. The Android build needs JDK 21." -ForegroundColor Red
            Write-Host '         A JDK 21 may already be on this machine, for example the one' -ForegroundColor Yellow
            Write-Host '         bundled with Android Studio. Check before installing anything:' -ForegroundColor Yellow
            Write-Host '           .\scripts\detect-android-toolchain.ps1' -ForegroundColor Yellow
            $results['JDK 21+'] = "FAIL (JDK $major)"
        }
    } else {
        Write-Host '  [FAIL] Could not parse a version from java -version.' -ForegroundColor Red
        $results['JDK 21+'] = 'FAIL (unparsable version)'
    }
} else {
    Write-Host '  [FAIL] java is not on PATH.' -ForegroundColor Red
    Write-Host '         Run .\scripts\detect-android-toolchain.ps1 first. A JDK may be' -ForegroundColor Yellow
    Write-Host '         installed but not on PATH, in which case nothing needs downloading.' -ForegroundColor Yellow
    $results['JDK 21+'] = 'FAIL (no java on PATH)'
}

$sdkOk = $false
$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))

# android\local.properties is how Gradle is normally told where the SDK lives, and
# it takes precedence over the environment. detect-android-toolchain.ps1 -Apply
# writes it, so honour it here rather than reporting a missing SDK that Gradle
# would in fact have found.
$localProps = Join-Path $repoRoot 'android\local.properties'
if (Test-Path -Path $localProps) {
    $sdkLine = Select-String -Path $localProps -Pattern 'sdk.dir' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $sdkLine) {
        $fromProps = $sdkLine.Line -replace '^\s*sdk\.dir\s*=\s*', ''
        # Android Studio escapes both the separators and the drive colon.
        $fromProps = $fromProps -replace '\\\\', '\'
        $fromProps = ($fromProps -replace '\\:', ':').Trim()
        $sdkCandidates = @($fromProps) + $sdkCandidates
    }
}

foreach ($candidate in $sdkCandidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate)) {
        if (Test-Path -Path $candidate) {
            Write-Host "  [OK] Android SDK found at $candidate" -ForegroundColor Green
            if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
                $env:ANDROID_HOME = $candidate
            }
            $sdkOk = $true
            $results['Android SDK'] = 'PASS'
            break
        }
    }
}
if (-not $sdkOk) {
    Write-Host '  [FAIL] No Android SDK found.' -ForegroundColor Red
    Write-Host '         Run .\scripts\detect-android-toolchain.ps1 to see whether one is' -ForegroundColor Yellow
    Write-Host '         already installed, and -Apply to configure it if so.' -ForegroundColor Yellow
    $results['Android SDK'] = 'FAIL (not found)'
}

# ---------------------------------------------------------------------------
Write-Section 'Repository hygiene - report only, nothing is committed'

# The new launcher activity was created after the last commit, and the old one at
# the previous owner's package path was deleted. Git sees these as two separate
# changes. If the new file is still untracked when you commit, the repository
# loses its only MainActivity and a fresh clone will not build.
$mainActivity = 'android/app/src/main/java/com/auralis/music/MainActivity.java'
if (-not (Test-Tool 'git')) {
    Write-Host '  [WARN] git is not on PATH, skipping the tracking check.' -ForegroundColor Yellow
    $results['MainActivity tracked'] = 'WARN (git not on PATH)'
} elseif (Test-Path -Path $mainActivity) {
    $tracked = & git ls-files -- $mainActivity 2>$null | Out-String
    if ($null -eq $tracked) { $tracked = '' }
    if ([string]::IsNullOrWhiteSpace($tracked)) {
        Write-Host "  [WARN] $mainActivity exists but is UNTRACKED." -ForegroundColor Yellow
        Write-Host '         Stage both halves together so the tree keeps exactly one MainActivity:' -ForegroundColor Yellow
        Write-Host '           git add -A android/app/src/main/java/' -ForegroundColor Yellow
        $results['MainActivity tracked'] = 'WARN (untracked)'
    } else {
        Write-Host "  [OK] $mainActivity is tracked." -ForegroundColor Green
        $results['MainActivity tracked'] = 'PASS'
    }
} else {
    Write-Host "  [FAIL] $mainActivity is missing, so the manifest .MainActivity cannot resolve." -ForegroundColor Red
    $results['MainActivity tracked'] = 'FAIL (missing)'
}

# A stale lock blocks every staging operation. It could not be cleared from the
# Linux sandbox, because a Windows-side git process is not visible from there.
$indexLock = Join-Path $repoRoot '.git\index.lock'
if (Test-Path -Path $indexLock) {
    Write-Host '  [WARN] .git\index.lock exists, so git cannot stage anything.' -ForegroundColor Yellow
    Write-Host '         Confirm no git process is running, then remove it:' -ForegroundColor Yellow
    Write-Host '           Get-Process git -ErrorAction SilentlyContinue' -ForegroundColor Yellow
    Write-Host '           Remove-Item .git\index.lock' -ForegroundColor Yellow
    $results['git index unlocked'] = 'WARN (index.lock present)'
} else {
    Write-Host '  [OK] No stale .git\index.lock.' -ForegroundColor Green
    $results['git index unlocked'] = 'PASS'
}

# ---------------------------------------------------------------------------
Write-Section 'Dependencies'

# node_modules may have been installed on a different OS, leaving only foreign
# native bindings. A clean install from package-lock.json guarantees the right
# ones for this host.
$needInstall = $false
if (-not (Test-Path -Path 'node_modules')) {
    Write-Host '  node_modules is missing.'
    $needInstall = $true
} elseif (-not (Test-Path -Path 'node_modules\@rolldown\binding-win32-x64-msvc')) {
    Write-Host '  The Windows Rolldown binding is missing from node_modules.'
    $needInstall = $true
} else {
    Write-Host '  [OK] Windows Rolldown binding present.' -ForegroundColor Green
}

if ($needInstall) {
    if (Test-Path -Path 'package-lock.json') {
        Invoke-Step 'npm ci' 'npm' @('ci')
    } else {
        Invoke-Step 'npm install' 'npm' @('install')
    }
}

# ---------------------------------------------------------------------------
Write-Section 'Web build - npm run build (tsc -b then vite build)'

$webCode = 1
if (Test-Tool 'npm') {
    Invoke-Step 'npm run build' 'npm' @('run', 'build')
    $webCode = $script:StepExit
} else {
    Write-Host '  Skipped, npm is not on PATH.' -ForegroundColor Yellow
    $results['npm run build'] = 'SKIPPED (no npm)'
}

if ($webCode -eq 0 -and (Test-Path -Path 'dist\index.html')) {
    $bundle = Get-ChildItem -Path 'dist\assets' -Filter '*.js' -ErrorAction SilentlyContinue |
        Sort-Object -Property Length -Descending |
        Select-Object -First 1

    Write-Host ''
    Write-Host '  dist/ produced:' -ForegroundColor Green
    Write-Host '    index.html'

    if ($null -ne $bundle) {
        $sizeKb = [math]::Round($bundle.Length / 1KB)
        $bundleName = $bundle.Name
        Write-Host "    assets\$bundleName  ($sizeKb KB)"

        # The fixes must be in the emitted bundle, not just in source. The Android
        # app ships this file, so a stale bundle reintroduces every old defect.
        $js = Get-Content -Path $bundle.FullName -Raw
        if ($null -eq $js) { $js = '' }

        Write-Host ''
        Write-Host '  Leaked-identifier checks (regex on the VALUE, not a substring):'

        # A substring test for ':android:' is unsound. src/services/firebase.ts
        # contains the guard /:android:/.test(appId), whose whole job is to REJECT
        # an Android app ID, so the literal ships in the bundle and the old check
        # could not tell the detector apart from the defect it detects.
        # Test the identifier shape instead. A :web: app ID is expected here once
        # .env is filled, so only android and ios platforms are faults.
        $leakChecks = [ordered]@{}
        $leakChecks['no android/ios Firebase app ID'] = '1:[0-9]+:(android|ios):[0-9A-Za-z]+'
        $leakChecks['no Android key from google-services.json'] = 'AIzaSyBSJXXQQXkQ0o-uACoLpZTHiuzhHD0VKo8'

        foreach ($name in $leakChecks.Keys) {
            $rx = $leakChecks[$name]
            $key = "bundle: $name"
            $hit = [regex]::Match($js, $rx)
            if ($hit.Success) {
                $found = $hit.Value
                Write-Host "    [FAIL] $name, found '$found'" -ForegroundColor Red
                $results[$key] = 'FAIL'
            } else {
                Write-Host "    [OK]   $name" -ForegroundColor Green
                $results[$key] = 'PASS'
            }
        }

        Write-Host ''
        Write-Host '  Fix-present checks (stable string literals):'

        # Absence checks against minified identifiers are worthless: the previous
        # marker 'kh.slice(0,8)' passed only because the minifier renamed kh to i
        # between builds, not because anything was fixed. These are string
        # literals, which minification preserves.
        $mustBePresent = [ordered]@{}
        $mustBePresent['honest search error path'] = 'SearchUnavailableError'
        $mustBePresent['favorites sync stamp'] = 'favoritesUpdatedAt'
        $mustBePresent['playlists sync stamp'] = 'playlistsUpdatedAt'
        $mustBePresent['app ID validation message'] = 'Web app ID'

        foreach ($name in $mustBePresent.Keys) {
            $needle = $mustBePresent[$name]
            $key = "bundle: $name"
            if ($js.Contains($needle)) {
                Write-Host "    [OK]   $name" -ForegroundColor Green
                $results[$key] = 'PASS'
            } else {
                Write-Host "    [FAIL] $name, '$needle' is missing from the bundle" -ForegroundColor Red
                $results[$key] = 'FAIL'
            }
        }
    }
}

# ---------------------------------------------------------------------------
Write-Section 'Capacitor sync - copies dist/ into the Android project'

# Critical: android/app/src/main/assets/public/ held a stale bundle predating the
# baseline fixes. Without this step the Android app still runs the old code.
if ($webCode -eq 0) {
    Invoke-Step 'npx cap sync android' 'npx' @('cap', 'sync', 'android')
} else {
    Write-Host '  Skipped, the web build failed so there is nothing to sync.' -ForegroundColor Yellow
    $results['npx cap sync android'] = 'SKIPPED'
}

# Prove the sync landed. android/app/src/main/assets/public/ is gitignored, so a
# stale copy is invisible to git and would silently ship old code in the APK.
if ($webCode -eq 0) {
    $distJs = Get-ChildItem -Path 'dist\assets' -Filter '*.js' -ErrorAction SilentlyContinue |
        Sort-Object -Property Length -Descending |
        Select-Object -First 1
    $shippedJs = Get-ChildItem -Path 'android\app\src\main\assets\public\assets' -Filter '*.js' -ErrorAction SilentlyContinue |
        Sort-Object -Property Length -Descending |
        Select-Object -First 1

    if ($null -eq $distJs -or $null -eq $shippedJs) {
        Write-Host '  [FAIL] Could not locate both bundles to compare.' -ForegroundColor Red
        $results['android assets current'] = 'FAIL (bundle missing)'
    } else {
        $distHash = (Get-FileHash -Path $distJs.FullName -Algorithm SHA256).Hash
        $shipHash = (Get-FileHash -Path $shippedJs.FullName -Algorithm SHA256).Hash
        $distName = $distJs.Name
        if ($distHash -eq $shipHash) {
            Write-Host "  [OK] Shipped Android bundle is byte-identical to dist ($distName)" -ForegroundColor Green
            $results['android assets current'] = 'PASS'
        } else {
            Write-Host '  [FAIL] Shipped Android bundle differs from dist; the APK would run old code.' -ForegroundColor Red
            $results['android assets current'] = 'FAIL (stale)'
        }
    }
}

# ---------------------------------------------------------------------------
Write-Section 'Android debug build - gradlew assembleDebug'

if ($javaOk -and $sdkOk -and $webCode -eq 0) {
    $androidDir = Join-Path $repoRoot 'android'
    $gradlew = Join-Path $androidDir 'gradlew.bat'

    if (Test-Path -Path $gradlew) {
        Invoke-Step 'gradlew assembleDebug' $gradlew @('assembleDebug', '--no-daemon') $androidDir

        $apk = Join-Path $repoRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
        if (Test-Path -Path $apk) {
            $apkSize = [math]::Round(((Get-Item -Path $apk).Length / 1MB), 2)
            Write-Host ''
            Write-Host "  APK: $apk  ($apkSize MB)" -ForegroundColor Green
        }
    } else {
        Write-Host "  [FAIL] $gradlew not found." -ForegroundColor Red
        $results['gradlew assembleDebug'] = 'FAIL (gradlew.bat missing)'
    }
} else {
    $why = @()
    if (-not $javaOk)   { $why += 'JDK 21 missing' }
    if (-not $sdkOk)    { $why += 'Android SDK missing' }
    if ($webCode -ne 0) { $why += 'web build failed' }
    $reason = $why -join '; '
    Write-Host "  Skipped, $reason." -ForegroundColor Yellow
    $results['gradlew assembleDebug'] = "SKIPPED ($reason)"
}

# ---------------------------------------------------------------------------
Write-Section 'Summary'

foreach ($key in $results.Keys) {
    $value = $results[$key]
    $color = 'Red'
    if ($value -like 'PASS*') {
        $color = 'Green'
    } elseif ($value -like 'SKIPPED*' -or $value -like 'WARN*') {
        $color = 'Yellow'
    }
    $row = '  {0,-28} {1}' -f $key, $value
    Write-Host $row -ForegroundColor $color
}

Write-Host ''
Write-Host "Full transcript: $log" -ForegroundColor Cyan

$failed = @($results.Values | Where-Object { $_ -like 'FAIL*' })
if ($failed.Count -gt 0) {
    exit 1
} else {
    exit 0
}
