<#
Runs the bot on an always-on Windows PC without anyone logged in: a scheduled task starts it at boot as
SYSTEM and run-bot.ps1 restarts it whenever it stops. Run from an administrator PowerShell:

  .\discord-bot\deploy\install-windows-task.ps1 -Java "C:\Program Files\Java\jdk-27\bin\java.exe"

The jar and a copy of .env go to C:\ProgramData\AuralisDiscordBot (readable by SYSTEM and administrators
only). Run it again after rebuilding the jar to update. Remove with: Unregister-ScheduledTask "Auralis Discord Bot"
#>
param(
    [Parameter(Mandatory)] [string] $Java,
    [string] $EnvFile
)
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path "$PSScriptRoot\..\.."
$jar = Join-Path $repo 'discord-bot\build\libs\auralis-discord-bot.jar'
$home_ = Join-Path $env:ProgramData 'AuralisDiscordBot'
$taskName = 'Auralis Discord Bot'

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrators')) { throw 'Run this from an administrator PowerShell.' }
if (-not (Test-Path $Java)) { throw "No java.exe at $Java (the bot needs Java 25 or newer)" }
if (-not (Test-Path $jar)) { throw "Build the jar first: .\gradlew.bat :discord-bot:botJar" }
if (-not $EnvFile) { $EnvFile = Join-Path $repo '.env' }

New-Item -ItemType Directory -Force $home_ | Out-Null
# Only SYSTEM and administrators can read the folder, since it holds the token.
& icacls $home_ /inheritance:r /grant:r 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' | Out-Null

Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
# Stopping the task can leave its java.exe running, which would keep the old jar locked.
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
    Where-Object { $_.CommandLine -like "*AuralisDiscordBot*auralis-discord-bot.jar*" } |
    ForEach-Object { Invoke-CimMethod -InputObject $_ -MethodName Terminate | Out-Null }
Copy-Item $jar (Join-Path $home_ 'auralis-discord-bot.jar') -Force
Copy-Item "$PSScriptRoot\run-bot.ps1" (Join-Path $home_ 'run-bot.ps1') -Force
$envTarget = Join-Path $home_ '.env'
if (-not (Test-Path $envTarget)) {
    if (-not (Test-Path $EnvFile) -or -not (Select-String -Path $EnvFile -Pattern '^DISCORD_BOT_TOKEN=.+' -Quiet)) {
        throw "No DISCORD_BOT_TOKEN in $EnvFile. Add it there (or pass -EnvFile) and run again."
    }
    Select-String -Path $EnvFile -Pattern '^DISCORD_BOT_TOKEN=' | Select-Object -First 1 | ForEach-Object { $_.Line } | Set-Content $envTarget
}

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory $home_ `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$home_\run-bot.ps1`" -Java `"$Java`""
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -StartWhenAvailable -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $taskName
Write-Host "Started '$taskName'. Log: $home_\bot.log"
