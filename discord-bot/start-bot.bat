@echo off
setlocal
cd /d "%~dp0.."

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-token.ps1"
if errorlevel 1 (
    pause
    exit /b 1
)

rem Set BOT_JAVA_HOME to override automatic Java 25+ detection.
set "BOT_JAVA="
for /f "usebackq delims=" %%J in (`powershell.exe -NoProfile -Command "$c = @(); if ($env:BOT_JAVA_HOME) { $c += Join-Path $env:BOT_JAVA_HOME 'bin\java.exe' }; if ($env:JAVA_HOME) { $c += Join-Path $env:JAVA_HOME 'bin\java.exe' }; $c += (Get-Command java.exe -ErrorAction SilentlyContinue).Source; foreach ($root in @('Java','Eclipse Adoptium','Microsoft','Zulu')) { $c += (Get-ChildItem (Join-Path $env:ProgramFiles ($root + '\*\bin\java.exe')) -ErrorAction SilentlyContinue).FullName }; foreach ($j in ($c | Select-Object -Unique)) { if ($j -and (Test-Path -LiteralPath $j)) { $v = (& $j -version 2>&1 | Out-String); if ($v -match 'version \x22(\d+)' -and [int]$Matches[1] -ge 25) { Write-Output $j; break } } }"`) do set "BOT_JAVA=%%J"

if not defined BOT_JAVA (
    echo Java 25 or newer is required to run the bot.
    echo Install it, or set BOT_JAVA_HOME to its installation folder, then try again.
    pause
    exit /b 1
)

if not exist "discord-bot\build\libs\auralis-discord-bot.jar" (
    echo Building the bot for the first run...
    call gradlew.bat :discord-bot:botJar --console=plain
    if errorlevel 1 (
        echo Build failed. See the error above.
        pause
        exit /b 1
    )
)

echo Starting Auralis with "%BOT_JAVA%"
echo Keep this window open while the bot is running. Press Ctrl+C to stop.
"%BOT_JAVA%" -Xms128m -Xmx768m -jar "discord-bot\build\libs\auralis-discord-bot.jar" %*
set "BOT_EXIT=%ERRORLEVEL%"
echo.
echo Bot stopped with exit code %BOT_EXIT%.
pause
exit /b %BOT_EXIT%
