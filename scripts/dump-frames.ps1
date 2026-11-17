<#
.SYNOPSIS
    Dumps the frames of a video (e.g. a screen recording of an animation) to PNG files.
.DESCRIPTION
    Uses VLC's "scene" video filter from a portable VLC 3.0.24 kept outside the app, at
    %LOCALAPPDATA%\AuralisDevTools\vlc-3.0.24 (the VLC bundled with Auralis is stripped of
    video filters, and app updates replace it). Pass -Install to fetch that VLC from VideoLAN
    (the official win64 zip, checked against its published SHA-256) when it is missing.
.PARAMETER Video
    The video file to dump.
.PARAMETER Out
    Output folder (default: %TEMP%\auralis-frames\<video name>). Must be empty or absent.
.PARAMETER Every
    Keep one frame in this many (default 1: every frame).
.PARAMETER Width
    Width of the saved frames in pixels; height follows the aspect ratio (default 960, 0 = original).
.EXAMPLE
    .\scripts\dump-frames.ps1 "$env:USERPROFILE\Videos\Screen Recordings\clip.mp4"
#>
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Video,
    [string]$Out = "",
    [int]$Every = 1,
    [int]$Width = 960,
    [switch]$Install
)
$ErrorActionPreference = "Stop"

$version = "3.0.24"
$sha256 = "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323"
$tools = Join-Path $env:LOCALAPPDATA "AuralisDevTools"
$vlcDir = Join-Path $tools "vlc-$version"
$vlc = Join-Path $vlcDir "vlc.exe"
$scenePlugin = Join-Path $vlcDir "plugins\video_filter\libscene_plugin.dll"

if (-not (Test-Path -LiteralPath $scenePlugin)) {
    if (-not $Install) { throw "Portable VLC with the scene filter is missing at $vlcDir. Run again with -Install." }
    $download = Join-Path $tools "download"
    New-Item -ItemType Directory -Force $download | Out-Null
    $zip = Join-Path $download "vlc-$version-win64.zip"
    Write-Host "==> Downloading VLC $version (win64, ~83MB) from download.videolan.org..." -ForegroundColor Cyan
    curl.exe -sL -o $zip "https://download.videolan.org/pub/videolan/vlc/$version/win64/vlc-$version-win64.zip"
    if ($LASTEXITCODE -ne 0) { throw "Download failed (curl exit $LASTEXITCODE)." }
    $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $sha256) { Remove-Item -LiteralPath $download -Recurse -Force; throw "Checksum mismatch ($actual); nothing installed." }
    Expand-Archive -LiteralPath $zip -DestinationPath $tools -Force
    Remove-Item -LiteralPath $download -Recurse -Force
    if (-not (Test-Path -LiteralPath $scenePlugin)) { throw "The scene filter wasn't in the archive." }
}

$Video = (Resolve-Path -LiteralPath $Video).Path
if ($Out -eq "") { $Out = Join-Path $env:TEMP ("auralis-frames\" + [IO.Path]::GetFileNameWithoutExtension($Video)) }
$Out = [IO.Path]::GetFullPath($Out)
if (Test-Path -LiteralPath $Out) {
    $target = Get-Item -LiteralPath $Out
    if (-not $target.PSIsContainer -or ($target.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        @(Get-ChildItem -LiteralPath $Out -Force).Count -gt 0) {
        throw "Output must be an empty, ordinary directory. Existing files will not be deleted: $Out"
    }
}
New-Item -ItemType Directory -Force $Out | Out-Null

$arguments = @(
    "`"$Video`"",
    "--intf=dummy", "--dummy-quiet", "--no-audio", "--no-video-title-show",
    # Software decoding: the scene filter writes nothing for GPU (D3D11) decoded frames.
    "--avcodec-hw=none",
    "--scene-format=png", "--scene-ratio=$Every", "--scene-prefix=frame_", "--scene-path=`"$Out`"",
    # The filter runs inside a throwaway transcode rather than playback: playback is clocked, and
    # dropped frames it couldn't write in time (or repeated frames when slowed down). A transcode
    # passes each decoded frame through exactly once (309 of 309 for a 30fps recording).
    "--sout=#transcode{vcodec=mp2v,vb=200,vfilter=scene,acodec=none}:dummy",
    "--play-and-exit", "vlc://quit"
)
if ($Width -gt 0) { $arguments += "--scene-width=$Width" }

Write-Host "==> Dumping frames of $([IO.Path]::GetFileName($Video)) to $Out..." -ForegroundColor Cyan
$started = Get-Date
$process = Start-Process -FilePath $vlc -ArgumentList $arguments -PassThru -WindowStyle Hidden
$process.WaitForExit()
$frames = @(Get-ChildItem -LiteralPath $Out -Filter "frame_*.png")
Write-Host ("==> {0} frames in {1:n1}s -> {2}" -f $frames.Count, ((Get-Date) - $started).TotalSeconds, $Out) -ForegroundColor Green
# Files are numbered by source frame (frame_00001, then every -Every frames): frame / fps = time.
if ($frames.Count -eq 0) { Write-Warning "No frames written. Check that the file plays in $vlc." }
