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

### Liquidify transparency and right-panel spacing (Windows 1.0.0)

Liquidify's dark background and surface overlays are reduced to 16% and 24% opacity, with translucent card surfaces and 48 dp artwork blur so the cover's soft shapes remain visible. Light mode retains a stronger readability tint; non-Liquidify backgrounds are unchanged. The expanded right panel no longer reserves a 40 dp button strip: collapse is in its header, and its left padding plus resize handle form an 8 dp gap. The collapsed reopen rail remains 40 dp. Width preferences and animations are retained. Compilation and the artwork-backed desktop preview passed; Android is unchanged.

Release packaging passed and installed copy updated/reopened at 14:23 on 11 October. Installed archive matches the package; shortcut and all 24 checked non-cache user files were preserved. Full data backup: `Downloads/Auralis-checkpoints/before-glass-spacing-20261011-142241`. Version remains 1.0.0.

### Resizable and collapsible sidebars (Windows 1.0.0)

Both sidebar edges can be dragged. Left navigation ranges from 180–320 dp and collapses to a 64 dp icon rail. Now playing ranges from 280–460 dp (including its toggle rail) and collapses to a 40 dp reopen rail. Width limits reserve space for the main page; narrow windows temporarily fold the right panel. Chevron buttons collapse/expand with 280 ms width and text/content fade transitions; dragging tracks directly. Preferences are saved in `desktop-sidebars.properties`, separately from phone settings. Windows tests: 26 passed, including width bounds; expanded/collapsed previews and final compilation passed. Android is unchanged.

Release packaging passed. Installed copy updated at 13:58 on 11 October, then reopened successfully. Installed archive matches the packaged code; shortcut and all 23 checked non-cache user files were preserved. Full data backup: `Downloads/Auralis-checkpoints/before-sidebars-20261011-135817`. Version remains 1.0.0.

### Dedicated themes and colours page (Windows 1.0.0)

Appearance now opens Themes and colours through a single entry. The dedicated desktop page contains a live composition of the resolved theme colours, circular theme-mode previews, three-colour palette swatches, Liquidify and the dynamic artwork-colour switch. Choices use the existing settings stores and apply live; Back returns to Appearance. Player background and lyrics controls remain in Appearance. Compilation and the isolated page preview passed; Android is unchanged.

Release package passed and installed copy updated at 13:49 on 11 October. Installed archive matches the packaged code; all 23 checked non-cache user files and shortcut target were preserved. Full data backup: `Downloads/Auralis-checkpoints/before-themes-page-20261011-134908`. Version remains 1.0.0.

### Desktop song context menus (Windows 1.0.0)

Artwork-style restoration: the desktop popup now receives the original sheet background composable from the shared song menu. It reuses the song's own cover-colour field, dark floor, white text and translucent rounded action rows while retaining click-adjacent positioning. Windows preview and Windows/Android compilation passed. The Android fallback is unchanged.

Release package passed; installed copy updated and reopened at 14:16 on 11 October. Installed archive matches the packaged code, and shortcut plus all 24 checked non-cache user files were preserved. Full data backup: `Downloads/Auralis-checkpoints/before-menu-style-20261011-141611`. Version remains 1.0.0.

Windows supplies a compact, scrollable popup for shared song actions. It opens at the initiating click, flips inward at window edges, and dismisses through Escape or clicking outside. Add to playlist stays in the popup with a Back row, existing playlists and New playlist. Queue, favourites, pinning, album/artist navigation, downloads and share retain their existing callbacks. Home, Search, Library and other shared song menus inherit the Windows renderer. The new composition hook defaults to null, retaining Android's existing sheet.

Windows tests: 24 passed, including three click/edge placement cases. Compact-menu visual preview and Windows/Android compilation passed. No phone APK was built.

Release package passed; installed copy updated at 13:43 on 11 October and reopened. Installed archive matches the packaged code, shortcut is unchanged, and all 23 checked non-cache user files were preserved. Full data backup: `Downloads/Auralis-checkpoints/before-context-menu-20261011-134256`. Version remains 1.0.0.

### Artwork aura in the now-playing sidebar (Windows 1.0.0)

The right-hand player panel now uses the current cover's extracted palette for gently drifting radial colour glows (24-second cycle), crossfading colours on song changes. Its lyrics card is translucent instead of an opaque neutral fill, and text stays white for contrast. The panel follows artwork colours independently of a curated accent palette; the app-wide Liquidify setting is retained. Android is unchanged.

Compilation, isolated sidebar preview and release packaging passed. Updated the installed copy at 13:36 on 11 October, preserving all 21 checked non-cache data files and the shortcut target. Installed code matches the packaged archive. Full data backup: `Downloads/Auralis-checkpoints/before-sidebar-aura-20261011-133536`.

### Liquidify search dropdown (Windows 1.0.0)

Frosted-backdrop correction: the dropdown now blurs a snapshot of Auralis's actual page beneath its window-relative bounds (24 dp blur), rather than letting sharp page content show through. The snapshot is taken when opening the dropdown and released when closing; it captures only the app's recorded Compose layer, never other windows. Search rows remain sharp above a readability tint. The fallback is opaque with blurred cover colours, so capture failure cannot expose unblurred content. Compilation and the page-content blur preview passed; Android is unchanged.

Release packaging passed and installed copy updated at 14:10 on 11 October. Installed archive matches the packaged code; all 24 checked non-cache user files and the shortcut were preserved. Full data backup: `Downloads/Auralis-checkpoints/before-frosted-search-20261011-140929`. Version remains 1.0.0.

Find music uses a blurred current-cover backdrop when Liquidify is enabled, with a readability scrim and a subtle border. Search text and suggestion rows remain sharp. Other modes retain the normal surface. The existing artwork crossfade follows song changes; this does not use native compositor backdrop blur. Windows compilation and the disposable `search-glass` visual preview passed. Android is unchanged.

Installed 11 October at 13:25 after the release package passed. Installed code matches the packaged archive, the shortcut target is unchanged, and all 21 checked non-cache user files were preserved. Full app-data backup: `Downloads/Auralis-checkpoints/before-search-glass-20261011-132507`. Relaunched successfully with bundled VLC and version 1.0.0.

Windows 1.1.5 replaces the standard light title bar with a compact dark bar: centred Auralis title, logo/Home, Back/Forward navigation, page refresh where supported, account/Profile, Listen Together, Settings and minimize/maximize/restore/close. The bar is draggable and remains above the full player. Navigation keeps album/artist pages and updates metadata without adding a history step. This change is Windows-only.

Run the existing `:windows:run` task from the repository root. Its window is titled **Auralis Dev**, and uses `%APPDATA%/Auralis Dev` rather than the installed app's settings/library. Installed self-tests similarly use `Auralis Self Test`. Installed Auralis continues to use `%APPDATA%/Auralis`.

### Spicy Lyrics cover flow, direct sign-in, and responsive Find music (Windows 1.0.0)

Sidebar word sync: replaced the active rich-sync line's plain Text with the full player's `ExperimentalWordLevelLyrics`, using the same source-timing resolver and presentation timestamps. The sidebar clock drives the renderer directly during drawing; overlapping lead/background lines use genuine active intervals, while the primary line remains the scroll anchor. Compact 24sp layout, rounded hover, manual scrolling and click-to-seek remain. Sources without genuine word timing retain the line/plain fallback. No shared or Android source changes. All 40 Windows tests passed; rendered checks cover sung/future words during a vocal rest, paused progress and backward seeking, plus the existing hover/click regression. Standard MSI packaging completed successfully. Installed on 11 October at 16:46 after a normal app close; all 27 checked user files and shortcut were preserved, installed code matches the build. Backup: `Downloads/Auralis-checkpoints/before-sidebar-words-20261011-164635`. Version remains 1.0.0.

BitChord-style desktop Home: inspected BitChord's `HomeScreen.kt` and `YtMusicRepository.kt` (https://github.com/kushagrasinghx/BitChord). Home now leads with local Recently played in four-song columns or cover cards, with the view choice saved in `desktop-home.properties`. Pins remain a separate section. Recommendation shelves retain service ordering and combine songs/collections under one heading; discovery and listening-history recommendations remain fallbacks. Added refresh, empty-mood recovery, loading placeholders, and automatic Home continuation requests near the bottom. Pagination rejects duplicate concurrent requests, stale responses after a mood change/refresh, repeated tokens and pages with no new songs/collections; failures show a manual retry. The shared parser now carries the page continuation separately from carousel continuations. Auralis local history supplies recents; YouTube account history is not imported by this change. Existing theme, search tray, player, menus and Android presentation are retained.

Verification: all 39 Windows tests passed, including feed ordering/mixed shelves, append-position stability, continuation parsing, stale-page rejection, duplicate-page guards and retry. Android shared compilation passed. Wide/narrow/list/cover/loading previews were visually checked. Standard MSI build passed; installed on 11 October at 16:40 after a normal app close. All 26 checked data files and the shortcut were preserved; installed app jar matches the build. Full data backup: `Downloads/Auralis-checkpoints/before-bitchord-home-20261011-163943`. Version remains 1.0.0.

Liquidify across all pages: `ArtworkAppBackground` now supplies its translucent background/surface and foreground colours to the shared dynamic-theme locals as well as MaterialTheme. Settings, Profile and their shared subpages previously read the older opaque dynamic locals, hiding the global cover backdrop even while desktop browsing surfaces followed it. Both paths now resolve to the same artwork surfaces. A composition regression test checks equality for all four background/surface/foreground locals in artwork and non-artwork modes; normal themes retain their opaque base colours. Settings and Content Liquidify previews were visually checked.

All 32 Windows tests passed; Settings, Content and Profile previews passed visual inspection. Standard MSI build succeeded. Installed and relaunched on 11 October at 16:18 after the app closed normally. All 26 checked non-cache user files and shortcut were preserved; installed code hashes match the build. Backup: `Downloads/Auralis-checkpoints/before-all-page-bg-20261011-161812`. Version remains 1.0.0. Android sources are unchanged.

Compact Spotify-style sidebar lyrics: Spicy Lyrics itself depends on Spotify/Spicetify and is not installed as an extension in Auralis. The native sidebar now uses existing Auralis lyric data and playback clock, a maximum 320dp translucent card, 24sp wrapped lines, top/bottom fades, smooth following, and a scrollbar on hover. Mouse scrolling pauses following for three seconds. Timed-line clicks seek; plain lyrics remain readable without seeking. Header buttons open a separate lyrics dialog, expand the full player, and collapse/restore the card with a height animation. Hover uses an inset 16dp rounded translucent tile and brighter text instead of a touch ripple. The expanded Windows renderer receives the same rounded hover treatment through an opt-in platform flag; Android's default remains false. Existing expanded word/syllable timing and lyric providers are retained. Reference: https://github.com/Spikerko/spicy-lyrics .

Verification: 31 Windows tests passed, including a rendered mouse-hover test checking rounded corners, a visible hover tile, no seek on hover, and one seek on click. Shared Android compilation passed. Compact sidebar and hovered-line previews were visually checked; the header controls use explicit 32dp desktop targets to prevent the default 48dp touch targets overlapping. The isolated software preview now samples 12 frames across 1.5 seconds for these expensive animated backgrounds, instead of drawing 90 frames; production animation is unchanged.

Installed together with the pending frosted sign-in correction on 11 October at 16:12. The user explicitly approved stopping the two leftover windowless Auralis processes after normal close did not finish; only those verified processes were stopped. Full data backup: `Downloads/Auralis-checkpoints/before-lyrics-and-signin-20261011-161157`. All 26 checked non-cache user files and the shortcut were preserved. Installed jar hashes match the final standard MSI build. Version remains 1.0.0; no executable was added to Downloads.

Sign-in glass correction: the modal explicitly uses an 82% theme-surface tint, a subtle border and shadow, and zero tonal elevation. The main app surface blurs by 18dp while sign-in is open; modal controls remain sharp. This blurs the actual page without a synchronous screenshot or GPU readback. Header title now takes remaining row width so Close stays aligned, with consistent 24dp padding and 12dp section spacing. Isolated browser-sign-in preview rendered and was visually checked with fake profiles and a blurred home page; Windows compilation passed. Android is unchanged.

Follow-up search motion correction: entrance/exit now run on the popup's own frame clock, rather than starting in the parent before the popup is ready. The first painted frame has a small visible opacity; scale/slide/fade continue over 220ms. A separate mounted flag keeps the popup alive for its 140ms exit and avoids reading animation state in parent composition on every frame. Submitted-query refresh is deferred until after entrance, and the position provider no longer writes composition state during layout. Keyboard focus is requested independently after one frame. A rendered popup regression test checks visibility in the first three frames, intermediate motion, settling within 400ms, animated dismissal, and immediate animated reopening without network calls. All 30 Windows tests and standard MSI packaging passed. This checks Compose frames, not end-to-end installed click latency. Installed on 11 October after backing up to `Downloads/Auralis-checkpoints/before-search-motion-20261011-154655`; all 26 checked non-cache data files and shortcut were preserved, and installed code hashes match the build. Version remains 1.0.0.

The right player panel now samples the actual cover image through a SkSL port of Kawarp's two-octave simplex domain warp and eight diagonal Kawase blur passes. The small 128px texture is decoded/blurred off the rendering thread only when artwork changes. Frame drawing updates shader uniforms; cover changes crossfade over one second. Spicy Lyrics uses Kawarp with saturation 1.5; this port retains that saturation with an additional readability dim. The flow is steady: Spotify audio-analysis/beat speed modulation is not available in Auralis. Reference: https://github.com/Spikerko/spicy-lyrics/blob/main/src/components/DynamicBG/dynamicBackground.ts and https://github.com/better-lyrics/kawarp/blob/bb914faf44a394c8b05896b31b21edbb369b5596/packages/core/src/index.ts . Kawarp's MIT notice is bundled in `windows/src/main/resources/third-party/kawarp-LICENSE.txt`.

Find music now fades/slides/scales in over 220ms and exits over 140ms. Removed synchronous full-window GPU image readback and the whole-page recording layer. Liquidify uses a blurred artwork backdrop immediately; it no longer captures the page's text/cards behind the popup. Search focus waits one frame; suggestions remain asynchronous. This removes a known blocking path; click-to-visible latency has not been measured in the installed app.

The title-bar account button opens sign-in directly. The YouTube tab lists Firefox/Zen/LibreWolf/Waterfox and Chromium profiles (Chrome/Edge/Brave/Vivaldi/Chromium), following BitChord's profile-picker behavior. Only the selected profile's unexpired YouTube cookies are queried through read-only SQLite. Firefox WAL state is handled by SQLite, with no copies of unrelated browser cookies in temp folders. Chromium DPAPI/AES-GCM sessions are supported; App-Bound v20 sessions report an actionable fallback rather than bypassing browser protection. New YouTube cookie saves use Windows DPAPI, while existing session files remain readable. Profile > YouTube account uses the same picker. Cookie paste remains available. No real user browser session was read during verification.

Auralis email/password sign-in remains in a separate Auralis account tab. Google email/password opens Google's own page in the system browser, followed by profile import; embedded/no-browser Google credential sign-in is not implemented. Import does not sign into Firebase or automatically synchronise the entire YouTube library.

Verification: 29 Windows tests passed, including a shader image comparison over time, a fake Firefox database filtering test, and Chromium GCM integrity/host-prefix checks. Disposable sign-in/search/sidebar previews rendered successfully. Android source is unchanged; version remains 1.0.0. Installed and reopened on 11 October at 15:36. The shrinker completed successfully but took 11m45s; the final sign-in theme correction was packaged with the standard `:windows:packageMsi` build (2m19s), which is the installed artifact. Standard packaging gives the app jar a `windows-<hash>.jar` name; its bytes match `windows/build/libs/windows.jar`. All 26 checked non-cache user files and the shortcut target were preserved. Full app-data backup: `Downloads/Auralis-checkpoints/before-motion-signin-20261011-153502`. No executable was added to Downloads.

### YouTube Music account and sidebar library (Windows 1.0.0)

Inspected BitChord's authenticated account, session scope, library pagination and saved-account flows. Windows now verifies the selected YouTube session, retrieves the actual account name/handle/photo, and loads all library playlist pages into the navigation sidebar, including Liked Music. The top-right photo opens an anchored Switch account menu with Add account, Manage accounts and Settings; disconnected users retain the profile icon and direct sign-in. Private playlist pages use the selected authenticated session and load progressively. Repeated continuation tokens and suggested-track shelves are excluded. Cached playlist lists appear immediately on later starts; each saved account has its own cache. The complete saved-account registry is protected with Windows DPAPI, and removing an account clears its saved session/cache. Existing local playlists and Android source are unchanged.

A read-only check with the existing saved connection verified the account, loaded 25 library entries across two pages and 99 tracks from one playlist. All 45 Windows tests passed, covering authenticated account scope, pagination, private playlists, list/grid parsing, encrypted account storage and cache separation. Sidebar and account-menu previews were inspected. Idle dark Home now uses flat #202020 charcoal whenever no track is loaded, matching the supplied BitChord background reference; active artwork and light themes remain supported.

Standard MSI build succeeded. Installed and relaunched on 11 October at 17:14 after a normal app close. All 28 checked data files and the existing shortcut were preserved; installed app code matches the build. Full backup: Downloads/Auralis-checkpoints/before-youtube-library-20261011-171346. Version remains 1.0.0. No executable was added to Downloads.

### Neutral desktop accent (Windows 1.0.0)

The green idle accent came from the shared fixed Lime colour scheme, rather than an imported Windows wallpaper colour. Desktop theme fallback now uses white accents and neutral grey containers in dark modes, with dark neutral accents for readable light mode. Added a White fixed palette to desktop Themes and Colours. Dynamic artwork palettes and explicitly selected curated palettes remain available; Android colour schemes are unchanged. Windows compilation passed and the idle Home/sidebar preview was visually checked.

Standard MSI build passed. Installed on 11 October at 17:20 after a normal close, with all 29 checked data files and shortcut preserved; installed code matches the build. Backup: Downloads/Auralis-checkpoints/before-white-accent-20261011-171924. Version remains 1.0.0.

### Title-bar account alignment (Windows 1.0.0)

Account names use the bundled desktop label typography at 14sp with an 18sp line height and medium weight. The avatar is 26dp and dropdown arrow 16dp, vertically centred with consistent 8dp spacing. Long names ellipsize within 180dp instead of being cut at 22 characters. The title-bar preview was visually checked; this is a presentation-only change.

Standard MSI build passed. Installed on 11 October at 17:24 after a normal close; all 30 checked data files and shortcut were preserved, and installed code matches the build. Backup: Downloads/Auralis-checkpoints/before-account-label-20261011-172330. Version remains 1.0.0.

### Complete desktop Home shelf layout (Windows 1.0.0)

Re-inspected BitChord HomeScreen, repository Home/history/new-release requests and shelf parsers against the supplied desktop screenshot. Home now starts directly with Recents in four-track horizontal columns (or cover view), followed by 150dp cover shelves with 14dp gaps, 16dp page gutters, compact subtitles and 22sp headings. Removed the greeting, mood chip strip, pinned shortcut blocks, separate quick-pick grid and learning banner from the Home presentation. Search remains available through a compact icon using the existing animated frosted Find music popup; refresh and recents view controls sit beside it.

Connected Home uses the selected authenticated YouTube session for FEmusic_home, FEmusic_history and FEmusic_new_releases. History, releases and the main feed load independently; Recents falls back to local history while remote history loads. Albums & singles precedes the service shelves, including Listen again. Mixed song/collection order and account subtitles are preserved. From your library includes YouTube playlists and saved local collections; playlist and artist cards open their appropriate pages. Private playlist menu actions use authenticated requests. Home page continuation excludes shelf tokens, rejects concurrent/repeated/stale requests and offers retry. Account switches cancel pending Home requests. Guest Home keeps the recommendation engine behind the new shelf presentation. Android is unchanged.

The read-only live check returned 3 Home shelves, 40 YouTube recents and 2 new-release shelves. Its later library continuation received a reconnect response; the Home endpoints themselves succeeded. All 48 Windows tests passed before final packaging, including mixed item order, plain/grid shelves and page-token scope. Wide service, narrow guest and loading previews were inspected.

Final regression verification passed all 48 tests. The existing sidebar word-sync image test now flushes scene invalidations after clock changes before asserting sung/future/paused/seek pixels, removing intermittent stale-frame captures; its assertions are unchanged.

Final standard MSI packaging passed. Installed on 11 October at 17:43 after a normal close; all 31 checked data files and shortcut were preserved, and installed code matches the build. Full backup: Downloads/Auralis-checkpoints/before-home-redesign-20261011-174242. Version remains 1.0.0.

### Find music button (Windows 1.0.0)

Home now shows the labelled Find music button at 210dp minimum width and 60dp height with 18sp text. Removed the adjacent Home refresh icon. The button uses neutral translucent white instead of the theme's tinted secondary container; its popup spacing accounts for the larger button. Existing popup animation and search actions are preserved.

Windows compilation and standard MSI packaging passed. Installed on 11 October at 17:48 after a normal close; all 32 checked data files and shortcut were preserved, and installed code matches the build. Backup: Downloads/Auralis-checkpoints/before-find-music-button-20261011-174716. Version remains 1.0.0.

### Home scrollbar hover visibility — 11 October 2026
Home's vertical scrollbar now fades in over 160 ms while the pointer is over the Home scroll area and fades out when it leaves. Scroll position, wheel scrolling, and scrollbar dragging are preserved. The Windows MSI packaged successfully in 54 seconds; version remains 1.0.0.
Installed and launched after a full app-data backup at Downloads/Auralis-checkpoints/before-hover-scrollbar-20261011-175117. All 32 checked non-cache data files were preserved, the installed code matches the release, and the shortcut target is unchanged.

### Account menu rows and title-bar cleanup — 11 October 2026
Account menu actions now use left-aligned 52 dp rows with a full-width rectangular hover highlight matching the reference. Removed the Settings gear from the window title bar. Find music retains the larger neutral button and no adjacent refresh control. Version remains 1.0.0.
Windows packaging passed in 1m31s. Installed and launched with full backup at Downloads/Auralis-checkpoints/before-account-hover-20261011-175528. All 32 checked non-cache user files and the shortcut target were preserved; installed code matches the release.

### Find music proportions correction — 11 October 2026
Changed the Find music button from 60 dp tall with a 210 dp minimum width to 64 dp tall with a 176 dp minimum width and narrower horizontal padding. Adjusted popup offset to keep its 8 dp gap. Neutral colours and larger text are retained. Version remains 1.0.0.
Windows packaging passed in 58 seconds. Installed and launched with full backup at Downloads/Auralis-checkpoints/before-find-music-proportions-20261011-175743. All 32 checked non-cache data files and the shortcut target were preserved; installed code matches the release.

### Account hover interaction correction — 11 October 2026
The action rows now observe the clickable row's own interaction source, with no separate hover handler or rounded indication. A focused mouse-input render check verifies all four rectangle edges highlight, exit clears the highlight, and clicks dispatch once. AccountMenuHoverTest passed. Version remains 1.0.0.
Packaging passed in 54 seconds. Installed and launched with full backup at Downloads/Auralis-checkpoints/before-account-hover-fix-20261011-180138. All 32 checked non-cache user files and shortcut target were preserved; installed code matches the release.

### Imported Spotify playback repair — 12 October 2026
Diagnosis from the app log: imported sp_ IDs were handed directly to NewPipe's YouTube watch extractor before background matching finished. Three failed IDs triggered a misleading network-block message based on an unrelated search cooldown. BitChord's DesktopStreamClient resolves YouTube recording IDs before audio extraction; its current source was inspected for comparison.
Added foreground title/artist/duration matching, valid YouTube ID checks, shared recording/duration caching, and a bounded Music search that can use the connected session independently of the background import cooldown, with one video-search fallback. Unmatched imports are not preloaded or sent to the video extractor. The queue's generic failure message no longer labels Spotify-ID failures as a YouTube network block.
Verification: PlaybackMatchTest (3 cases) and RepeatPlaybackTest passed. Live Tere Liye import sp_0d28khcov6AiegSCpG5TuT matched 5U5Ru0nTiUM; four 524288-byte ranges returned HTTP 206. Muted playback through DesktopPlaybackController, LocalStreamRelay and bundled libVLC advanced from 0 to 3782 ms over four seconds. The probe uses no app data. Version stays 1.0.0.
All 53 Windows tests passed; packaging passed in 1m54s. Installed after full backup at Downloads/Auralis-checkpoints/before-playback-import-20261012-122557. The updater initially matched both windows-core-desktop and windows jars; refined its main-jar filter and independently verified hashes of both modules before reopening. Shortcut target preserved.
