# Auralis Windows feature audit

Updated 11 October 2026 for Windows 1.1.3. Android remains at its existing version.

## This batch

| Feature | Windows behaviour | Verification |
|---|---|---|
| History | Opens from Home and sidebar; replay, remove and clear; pause-history respected | UI render and existing JSON history |
| Stats | Measures actual playing time; week/month/year/all-time; songs/artists/albums; clear confirmation | Persistence and pause/buffering/sleep tests |
| Wrapped | Basic current-year listening summary | UI render; not the phone's animated story cards |
| Library Top/weekly/monthly | Uses the new measured sessions; Top length and Most playlists preferences applied | Shared Library logic with desktop stats adapter |
| Listen Together | Create/join, roster, host playback, exact recording, pause/seek/speed, guest recommendations and approval, end/leave | Live temporary-room protocol and two muted-player integration check |
| Email account | Sign in/create, reset password, sign out, restore protected session | REST contract tests and Windows DPAPI round trip; real credentials require user sign-in |
| Google account | System-browser sign-in with PKCE and state-checked loopback callback | Local callback success/error checks; real Google consent requires user sign-in |

Account sign-in does **not** yet sync the PC library with the phone. YouTube Music and Spotify account-library sign-in are separate features and still unavailable. Playlist links and JSON library backup/import remain available. No password is saved; the Firebase refresh token is protected for the current Windows user. Owner-supplied Google desktop configuration is in ignored `windows/local-resources/google-desktop.json` and is bundled locally when building. A fresh checkout must supply this file to enable Google sign-in.

## Settings audit

| Page / option | Applied on Windows | Remaining limits |
|---|---|---|
| Privacy: pause listen history | Stops new history entries/play counts and measured listening stats | Previous records remain until cleared |
| Privacy: pause search history | Search still works, but submitted searches aren't saved | Previous recent searches remain until cleared |
| Privacy: clear history/search | Clears the corresponding local repository | Stats have their own separate Clear stats confirmation |
| Privacy: disable screenshot | Calls Windows SetWindowDisplayAffinity for Auralis's own window | OS capture protection, not protection against cameras or every third-party capture method |
| Storage: artwork cache | Real disk/memory cache size and clear | Changing maximum size applies after restart |
| Storage: saved lyrics | Real file size and targeted clear of generated lyric JSON | Currently displayed/in-memory lyrics remain until reloaded/restarted |
| Storage: stream lookups | Clears resolved URLs | Audio streams use memory; offline downloads/audio-file cache aren't implemented |
| Content: metadata language/country | Used by shared InnerTube request context | Some internal parsing requests intentionally stay English |
| Content: app language | Loads bundled phone translations and persists selection | Newly added Windows-specific text is English |
| Content: proxy | Installs the shared proxy selector and credentials handler | Existing pooled connections can finish on their previous route; an external proxy must be reachable |
| Content: playlist insertion position | Desktop library inserts at beginning/end | |
| Content: artist details | Shared artist page honours description/subscriber/monthly-audience switches | Provider metadata can be absent |
| Content: lyric providers | Shared provider race honours disabled providers | Cached lyrics may still be displayed |
| Content: romanization | Desktop lyrics draws the shared romanizer's extra line and selected scripts | Same script/dictionary limitations as phone |
| Content: Home ordering/Quick picks/Wrapped | Shared Home logic honours preferences | Wrapped opens the basic summary |
| Content: Top length/Most playlists | Shared Library uses desktop measured statistics | Older play counts cannot reconstruct listening time |
| Player: stream quality | Next extraction selects bitrate nearest the preference; Auto chooses highest available | Auto doesn't infer Wi-Fi/mobile on Windows; cached native media is unaffected |
| Player: speed | Applies to VLC and smooth lyrics clock | Guest speed follows the room host |
| Player: next-stream preloading | Resolves the next stream ahead | Does not promise sample-accurate gapless transitions |
| Appearance: theme/curated palette | Applied live through desktop theme | Wallpaper Material You unavailable |
| Appearance: artwork colours | Extracts colours from current cover and feeds desktop theme | Requires artwork to load; curated palettes take precedence |
| Appearance: background/artwork crop/hide | Applied in Windows player | Blur/Gradient/Follow theme only |
| Appearance: seek style/black mini-player | Applied to player and bar | |
| Appearance: Metro lyrics | Alignment, size, spacing, blur, tapping and automatic scrolling applied | Alternative phone lyric renderers not ported |
| Appearance: default tab/density | Default tab at startup; density applied live | Large density may require scrolling |

Windows audio/appearance pages now expose their supported controls and explicitly name the remaining gaps. Not implemented: silence skipping, spatial audio, queue restoration, automatic radio extension, Bluetooth reconnect, system-mute pause, keep-screen-awake, alternative phone player/mini-player designs, animated backgrounds and refraction shaders. Updater still requires a new installer. Sharing selected lyrics supports copying text; image share-card rendering is not ported.

## Existing Windows features retained

Home, Search with typing suggestions, Album/Artist pages, Library/playlist management, link imports, JSON backup/import, playback/queue/media keys, Windows media overlay, word-synced lyrics, Discord local status and bundled VLC. Downloads, voice/song recognition, Explore/moods browsing, account library sync and the animated Wrapped presentation remain further work.

## Android boundary

The account/settings batch changed Windows and shared desktop sources only. The subsequent Library fix adds two optional shared-screen hooks (source classification and empty-state content), supplied only by Windows; their null defaults retain Android's existing rendering and filtering. Android compilation passes. Android sources and the installed phone app are unchanged; no phone APK was built for the Library fix.

## Library filters (Windows 1.1.4)

- Spotify and YouTube Music pills filter imported playlists. New imports retain stable provider IDs, so reimport updates the same playlist. Older random-ID imports are recognised when their description explicitly records the source; a source isn't guessed from a playlist title or artwork.
- Albums includes saved albums, opens the provider's album page, and removes saved albums through the album store. Album/artist pages now connect saving, subscribing, favourites and adding tracks to existing playlists.
- Empty filters/searches show an explanation; source filters offer a route to playlist import. Tapping the selected pill again clears the filter.
- Library header History and Listen Together open their implemented Windows destinations.

## Development preview

Run the existing `:windows:run` task from the repository root. Its window is titled **Auralis Dev**, and uses `%APPDATA%/Auralis Dev` rather than the installed app's settings/library. Installed self-tests similarly use `Auralis Self Test`. Installed Auralis continues to use `%APPDATA%/Auralis`.
