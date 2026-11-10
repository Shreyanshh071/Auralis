#!/bin/sh
# Installs or updates the Auralis Discord bot as a systemd service on Ubuntu or Debian (x86-64 or ARM64).
#
#   sudo sh install-server.sh /path/to/auralis-discord-bot.jar
#
# First run: installs Java 25, creates the auralis-bot user, the service and /etc/auralis-discord-bot.env,
# then stops so you can paste the token. Later runs replace the jar and restart the bot.
set -eu

JAR=${1:-auralis-discord-bot.jar}
HERE=$(cd "$(dirname "$0")" && pwd)
ENV_FILE=/etc/auralis-discord-bot.env

[ "$(id -u)" -eq 0 ] || { echo "Run with sudo." >&2; exit 1; }
[ -f "$JAR" ] || { echo "No jar at $JAR. Copy discord-bot/build/libs/auralis-discord-bot.jar here first." >&2; exit 1; }

# Java 25 or newer: JDAVE, Discord's voice encryption, needs it. Temurin comes from Adoptium's apt repo.
if ! java -version 2>&1 | grep -Eq 'version "(2[5-9]|[3-9][0-9])'; then
    echo "Installing Java 25 (Eclipse Temurin)..."
    apt-get update
    apt-get install -y wget apt-transport-https gpg
    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /usr/share/keyrings/adoptium.gpg
    . /etc/os-release
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" > /etc/apt/sources.list.d/adoptium.list
    apt-get update
    apt-get install -y temurin-25-jre
    # Make /usr/bin/java the new one if an older Java was already installed.
    update-alternatives --set java "$(update-alternatives --list java | grep temurin-25 | head -n1)" 2>/dev/null || true
fi
java -version

id auralis-bot >/dev/null 2>&1 || useradd --system --home-dir /var/lib/auralis-discord-bot --create-home --shell /usr/sbin/nologin auralis-bot

install -d -o root -g root -m 755 /opt/auralis-discord-bot
install -o root -g root -m 644 "$JAR" /opt/auralis-discord-bot/auralis-discord-bot.jar
install -o root -g root -m 644 "$HERE/auralis-discord-bot.service" /etc/systemd/system/auralis-discord-bot.service
systemctl daemon-reload
systemctl enable auralis-discord-bot >/dev/null

if [ ! -f "$ENV_FILE" ]; then
    install -o root -g root -m 600 "$HERE/bot.env.example" "$ENV_FILE"
    echo
    echo "Now put the bot token in $ENV_FILE:"
    echo "    sudo nano $ENV_FILE"
    echo "then start the bot with:"
    echo "    sudo systemctl restart auralis-discord-bot"
    exit 0
fi

if grep -q '^DISCORD_BOT_TOKEN=paste-the-token-here' "$ENV_FILE"; then
    echo "The token in $ENV_FILE is still the placeholder. Edit it, then: sudo systemctl restart auralis-discord-bot" >&2
    exit 1
fi

systemctl restart auralis-discord-bot
sleep 15
systemctl --no-pager --lines=15 status auralis-discord-bot || true
