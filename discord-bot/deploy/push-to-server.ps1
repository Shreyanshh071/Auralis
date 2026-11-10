<#
Builds the bot jar on this PC and installs or updates it on a Linux server over SSH.

  .\discord-bot\deploy\push-to-server.ps1 -Server ubuntu@203.0.113.10 -Key $HOME\.ssh\oracle-bot.key

The first run installs Java and the service, then asks you to put the token on the server.
Later runs replace the jar and restart the bot. Uses Windows' built-in ssh and scp.
#>
param(
    [Parameter(Mandatory)] [string] $Server,
    [string] $Key,
    [switch] $SkipBuild
)
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path "$PSScriptRoot\..\.."
$jar = Join-Path $repo 'discord-bot\build\libs\auralis-discord-bot.jar'

if (-not $SkipBuild) {
    Push-Location $repo
    try {
        & .\gradlew.bat :discord-bot:botJar --console=plain
        if ($LASTEXITCODE -ne 0) { throw "botJar failed" }
    } finally { Pop-Location }
}
if (-not (Test-Path $jar)) { throw "No jar at $jar" }

$sshArgs = @()
if ($Key) { $sshArgs += @('-i', $Key) }
$remote = 'auralis-bot-upload'
$files = @($jar) + (Get-ChildItem "$PSScriptRoot\install-server.sh", "$PSScriptRoot\auralis-discord-bot.service", "$PSScriptRoot\bot.env.example").FullName

& ssh @sshArgs $Server "mkdir -p ~/$remote"
if ($LASTEXITCODE -ne 0) { throw "ssh to $Server failed" }
& scp @sshArgs @files "${Server}:~/$remote/"
if ($LASTEXITCODE -ne 0) { throw "scp failed" }
& ssh @sshArgs -t $Server "sudo sh ~/$remote/install-server.sh ~/$remote/auralis-discord-bot.jar"
