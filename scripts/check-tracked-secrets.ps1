# Checks tracked working-tree files, excluding ignored local build configuration.
# Reports paths only so a detection does not print the exposed value again.
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
Push-Location $workspaceRoot
try {
    $matches = @(git grep --name-only --extended-regexp 'AIza[0-9A-Za-z_-]{35}' -- .)
    if ($LASTEXITCODE -gt 1) { throw 'Could not scan tracked files' }
    if ($matches.Count -gt 0) {
        $matches | ForEach-Object { Write-Output "Google API key found in tracked file: $_" }
        throw 'Move API keys to ignored build configuration before committing'
    }
    Write-Output 'No Google API key literals found in tracked working-tree files.'
} finally { Pop-Location }
