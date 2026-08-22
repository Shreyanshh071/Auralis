# Auralis - Android toolchain detection (Windows)
#
# The Android debug build needs a JDK 21 or newer (android/app/capacitor.build.gradle
# targets JavaVersion.VERSION_21) and an Android SDK (compileSdk 36). This script
# finds out whether either already exists on this machine and, with -Apply,
# configures what it found.
#
# It is read-only by default. It never downloads or installs anything, and it
# never assumes a path exists: every location below is either read from the
# environment or the registry, or enumerated from disk, and then tested.
#
# Usage, from the repo root:
#     .\scripts\detect-android-toolchain.ps1            # inspect and report
#     .\scripts\detect-android-toolchain.ps1 -Apply     # also configure what was found
#
# -Apply makes exactly two changes, both reversible:
#     1. writes android\local.properties with sdk.dir  (that file is gitignored)
#     2. sets JAVA_HOME for the current user            (undo: set it back, or clear it)
#
# Check the syntax without executing anything:
#     $e = $null
#     [System.Management.Automation.Language.Parser]::ParseFile(
#         (Resolve-Path .\scripts\detect-android-toolchain.ps1), [ref]$null, [ref]$e) | Out-Null
#     $e
#
# ENCODING NOTE - do not reintroduce non-ASCII characters into this file.
# Windows PowerShell decodes a BOM-less .ps1 using the ANSI codepage, so an en or
# em dash becomes three characters ending in a curly double quote, which
# PowerShell accepts as a string delimiter. Strings then terminate early and the
# file fails to parse. Plain ASCII with CRLF decodes identically under
# Windows-1252, UTF-8, and UTF-8-with-BOM.

[CmdletBinding()]
param(
    [switch] $Apply
)

$ErrorActionPreference = 'Continue'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -Path $repoRoot

function Write-Section {
    param([string] $Title)

    $bar = '-' * 72
    Write-Host ''
    Write-Host $bar -ForegroundColor DarkGray
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host $bar -ForegroundColor DarkGray
}

function Test-Tool {
    param([string] $Name)

    $found = Get-Command -Name $Name -ErrorAction SilentlyContinue
    return ($null -ne $found)
}

# ---------------------------------------------------------------------------
Write-Section 'What the Android build requires'

Write-Host '  JDK          : 21 or newer  (capacitor.build.gradle targets VERSION_21)'
Write-Host '  Android SDK  : platform 36  (variables.gradle sets compileSdk 36)'
Write-Host '  Gradle       : 8.14.3       (downloaded by gradlew on first run)'
Write-Host '  AGP          : 8.13.0'

# ---------------------------------------------------------------------------
Write-Section 'Environment variables as they stand'

$envNames = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_AVD_HOME')
foreach ($n in $envNames) {
    $v = [System.Environment]::GetEnvironmentVariable($n)
    if ([string]::IsNullOrWhiteSpace($v)) {
        Write-Host "  $n = (not set)" -ForegroundColor DarkGray
    } else {
        Write-Host "  $n = $v"
    }
}

# ---------------------------------------------------------------------------
Write-Section 'Searching for a JDK'

$jdks = New-Object System.Collections.ArrayList

# Returns the feature version of a JDK home, or 0 if it cannot be determined.
# The release file is preferred because it costs no process launch.
function Get-JdkMajor {
    param([string] $JdkHome)

    $releaseFile = Join-Path $JdkHome 'release'
    if (Test-Path -Path $releaseFile) {
        $hit = Select-String -Path $releaseFile -Pattern 'JAVA_VERSION' -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -ne $hit) {
            if ($hit.Line -match 'JAVA_VERSION="?([0-9]+)') {
                return [int] $Matches[1]
            }
        }
    }

    $exe = Join-Path $JdkHome 'bin\java.exe'
    if (Test-Path -Path $exe) {
        $text = (& $exe -version 2>&1 | Out-String)
        if ($null -ne $text) {
            if ($text -match 'version "([0-9]+)') {
                return [int] $Matches[1]
            }
        }
    }

    return 0
}

# Only accepts a directory that really contains bin\java.exe, so a wrong guess is
# discarded rather than reported.
function Add-Jdk {
    param([string] $Candidate, [string] $Source)

    if ([string]::IsNullOrWhiteSpace($Candidate)) { return }
    if (-not (Test-Path -Path $Candidate)) { return }
    if (-not (Test-Path -Path (Join-Path $Candidate 'bin\java.exe'))) { return }

    $full = $Candidate
    $resolved = Resolve-Path -Path $Candidate -ErrorAction SilentlyContinue
    if ($null -ne $resolved) { $full = $resolved.Path }

    foreach ($existing in $jdks) {
        if ($existing.Path -eq $full) { return }
    }

    $row = New-Object psobject
    $row | Add-Member -MemberType NoteProperty -Name Path   -Value $full
    $row | Add-Member -MemberType NoteProperty -Name Source -Value $Source
    $row | Add-Member -MemberType NoteProperty -Name Major  -Value (Get-JdkMajor $full)
    [void] $jdks.Add($row)
}

# 1. JAVA_HOME.
Add-Jdk ([System.Environment]::GetEnvironmentVariable('JAVA_HOME')) 'JAVA_HOME'

# 2. Whatever java.exe is on PATH, walked back to its home.
$javaCmd = Get-Command -Name java -ErrorAction SilentlyContinue
if ($null -ne $javaCmd) {
    $binDir = Split-Path -Parent $javaCmd.Source
    Add-Jdk (Split-Path -Parent $binDir) 'java.exe on PATH'
}

# 3. Directories under the standard program roots. These are enumerated with
#    Get-ChildItem, so nothing is assumed to exist; a pattern that matches
#    nothing simply yields nothing.
$programRoots = @(
    [System.Environment]::GetEnvironmentVariable('ProgramFiles'),
    [System.Environment]::GetEnvironmentVariable('ProgramFiles(x86)'),
    [System.Environment]::GetEnvironmentVariable('ProgramW6432')
)
$localAppData = [System.Environment]::GetEnvironmentVariable('LOCALAPPDATA')
if (-not [string]::IsNullOrWhiteSpace($localAppData)) {
    $programRoots += (Join-Path $localAppData 'Programs')
}

$vendorPatterns = @(
    'Java\*',
    'Eclipse Adoptium\*',
    'Eclipse Foundation\*',
    'Microsoft\jdk-*',
    'Amazon Corretto\*',
    'Zulu\*',
    'BellSoft\*',
    'RedHat\*',
    'Android\Android Studio\jbr',
    'Android Studio\jbr',
    'JetBrains\*\jbr'
)

foreach ($root in $programRoots) {
    if ([string]::IsNullOrWhiteSpace($root)) { continue }
    if (-not (Test-Path -Path $root)) { continue }

    foreach ($pattern in $vendorPatterns) {
        $full = Join-Path $root $pattern
        $hits = Get-ChildItem -Path $full -Directory -ErrorAction SilentlyContinue
        foreach ($hit in $hits) {
            Add-Jdk $hit.FullName "disk: $root"
            # Some layouts nest the runtime one level down.
            Add-Jdk (Join-Path $hit.FullName 'jbr') "disk: $root (bundled jbr)"
        }
    }
}

# 4. The registry, for installers that recorded a home but are not on PATH.
$jdkRegPaths = @(
    'HKLM:\SOFTWARE\JavaSoft\JDK',
    'HKLM:\SOFTWARE\JavaSoft\Java Development Kit',
    'HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JDK'
)
foreach ($regPath in $jdkRegPaths) {
    $children = Get-ChildItem -Path $regPath -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        $prop = Get-ItemProperty -Path $child.PSPath -ErrorAction SilentlyContinue
        if ($null -ne $prop) {
            Add-Jdk $prop.JavaHome "registry: $regPath"
        }
    }
}

# 5. Android Studio's own install location, whose bundled JBR is a full JDK.
$studioRegPaths = @('HKLM:\SOFTWARE\Android Studio', 'HKCU:\SOFTWARE\Android Studio')
$studioPath = ''
foreach ($regPath in $studioRegPaths) {
    $prop = Get-ItemProperty -Path $regPath -ErrorAction SilentlyContinue
    if ($null -ne $prop) {
        if (-not [string]::IsNullOrWhiteSpace($prop.Path)) {
            $studioPath = $prop.Path
            Add-Jdk (Join-Path $prop.Path 'jbr') 'registry: Android Studio bundled jbr'
        }
    }
}

if ($jdks.Count -eq 0) {
    Write-Host '  No JDK found in any of the locations searched.' -ForegroundColor Red
} else {
    foreach ($jdk in $jdks) {
        $label = 'unknown version'
        if ($jdk.Major -gt 0) { $label = "JDK $($jdk.Major)" }
        $mark = '[too old]'
        $color = 'Yellow'
        if ($jdk.Major -ge 21) {
            $mark = '[usable] '
            $color = 'Green'
        }
        Write-Host "  $mark $label" -ForegroundColor $color
        Write-Host "            $($jdk.Path)"
        Write-Host "            found via $($jdk.Source)" -ForegroundColor DarkGray
    }
}

$bestJdk = $null
foreach ($jdk in $jdks) {
    if ($jdk.Major -ge 21) {
        if ($null -eq $bestJdk) {
            $bestJdk = $jdk
        } elseif ($jdk.Major -gt $bestJdk.Major) {
            $bestJdk = $jdk
        }
    }
}

# ---------------------------------------------------------------------------
Write-Section 'Searching for an Android SDK'

# A directory only counts as an SDK if it holds at least one real SDK component.
function Test-SdkDir {
    param([string] $Dir)

    if ([string]::IsNullOrWhiteSpace($Dir)) { return $false }
    if (-not (Test-Path -Path $Dir)) { return $false }

    $markers = @('platform-tools', 'platforms', 'build-tools', 'cmdline-tools')
    foreach ($marker in $markers) {
        if (Test-Path -Path (Join-Path $Dir $marker)) { return $true }
    }
    return $false
}

$sdkCandidates = New-Object System.Collections.ArrayList

function Add-Sdk {
    param([string] $Candidate, [string] $Source)

    if (-not (Test-SdkDir $Candidate)) { return }

    $full = $Candidate
    $resolved = Resolve-Path -Path $Candidate -ErrorAction SilentlyContinue
    if ($null -ne $resolved) { $full = $resolved.Path }

    foreach ($existing in $sdkCandidates) {
        if ($existing.Path -eq $full) { return }
    }

    $row = New-Object psobject
    $row | Add-Member -MemberType NoteProperty -Name Path   -Value $full
    $row | Add-Member -MemberType NoteProperty -Name Source -Value $Source
    [void] $sdkCandidates.Add($row)
}

Add-Sdk ([System.Environment]::GetEnvironmentVariable('ANDROID_HOME')) 'ANDROID_HOME'
Add-Sdk ([System.Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT')) 'ANDROID_SDK_ROOT'

if (-not [string]::IsNullOrWhiteSpace($localAppData)) {
    Add-Sdk (Join-Path $localAppData 'Android\Sdk') 'default user location'
}
$userProfile = [System.Environment]::GetEnvironmentVariable('USERPROFILE')
if (-not [string]::IsNullOrWhiteSpace($userProfile)) {
    Add-Sdk (Join-Path $userProfile 'Android\Sdk') 'user profile'
}

$sdkRegPaths = @(
    'HKLM:\SOFTWARE\Android SDK Tools',
    'HKLM:\SOFTWARE\WOW6432Node\Android SDK Tools',
    'HKCU:\SOFTWARE\Android SDK Tools'
)
foreach ($regPath in $sdkRegPaths) {
    $prop = Get-ItemProperty -Path $regPath -ErrorAction SilentlyContinue
    if ($null -ne $prop) {
        Add-Sdk $prop.Path "registry: $regPath"
    }
}

# An existing local.properties would already point Gradle at an SDK.
$localProps = Join-Path $repoRoot 'android\local.properties'
if (Test-Path -Path $localProps) {
    $hit = Select-String -Path $localProps -Pattern 'sdk.dir' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $hit) {
        # Android Studio escapes both the separators and the drive colon.
        $value = $hit.Line -replace '^\s*sdk\.dir\s*=\s*', ''
        $value = $value -replace '\\\\', '\'
        $value = $value -replace '\\:', ':'
        Add-Sdk $value.Trim() 'android\local.properties'
    }
}

$bestSdk = $null
if ($sdkCandidates.Count -eq 0) {
    Write-Host '  No Android SDK found in any of the locations searched.' -ForegroundColor Red
} else {
    foreach ($sdk in $sdkCandidates) {
        Write-Host "  [found] $($sdk.Path)" -ForegroundColor Green
        Write-Host "          found via $($sdk.Source)" -ForegroundColor DarkGray

        $platform36 = Join-Path $sdk.Path 'platforms\android-36'
        if (Test-Path -Path $platform36) {
            Write-Host '          platform 36: present' -ForegroundColor Green
        } else {
            Write-Host '          platform 36: MISSING (compileSdk 36 needs it)' -ForegroundColor Yellow
        }

        $buildTools = Get-ChildItem -Path (Join-Path $sdk.Path 'build-tools') -Directory -ErrorAction SilentlyContinue
        if ($null -ne $buildTools -and $buildTools.Count -gt 0) {
            $versions = ($buildTools | ForEach-Object { $_.Name }) -join ', '
            Write-Host "          build-tools: $versions" -ForegroundColor Green
        } else {
            Write-Host '          build-tools: MISSING' -ForegroundColor Yellow
        }
    }
    $bestSdk = $sdkCandidates[0]
}

if ([string]::IsNullOrWhiteSpace($studioPath)) {
    Write-Host ''
    Write-Host '  Android Studio: not recorded in the registry.' -ForegroundColor DarkGray
} else {
    Write-Host ''
    Write-Host "  Android Studio: $studioPath" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
Write-Section 'Installers available on this machine'

$managers = @('winget', 'choco', 'scoop')
$haveManager = ''
foreach ($manager in $managers) {
    if (Test-Tool $manager) {
        Write-Host "  [yes] $manager" -ForegroundColor Green
        if ([string]::IsNullOrWhiteSpace($haveManager)) { $haveManager = $manager }
    } else {
        Write-Host "  [no ] $manager" -ForegroundColor DarkGray
    }
}

# ---------------------------------------------------------------------------
Write-Section 'Verdict'

$canBuild = ($null -ne $bestJdk) -and ($null -ne $bestSdk)

if ($null -ne $bestJdk) {
    Write-Host "  JDK        : usable, JDK $($bestJdk.Major) at $($bestJdk.Path)" -ForegroundColor Green
} else {
    Write-Host '  JDK        : NOT AVAILABLE, nothing on this machine is JDK 21 or newer.' -ForegroundColor Red
}

if ($null -ne $bestSdk) {
    Write-Host "  Android SDK: usable, $($bestSdk.Path)" -ForegroundColor Green
} else {
    Write-Host '  Android SDK: NOT AVAILABLE.' -ForegroundColor Red
}

Write-Host ''
if ($canBuild) {
    Write-Host '  The Android debug build can run on this machine.' -ForegroundColor Green
    if (-not $Apply) {
        Write-Host '  Re-run with -Apply to configure it, then run scripts\verify-build.ps1.' -ForegroundColor Cyan
    }
} else {
    Write-Host '  The Android debug build CANNOT run until the gap above is closed.' -ForegroundColor Red
    Write-Host '  Nothing is downloaded by this script. The commands below are for you to run.' -ForegroundColor Yellow
    Write-Host ''
    if ($null -eq $bestJdk) {
        Write-Host '  JDK 21:' -ForegroundColor Yellow
        if ($haveManager -eq 'winget') {
            Write-Host '    winget install --id EclipseAdoptium.Temurin.21.JDK -e' -ForegroundColor White
        } elseif ($haveManager -eq 'choco') {
            Write-Host '    choco install temurin21jdk' -ForegroundColor White
        } elseif ($haveManager -eq 'scoop') {
            Write-Host '    scoop bucket add java; scoop install temurin21-jdk' -ForegroundColor White
        } else {
            Write-Host '    No package manager was found, so install manually from adoptium.net.' -ForegroundColor White
        }
        Write-Host '    Installing Android Studio also supplies a JDK 21 as its bundled jbr.' -ForegroundColor DarkGray
    }
    if ($null -eq $bestSdk) {
        Write-Host '  Android SDK:' -ForegroundColor Yellow
        if ($haveManager -eq 'winget') {
            Write-Host '    winget install --id Google.AndroidStudio -e' -ForegroundColor White
        } else {
            Write-Host '    Install Android Studio, then open it once so it provisions the SDK.' -ForegroundColor White
        }
        Write-Host '    In Studio: SDK Manager, install platform 36 and current build-tools.' -ForegroundColor DarkGray
    }
    Write-Host ''
    Write-Host '  After installing, re-run this script with -Apply.' -ForegroundColor Cyan
}

# ---------------------------------------------------------------------------
if ($Apply) {
    Write-Section 'Applying configuration'

    if ($null -eq $bestSdk) {
        Write-Host '  Skipped android\local.properties, no SDK to point it at.' -ForegroundColor Yellow
    } else {
        # Gradle accepts forward slashes here, which avoids the escaping rules that
        # make backslashes in a properties file error-prone.
        $sdkForProps = $bestSdk.Path -replace '\\', '/'
        $body = @()
        $body += '# Generated by scripts\detect-android-toolchain.ps1 -Apply'
        $body += '# This file is gitignored (android/.gitignore) and is local to this machine.'
        $body += "sdk.dir=$sdkForProps"
        Set-Content -Path $localProps -Value $body -Encoding ASCII
        Write-Host "  Wrote android\local.properties with sdk.dir=$sdkForProps" -ForegroundColor Green
    }

    if ($null -eq $bestJdk) {
        Write-Host '  Skipped JAVA_HOME, no JDK 21 or newer to point it at.' -ForegroundColor Yellow
    } else {
        $currentUserJavaHome = [System.Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
        if ($currentUserJavaHome -eq $bestJdk.Path) {
            Write-Host '  JAVA_HOME already points at that JDK, left alone.' -ForegroundColor Green
        } else {
            if (-not [string]::IsNullOrWhiteSpace($currentUserJavaHome)) {
                Write-Host "  Previous user JAVA_HOME was: $currentUserJavaHome" -ForegroundColor Yellow
                Write-Host '  Note that down if you need to undo this.' -ForegroundColor Yellow
            }
            [System.Environment]::SetEnvironmentVariable('JAVA_HOME', $bestJdk.Path, 'User')
            Write-Host "  Set user JAVA_HOME to $($bestJdk.Path)" -ForegroundColor Green
        }

        # Also set it for this process so verify-build.ps1 can be run immediately.
        $env:JAVA_HOME = $bestJdk.Path
        $javaBin = Join-Path $bestJdk.Path 'bin'
        if ($env:PATH -notlike "*$javaBin*") {
            $env:PATH = "$javaBin;" + $env:PATH
        }
        Write-Host '  Also set JAVA_HOME and PATH for this session.' -ForegroundColor Green
        Write-Host '  A new terminal is needed for the user-level change to be picked up.' -ForegroundColor DarkGray
    }

    Write-Host ''
    Write-Host '  Next: .\scripts\verify-build.ps1' -ForegroundColor Cyan
}

Write-Host ''
if ($canBuild) { exit 0 } else { exit 1 }
