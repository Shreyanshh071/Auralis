<#
.SYNOPSIS
Builds and refreshes the two permanent Android APK downloads in the workspace root.
#>
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if (-not $SkipBuild) {
    & (Join-Path $workspaceRoot 'gradlew.bat') -p $workspaceRoot :app:assembleDebug :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed; existing APK deliverables were preserved.' }
}

$variants = @(
    @{ Source = 'android/app/build/outputs/apk/debug/app-debug.apk'; Target = 'Auralis-Debug.apk' },
    @{ Source = 'android/app/build/outputs/apk/release/app-release.apk'; Target = 'Auralis-Release.apk' }
)
foreach ($variant in $variants) {
    $sourcePath = Join-Path $workspaceRoot $variant.Source
    if (-not (Test-Path -LiteralPath $sourcePath)) { throw "Missing built APK: $sourcePath" }
}
foreach ($variant in $variants) {
    $sourcePath = Join-Path $workspaceRoot $variant.Source
    $targetPath = Join-Path $workspaceRoot $variant.Target
    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
    if ((Get-FileHash -LiteralPath $sourcePath).Hash -ne (Get-FileHash -LiteralPath $targetPath).Hash) {
        throw "APK verification failed: $targetPath"
    }
    Write-Host "Updated and verified $($variant.Target)"
}

# These are the two disposable compiler outputs we just verified against the
# permanent downloads. Leave only the two named APK deliverables after packaging.
Remove-Item -LiteralPath (Join-Path $workspaceRoot 'android/app/build/outputs/apk/debug/app-debug.apk') -Force
Remove-Item -LiteralPath (Join-Path $workspaceRoot 'android/app/build/outputs/apk/release/app-release.apk') -Force
