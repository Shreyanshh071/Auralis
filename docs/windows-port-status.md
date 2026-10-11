# Auralis Windows feature audit

Updated 11 October 2026. Windows project version is 1.0.0; Android keeps its existing version.

## Owner's development and update preferences

- The mini-player button after Next is now Repeat rather than Lyrics: cycles Off → All → One. Active repeat uses the selected theme accent and a status dot; inactive repeat uses the theme's neutral icon colour. Repeat is disabled during Listen Together rooms to preserve host queue control. Automatic playback repeats a recording or wraps the queue as selected; three transition tests cover Off/All/One, singleton and empty queues. Android code is unchanged.
- Installed and reopened the Repeat update at 13:16 on 11 October, version 1.0.0. All 21 Windows tests passed; installed code verified, existing shortcut and 21 checked non-cache data files preserved. Backup: `Downloads/Auralis-checkpoints/before-repeat-20261011-131617`.

- Mini-player correction: the song-info block must have no hover highlight (owner clarified after the first hover styling). Only the cover opens the player; title/artist and the rest of the block are not interactive.
- Installed that correction at 13:10 on 11 October, version 1.0.0; build passed, installed code verified, app reopened, 21 checked non-cache data files and shortcut preserved. Backup: `Downloads/Auralis-checkpoints/before-no-song-highlight-20261011-131021`.

- Keep the Windows version at **1.0.0** until the owner explicitly requests a version increase.
- After each completed, verified change, automatically update the existing installed app so the owner can check it (requested 11 October). Preserve settings and library, keep the existing shortcut, and do not put a fresh EXE in Downloads for each change.
- Use an internal MSI if packaging is required. Keep Windows at 1.0.0 and close/reopen the existing app normally when applying the update.
- The installed copy was updated to 1.0.0 at the owner's request on 11 October. Its existing shortcut and all 23 checked data files were preserved.

## Window frame behaviour

- The compact dark title bar now sits over Windows' native resizable frame. Native caption hit testing handles dragging, double-click maximize/restore and the maximize button's Snap layouts target; minimize/maximize buttons send Windows system commands.
- `:windows:verifyWindowFrame` opens its own disposable window without loading any account, library or playback data. Verified native caption/resize hit tests, child-canvas forwarding, maximize client bounds against monitor work area, unclipped canvas, Compose placement tracking, original bounds on restore, caption double click and minimize. Existing Windows tests also pass.
- Native callbacks remain retained until disposal and original AWT procedures are restored. Normal title-bar buttons remain client controls. The existing minimum window size still applies to smaller Snap zones.
- Installed together with the desktop Home redesign on 11 October. Windows stays at 1.0.0.
- Follow-up: custom maximize/restore now handles native mouse press/release explicitly, including cancellation when released outside the button. Its native hover is forwarded to Compose; title-bar highlights and Home card highlights/play controls fade over 150 ms. The frame integration check now exercises actual button messages rather than only direct system commands. Windows tests and the frame check pass; Android code is unchanged.
- Installed the hover/button fix on 11 October at 12:28, still version 1.0.0. Existing shortcut retained and all 251 checked app-data files unchanged; backup: `Downloads/Auralis-checkpoints/before-hover-fix-20261011-122756`. Installed archive matches the new release output. App reopened normally.

## Desktop Home redesign

- Playing-song artwork now supplies a blurred app backdrop and animated tinted browsing surfaces, including the existing sidebar and mini-player. Existing top bar and sidebar destinations are retained. Wide windows (at least 1180 dp) show a 290–340 dp right-hand now-playing panel with cover, title/artist and compact Metro lyrics using the same smooth clock. Narrow windows retain the full-player lyrics entry. Artwork size adapts to height to leave space for lyrics; expand opens the full player. Verified Windows tests and an isolated layout render. Android code is unchanged.
- Installed and reopened this update at 12:53 on 11 October, version 1.0.0. Installed code matches release output; existing shortcut and all 20 checked non-cache data files are unchanged. Full data backup: `Downloads/Auralis-checkpoints/before-artwork-sidebar-20261011-125303`.

- Mini-player: the song-info block now has a 150 ms hover highlight, with only its cover clickable to open the player. Transport layout is shuffle-upcoming, previous, white circular play/pause, next and lyrics; queue and volume sit on the right. Enabled transport icons stay white independent of artwork/theme colours. Shuffle preserves current/past songs and is unavailable in active rooms. Windows build/tests passed; Android code is unchanged.
- Installed the mini-player update at 12:42 on 11 October, still version 1.0.0, and reopened it. Installed code matches release output. Backup: `Downloads/Auralis-checkpoints/before-playerbar-20261011-124215`. Library/history/settings/account files stayed unchanged; search lookup cache, artwork journal and two Home recommendation caches changed during the update. These cache changes were checked before reopening.

- Search is removed from the sidebar. Home's larger 176 × 54 dp Find music button opens an anchored dropdown using the existing recent-query and live-song suggestion logic. Song clicks play directly; submitting a query opens full search results. Outside click dismisses the dropdown. Existing Ctrl+F and search result navigation remain available. Windows tests and a narrow Home render passed; Android code is unchanged.
- Installed this change at 12:35 on 11 October, version 1.0.0. Existing shortcut and all 289 checked data files preserved; backup: `Downloads/Auralis-checkpoints/before-find-music-20261011-123525`. Installed code matches release output and the app was reopened.

- Windows has its own desktop layout over the existing Home recommendation engine: greeting, eight compact shortcuts, multi-column Quick picks, horizontally browsable discovery/music shelves and a vertical scrollbar. Removed duplicate Stats, History, Listen Together and Profile buttons from the Home header; their sidebar entries remain.
- Starter content is curated rather than fully random. Actual recent/top plays replace starter rotation; pinned music stays. Songs are deduplicated across Home shelves. Mood chips show their filtered feed without unrelated local rotation.
- Fresh installs explain that recommendations use listening history. Related discovery/music shelves refresh through the existing Home loading/refresh pipeline; not every upstream YouTube shelf is locally personalized. Pause-listening-history settings prevent new history from becoming recommendation signals.
- Inspected LiMusic source at ff7025db316762430897c1502ce14f519833fa54: it combines the YouTube home feed with local recent items/artists and artist-seeded community playlists. Auralis uses its own Compose implementation; no LiMusic code copied.
- Verified wide/narrow/new-user preview renders and tests for starter-to-personal rotation, persistent pins, cross-shelf duplicates, and mood-filter isolation. Android Home and shared recommendation code are unchanged.
- Installed on 11 October after 18 Windows tests passed. Same-version MSI repair returned 1603 (SecureRepair), so the known package was normally removed and the new internal MSI installed; both operations succeeded. Existing shortcut retained, all 158 checked app-data files unchanged, backup in `Downloads/Auralis-checkpoints/before-desktop-home-20261011-121302`. No EXE was copied to Downloads.

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

- Windows Appearance now uses responsive visual theme preview cards, colour swatches with selected checks, and player-background previews instead of the three dropdowns. Liquidify is a separate optional, persisted desktop setting (`desktop-background.properties`), off by default. On: artwork supplies the whole browsing backdrop; off: selected theme/colour palette controls normal surfaces. Liquidify respects light/dark mode. Sidebar metadata contrast also follows theme; the synced lyrics area keeps a readable dark surface. Windows tests and Appearance render passed. No Android settings or code changed.
- Installed this update at 13:03 on 11 October and reopened Auralis. Still version 1.0.0, installed code matches release output, existing shortcut and 20 checked non-cache data files unchanged. Backup: `Downloads/Auralis-checkpoints/before-theme-picker-20261011-130306`.

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

### Liquidify search dropdown (Windows 1.0.0)

Find music uses a blurred current-cover backdrop when Liquidify is enabled, with a readability scrim and a subtle border. Search text and suggestion rows remain sharp. Other modes retain the normal surface. The existing artwork crossfade follows song changes; this does not use native compositor backdrop blur. Windows compilation and the disposable `search-glass` visual preview passed. Android is unchanged.

Installed 11 October at 13:25 after the release package passed. Installed code matches the packaged archive, the shortcut target is unchanged, and all 21 checked non-cache user files were preserved. Full app-data backup: `Downloads/Auralis-checkpoints/before-search-glass-20261011-132507`. Relaunched successfully with bundled VLC and version 1.0.0.

Windows 1.1.5 replaces the standard light title bar with a compact dark bar: centred Auralis title, logo/Home, Back/Forward navigation, page refresh where supported, account/Profile, Listen Together, Settings and minimize/maximize/restore/close. The bar is draggable and remains above the full player. Navigation keeps album/artist pages and updates metadata without adding a history step. This change is Windows-only.

Run the existing `:windows:run` task from the repository root. Its window is titled **Auralis Dev**, and uses `%APPDATA%/Auralis Dev` rather than the installed app's settings/library. Installed self-tests similarly use `Auralis Self Test`. Installed Auralis continues to use `%APPDATA%/Auralis`.
