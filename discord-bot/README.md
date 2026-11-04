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

## Commands

Type these in any channel the bot can read. Upper or lower case both work.

| Command | What it does |
| --- | --- |
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
