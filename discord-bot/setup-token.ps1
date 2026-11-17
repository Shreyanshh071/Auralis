$ErrorActionPreference = 'Stop'
$envPath = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
if (-not [string]::IsNullOrWhiteSpace($env:DISCORD_BOT_TOKEN)) { exit 0 }
if (Test-Path -LiteralPath $envPath) {
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line.Trim().StartsWith('DISCORD_BOT_TOKEN=')) {
            $existing = $line.Trim().Substring('DISCORD_BOT_TOKEN='.Length).Trim().Trim('"', "'")
            if ($existing -and $existing -ne 'your-token') { exit 0 }
        }
    }
}
Write-Host 'The bot needs its Discord bot token once before it can sign in.'
Write-Host 'Get it from Discord Developer Portal > your application > Bot > Reset Token.'
Write-Host 'Paste it below. It stays hidden and is saved in the ignored root .env file.'
$secret = Read-Host 'Discord bot token' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr).Trim()
    if ([string]::IsNullOrWhiteSpace($token) -or $token -eq 'your-token' -or $token -match '\s') {
        Write-Host 'No valid token entered. Run start-bot.bat again to retry.'
        exit 1
    }
    $lines = if (Test-Path -LiteralPath $envPath) { @([IO.File]::ReadAllLines($envPath)) } else { @() }
    $lines = @($lines | Where-Object { -not $_.Trim().StartsWith('DISCORD_BOT_TOKEN=') })
    $lines += 'DISCORD_BOT_TOKEN=' + $token
    [IO.File]::WriteAllLines($envPath, [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Token saved. Starting the bot.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    $token = $null
    $secret.Dispose()
}
