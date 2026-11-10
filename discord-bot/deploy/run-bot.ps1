# Keeps the bot running: starts it, and starts it again 10 seconds after it stops. Used by the
# scheduled task from install-windows-task.ps1; the working folder holds the jar and .env.
param([Parameter(Mandatory)] [string] $Java)
$log = Join-Path $PSScriptRoot 'bot.log'
while ($true) {
    if ((Test-Path $log) -and (Get-Item $log).Length -gt 10MB) { Move-Item $log "$log.old" -Force }
    "[$(Get-Date -Format s)] starting" | Add-Content $log
    & $Java -Xms128m -Xmx768m -XX:+ExitOnOutOfMemoryError -jar (Join-Path $PSScriptRoot 'auralis-discord-bot.jar') *>> $log
    "[$(Get-Date -Format s)] stopped with exit code $LASTEXITCODE; restarting in 10s" | Add-Content $log
    Start-Sleep -Seconds 10
}
