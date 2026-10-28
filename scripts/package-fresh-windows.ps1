<# Creates a portable download with its own initially empty profile beside the executable. #>
param([string]$Name = 'Auralis-Fresh-User')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$source = Join-Path $workspace 'windows/build/compose/binaries/main/app/Auralis'
$downloads = Join-Path $workspace 'windows/build/downloads'
$destination = Join-Path $downloads $Name
if (-not (Test-Path -LiteralPath (Join-Path $source 'Auralis.exe'))) {
    throw 'Build :windows:createDistributable before packaging.'
}
if (Test-Path -LiteralPath $destination) { throw "Output already exists: $destination" }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
Get-ChildItem -LiteralPath $source | Copy-Item -Destination $destination -Recurse
$config = Join-Path $destination 'app/Auralis.cfg'
Add-Content -LiteralPath $config -Value 'java-options=-Dauralis.dataDir=$APPDIR\..\profile'
@'
Extract this entire folder to a writable location, then open Auralis.exe.

This copy starts as a new user: no account, songs, library, or listening history.
It keeps its own data in the profile folder beside Auralis.exe. Your installed
Auralis profile stays separate. After your first song, the Liquidify background
follows that song and is restored on the next launch.
'@ | Set-Content -LiteralPath (Join-Path $destination 'START-HERE.txt')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = Join-Path $downloads "$Name.zip"
[System.IO.Compression.ZipFile]::CreateFromDirectory($destination, $archive, [System.IO.Compression.CompressionLevel]::Optimal, $true)
Get-Item -LiteralPath $archive
