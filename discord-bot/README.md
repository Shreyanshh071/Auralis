# Auralis Discord bot

Plays music in voice channels using Auralis's own search, stream lookup, radio and lyrics (from `:windows-core`).

## Setup

The bot has its own Discord application (ID `1566068079405039697`), separate from Auralis's Rich Presence.

1. Open that application at <https://discord.com/developers/applications>, go to **Bot**, and reset the token to get one.
   On the same page, turn on **Message Content Intent**: commands are read from chat, and Discord refuses the login without it. Past 100 servers, Discord has to approve this.
2. Add the token to the repo's `.env` file (which Git ignores):

   ```
   DISCORD_BOT_TOKEN=your-token
   ```

3. Invite the bot:

   ```
   https://discord.com/oauth2/authorize?client_id=1566068079405039697&scope=bot+applications.commands&permissions=3165184
   ```

## Build and run

The build uses JDK 21 like the rest of the repo. The bot itself needs **Java 25 or newer**, because Discord requires encrypted voice and JDAVE, the library providing it, targets Java 25.

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" :discord-bot:botJar
& "C:\Program Files\Java\jdk-27\bin\java.exe" -jar discord-bot\build\libs\auralis-discord-bot.jar
```

Run it from the repo root so it finds `.env`, or set `DISCORD_BOT_TOKEN` in the environment. The bot only works while this process is running. The jar includes Windows and Linux (x86-64, ARM64) voice libraries.

To test search, stream lookup and decoding without Discord:

```powershell
& "C:\Program Files\Java\jdk-27\bin\java.exe" -jar discord-bot\build\libs\auralis-discord-bot.jar --check "song name"
```

## Keeping it online 24/7

Run on your own PC, the bot is offline whenever that PC or window is closed. To have it always answer `auplay` like public music bots do, run the same jar on a machine that never sleeps. Everything for that is in [`deploy/`](deploy/).

### Which machine

| Option | Cost | Good | Watch out for |
| --- | --- | --- | --- |
| **Oracle Cloud Always Free VM + systemd** (recommended) | Free | Real always-on Linux server; the Ampere (ARM) shape gives up to 24 GB RAM, and the jar already carries ARM64 voice libraries. Discord voice only needs outbound UDP, which Oracle allows by default. | YouTube sometimes blocks datacenter IPs ("sign in to confirm you're not a bot"). Test with `--check` first (step 6). ARM capacity is sometimes "out of capacity" in a region; the 1 GB AMD micro shape works too. Oracle may reclaim Always Free VMs that sit nearly idle for a week; upgrading the account to Pay As You Go (still free within the limits) avoids that. |
| Always-on home PC or Raspberry Pi | Electricity | Home IP, which YouTube treats best. Windows: `install-windows-task.ps1`. Pi or Linux box: the same `install-server.sh` as the VPS. | Goes down with the PC, the power or the home internet. |
| Docker on any small VPS (Hetzner, DigitalOcean, …) | ~$4–6/month | `compose.yaml` here; nothing else on the server but Docker. | Same datacenter-IP risk as Oracle. Pick 1 GB RAM or more. |
| Railway, Fly.io | ~$5+/month, no free tier | No server to look after. | Small default memory (a JVM with audio decoding wants ~400–700 MB), the same datacenter-IP risk, and you pay for what Oracle gives free. |

**Resources:** the JVM idles around 250–400 MB and grows with servers playing at once; the default `-Xmx768m` suits a 1 GB server. Each playing server costs a stream lookup when a song starts (a few hundred ms of CPU) and steady decoding and Opus encoding while it plays, which a single ARM core handles for many servers. No inbound ports need opening.

**Only one copy at a time.** Once the server copy is online, close the `.bat` window on the old PC. Two copies with the same token both answer every command.

### Oracle Cloud step by step

You do these yourself: the account, the VM and the token are yours.

1. **Account.** Sign up at <https://www.oracle.com/cloud/free/> (it asks for a card to verify you; Always Free resources are not charged). Pick a home region near your Discord voice region; it can't be changed later.
2. **VM.** In the console: *Compute → Instances → Create instance*.
   - Image: **Canonical Ubuntu 24.04** (or 22.04).
   - Shape: *Change shape → Ampere → VM.Standard.A1.Flex*, 1 OCPU and 6 GB RAM (more is free up to 4 OCPU / 24 GB). If it says out of capacity, use *Specialty and previous generation → VM.Standard.E2.1.Micro* instead.
   - SSH keys: *Generate a key pair for me* and **save the private key**, e.g. to `C:\Users\<you>\.ssh\oracle-bot.key`.
   - Create, and copy the instance's **Public IP address**.
3. **Lock the key file down** so Windows' `ssh` accepts it (PowerShell):

   ```powershell
   icacls "$HOME\.ssh\oracle-bot.key" /inheritance:r /grant:r "${env:USERNAME}:R"
   ```

4. **Install the bot** from the repo root on your PC (builds the jar, uploads it, installs Java 25 and the service):

   ```powershell
   .\discord-bot\deploy\push-to-server.ps1 -Server ubuntu@<public-ip> -Key $HOME\.ssh\oracle-bot.key
   ```

5. **Add the token** on the server. Use the same token as in your `.env`, or reset it on the Developer Portal's **Bot** page (that also logs out the old PC copy):

   ```powershell
   ssh -i $HOME\.ssh\oracle-bot.key ubuntu@<public-ip>
   sudo nano /etc/auralis-discord-bot.env    # replace paste-the-token-here, Ctrl+O, Enter, Ctrl+X
   ```

6. **Check YouTube works from this IP** before starting, still on the server:

   ```sh
   sudo -u auralis-bot sh -c 'cd /var/lib/auralis-discord-bot && java -jar /opt/auralis-discord-bot/auralis-discord-bot.jar --check "Blinding Lights"'
   ```

   All of `SEARCH`, `STREAM`, `LOAD`, `DECODE` and `DAVE` should report songs, `ok` or frames. If `SEARCH` finds the song but `STREAM` says `none` or `LOAD` fails, YouTube is blocking this IP: run the bot from an always-on home machine instead (below).
7. **Start it** and watch the log until it says `Auralis is online as …`:

   ```sh
   sudo systemctl restart auralis-discord-bot
   journalctl -u auralis-discord-bot -f      # Ctrl+C stops watching, not the bot
   ```

8. Close the `.bat` window on the old PC. In Discord, join a voice channel and type `auplay`.

The service starts on boot and restarts 10 seconds after any crash. Day to day:

| Task | Command |
| --- | --- |
| Update after code changes | `.\discord-bot\deploy\push-to-server.ps1 -Server ubuntu@<ip> -Key $HOME\.ssh\oracle-bot.key` (on your PC) |
| Logs | `journalctl -u auralis-discord-bot -n 200` |
| Stop, start, restart | `sudo systemctl stop`/`start`/`restart auralis-discord-bot` |
| Change the token or heap | `sudo nano /etc/auralis-discord-bot.env`, then restart |

### Docker instead

On any Linux server with Docker: put `auralis-discord-bot.jar` (from `discord-bot/build/libs/`), `Dockerfile` and `compose.yaml` in one folder, copy `bot.env.example` there as `.env` with the token, then:

```sh
docker compose run --rm bot --check "Blinding Lights"   # same check as step 6
docker compose up -d --build
docker compose logs -f
```

The image is built from the jar rather than the source, because the repo's Gradle build also sets up the Android app and needs its SDK.

### Always-on Windows PC

On a PC that stays on, run the bot as a startup task so nobody has to log in or keep a window open. From an administrator PowerShell at the repo root, with the token in the repo's `.env`:

```powershell
.\gradlew.bat :discord-bot:botJar
.\discord-bot\deploy\install-windows-task.ps1 -Java "C:\Program Files\Java\jdk-27\bin\java.exe"
```

It copies the jar and token to `C:\ProgramData\AuralisDiscordBot` (readable by administrators only), starts it at boot as SYSTEM and restarts it when it stops. The log is `bot.log` there. Run the same two commands to update. Set Windows to never sleep (*Settings → System → Power*), or the bot sleeps with it.

## Commands

Type these in any channel the bot can read. Upper or lower case both work.

| Command | What it does |
| --- | --- |
| `auplay` | Play or queue a random mix of up to 10 songs from YouTube Music |
| `auplay <song or link>` | Play or queue a song, or a whole playlist or album link |
| `auplay shuffle <link>` | Same, with the playlist or album shuffled; a single song just plays |
| `auplaynext <song or link>` | Play next, at the front of the queue; also takes `shuffle` |
| `auplaylist <name>` | Find a YouTube Music playlist and queue it |
| `ausearch <song>` | Pick from the top results |
| `aupause`, `auresume` | Pause, resume |
| `auskip`, `austop`, `auleave` | Skip, stop and clear, leave |
| `auseek <1:30>`, `auvolume <0-150>` | Jump within a song, set the volume |
| `auqueue`, `aunp`, `aulyrics` | Show the queue, current song, lyrics |
| `aushuffle`, `auloop <off/song/queue>` | Shuffle, set looping |
| `auremove <position>`, `aumove <from> <to>` | Edit the queue |
| `auauto` | Continue with Auralis radio when the queue ends |
| `aucommands`, `auhelp` | Show this list |

Commands and buttons that change playback or the queue only work for people in the bot's voice channel. Anyone can use `aucommands`, `ausearch`, `auqueue`, `aunp` and `aulyrics`, which only show information. The bot leaves after 5 minutes idle: nothing playing, paused, or nobody else in the channel.

Playlists can be public or unlisted. Private playlists can't load, because the bot has no YouTube account. YouTube's auto-generated Mix links play only the linked song.
