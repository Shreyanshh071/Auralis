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
The installer completed its user-data comparison successfully before the jar-name check: all 62 checked non-cache files were preserved before launch. Auralis reopened with a visible window; normal startup account sync and saved-state writes occur after launch.

### Playlist header layout — 12 October 2026
Wide Windows playlist pages now use an artwork banner with a dark blurred cover background, a 156 dp cover beside bottom-aligned playlist metadata and Play/options/shuffle controls, playlist search in the upper right, and sort/order controls aligned right below. Auralis theme typography is retained. The track table, order/reordering, filtering, and menu actions retain their existing implementation. Compact windows retain the existing stacked header. Added a disposable playlist-header preview fixture. Version remains 1.0.0.
Rendered and visually inspected windows/build/screens/playlist-header.png. Compilation, preview rendering and packaging passed (5m11s). Installed and launched after full backup at Downloads/Auralis-checkpoints/before-playlist-header-20261012-124253. All 64 checked non-cache user files and shortcut target were preserved. Both main and core installed modules match the release.

### Fluid lyrics rendering performance — 12 October 2026
The active Fluid line repeatedly repainted complete text layers for individual words and applied a live blur/offscreen sweep. Cached the measured word geometry, white glyph image and held-note glow once per layout. Animation now clips and translates those images in the draw phase, preserving the feathered word sweep, lift, wrapped lines and glow without per-frame text rasterization or blur.

The same 900×250 wrapped-line render test improved from 38.52 ms median / 115.45 ms p95 to 9.46 ms median / 11.26 ms p95 across 180 frames after warmup. These are focused renderer measurements, not whole-app frame-rate guarantees. The test also verifies seeking updates the sweep and completed words remain bright. Visually inspected the wrapped-line image. Version remains 1.0.0.

All 54 Windows tests passed; MSI packaging succeeded in 2m49s. Installed after a normal app close and full backup at Downloads/Auralis-checkpoints/before-fluid-lyrics-20261012-125823. All 64 checked non-cache user files and the shortcut were preserved; main and core installed modules match the release. Auralis was relaunched.

### Dynamic lyrics background colour correction — 12 October 2026
Compared the current Spicy Lyrics dynamic-background CSS with our Kawarp output. The Windows full player used brightness=1 and omitted the subsequent CSS saturate(2.5) brightness(.65) filter; its sidebar also used a different dimming curve. This left bright artwork pale behind white lyrics. Added explicit full-player and sidebar presentation settings matching the final filters (.65/2.5 and .6/2.65), corrected Kawarp luminance weights and output order, and preserved the intermediate canvas clamp before the final filter. Both passes are combined in the existing GPU shader, without another blur or offscreen layer. Source: https://github.com/Spikerko/spicy-lyrics/blob/main/src/css/DynamicBG/spicy-dynamic-bg.css

ArtworkAuraTest checks opaque output, white-cover dimming, pink/blue colour separation, dark-cover preservation in both presentations, and animation over time. Focused checks passed; generated colour previews were inspected. Version remains 1.0.0.

All 55 Windows tests passed and MSI packaging succeeded in 3m1s. Installed after a normal close and full backup at Downloads/Auralis-checkpoints/before-aura-colour-20261012-130814. All 65 checked non-cache user files and the shortcut were preserved, both installed modules match the release, and Auralis was relaunched.

### Selected lyrics style in side panels — 12 October 2026
The shared compact lyrics view previously always used ExperimentalWordLevelLyrics (Metro). Active sidebar lines now read the live appearance selection and call the same FluidLyricLine or AppleMusicLyricsLine renderer used by the full player for Auralis Fluid and Apple Music Letter by Letter. Other selections retain the full player's existing word-renderer fallback. This covers the dedicated Lyrics panel, Now playing card and its detached lyrics window. Compact sizing, hover, scrolling and click-to-seek remain. Line-synced Apple lyrics use the explicit line end or next line start when available.

Focused sidebar word/hover checks passed. Added a render check that switches Metro, Fluid and Apple within one open scene, verifies distinct output, advances playback and seeks back for each style; all three previews were inspected. Version remains 1.0.0.

MSI packaging passed in 2m7s. Installed after a normal app close and full backup at Downloads/Auralis-checkpoints/before-sidebar-styles-20261012-131502. All 65 checked non-cache data files and shortcut were preserved; both installed modules match the release. Auralis was relaunched.

### Interlude dot motion and size — 12 October 2026
Inspected both supplied 240 fps recordings and sampled consecutive dot frames. The reference uses larger dots, curved lift/scale changes and spring settling; our dots used straight-line interpolation, a radial aura, smaller sizes and an intro clock quantized to 50 ms. Replaced the curves with natural cubic splines and independent playback-driven springs, increased the full-player dot diameter from 0.42 to 0.70 times the base lyric size, and increased compact intro dots to 16 dp. Removed the radial aura and click hover/ripple pill. Intro and interlude progress now follow the smooth clock without the redundant progress tween. Pause freezes the springs; seeking resets them. Android sources are unchanged.

LyricsDotsMotionTest verifies more than 50 distinct frames in a 60-frame sequence, no alpha outside the solid dot edges, pause stability and seek reset. Focused dot/sidebar checks passed and rendered frames were inspected. Version remains 1.0.0.

All 57 Windows tests passed; MSI packaging succeeded in 2m6s. Installed after a normal close and full backup at Downloads/Auralis-checkpoints/before-dots-motion-20261012-132752. All 65 checked non-cache user files and the shortcut were preserved; main and core installed modules match the release. Auralis was relaunched.

### Larger full-screen player — 12 October 2026
Increased the cover cap from 560 to 640 dp, widened its column to 44%, reduced outer gutters and retained width/height bounds for smaller windows. Full-screen lyrics use a 1.35 presentation multiplier on the selected text-size preference (about 46 sp at the default), with the selected renderer retained. Title/artist text increased to 34/22 sp, seek times to 17 sp, tabs to 16 sp and hover transport glyphs were enlarged. Saved settings and compact sidebar sizing are unchanged. Version remains 1.0.0.

Compilation and MSI packaging passed in 1m32s. Installed after a normal close and full backup at Downloads/Auralis-checkpoints/before-player-size-20261012-133225. All 65 checked non-cache user files and shortcut were preserved; both installed modules match the release. Auralis was relaunched.

## Windows dock live liquid glass — 2026-10-12

- Replaced the dock's 86% dark painted fill with a live recording of the page behind it. The dock is excluded from that recording, so icons/text stay sharp and there is no recursive glass capture.
- Uses the phone's 18 dp blur, 1.5 saturation, rounded-rectangle refraction shader (20 dp height / 28 dp amount), depth and chromatic dispersion on Skia. Tint remains 38% dark; the existing spring swell, pointer light and selected pill remain.
- Removed History and Profile from the dock. Home, Library, Wrapped and Settings remain.
- Verified with an ImageComposeScene render over alternating 8 px stripes: significantly reduced stripe variation within the dock, and changing the page from orange to blue updates the glass. Inspected both PNGs under windows/build/screens/dock-glass. Focused DockGlassRenderTest and MSI packaging passed (1m33s).
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-dock-glass-20261012-134357.

## Familiar Artists missing portraits — 2026-10-12

- Root cause: artist cards depended entirely on paced YouTube artist searches. A YouTube network cooldown delayed those lookups, and a failed lookup was stored as null for the remainder of the session.
- Portrait lookup now runs independently through the existing Wikipedia artist image source while YouTube resolves the channel. Successful channel/photo results are saved to cache/artist-portraits.properties and immediately reused on startup; channel-only replies preserve an existing portrait. Missing results retry after a minute rather than being permanently cached.
- Wikipedia follows title redirects, allows five seconds per request, and propagates cancellation.
- Three focused checks passed: portrait publication while YouTube waits, persistence/retry/cancellation, and live download/decoding of all six screenshot artists (Tame Impala, Sidney Gish, TV Girl, Pawan Singh, Ravyn Lenae, The Police). Image sizes ranged from 829x1105 to 1280x1600. MSI packaging passed.
- Full data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-artist-photos-20261012-135604.
- Final app startup check caught the imported uppercase credit PAWAN SINGH. Independent portrait queries now title-case all-uppercase credits for Wikipedia without changing the displayed artist name. The live check uses that exact uppercase input.
- Final installation backup: C:\Users\shrey\Downloads\Auralis-checkpoints\before-artist-photos-20261012-140025.

## Playlist cover background while idle — 2026-10-12

- When the player has no current track, the open playlist supplies the app's existing blurred artwork backdrop and palette. Reads the selected playlist live, so opening another playlist updates the background and leaving its page returns to the normal idle background.
- Uses the playlist cover first, then a track thumbnail when the cover is absent. Browse playlist/album pages also supply their own cover. A loaded song retains the existing song-background behaviour.
- Rendered and visually inspected the real playlist detail screen with its blurred cover behind the header and track table: windows/build/screens/playlist-header.png. Compilation, preview rendering and MSI packaging passed. No new tests for this reversible presentation change.
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-playlist-idle-20261012-140715.

## Automatic artwork backgrounds in every theme — 2026-10-12

- Removed the Liquidify gate from the app artwork backdrop: current-song artwork now supplies the changing blurred background in every theme mode. An open playlist/album cover remains the fallback when no current artwork is available.
- Removed the obsolete Liquidify background switch from Themes and colours and described the automatic behaviour there. Search surfaces also use their artwork treatment independently of the old preference.
- Compilation and MSI packaging passed (1m44s). Existing artwork renderer and light/dark foreground handling are reused; no new test for this small presentation setting change.
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-always-artwork-20261012-141241.

## Remove Windows artwork toggles — 2026-10-12

- Removed Hide player artwork, Crop player artwork and Black mini-player from Windows Appearance settings.
- Windows full-screen player always displays its standard cover, and the bottom player bar uses its normal theme surface. Removed the hidden-cover-only transport row and black-bar theme override, so old saved values cannot activate the removed features.
- No remaining references to these three preference fields in windows/src/main. Compilation and MSI packaging passed (1m43s). Android and the shared backup preference format were kept intact.
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-remove-artwork-options-20261012-141657.

## Compact card follows selected lyric style — 2026-10-12

- The compact sidebar now observes the live appearance store directly, including detached lyric windows. Fluid and Apple render surrounding lines through their own renderers; the distance blur and Metro intro flag are now limited to MetroLyrics.
- The selected style changes in the open card without reopening it. Inactive Fluid uses the lightweight text path, preserving its glyph-cache performance.
- All three SidebarWordLyricsTest checks passed: renderer switching, pause/seek/rest timing, and the whole compact card switching from the live store even when its parent supplies Metro defaults. Inspected Fluid/Apple card renders under windows/build/screens/compact-card-styles. MSI packaging passed (2m11s).
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-card-style-20261012-145935.

## Replace library sidebar with navigation — 2026-10-12

- Replaced the left playlist list with Home, Library, History and Downloads rows, using Auralis typography and the existing translucent artwork surface. Selected row has a rounded highlight; the panel remains collapsible.
- Library clears the selected playlist/smart collection and opens the overview. History opens listening history. Downloads opens the existing downloaded smart collection. Home and History clear any browse overlay. Playlists remain available through Library.
- Compilation and MSI packaging passed (2m45s). No new tests for this reversible navigation UI change.
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-navigation-sidebar-20261012-150441.

## Continuous playlist backdrop — 2026-10-12

- Removed the playlist hero's separately enlarged/blurred cover and heavy black gradient. The header now exposes the same app-owned blurred artwork as the song list, eliminating the duplicated cover crops and hard split at the header boundary.
- Current-song artwork therefore flows through the header during playback; when the player is empty, the playlist cover fills the existing single app backdrop. The foreground playlist cover and controls remain intact.
- Visually inspected windows/build/screens/playlist-header.png: continuous colours behind header and track list with readable text. Preview rendering, compilation and MSI packaging passed (5m44s).
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-continuous-playlist-20261012-151226.

## Restore pointer input after Alt+Tab — 2026-10-12

- Replaced the previous windowActivated-only, null-focus-owner recovery (which walked only first children) with activation and windowGainedFocus listeners. They run after AWT activation, verify the app is active/focused, locate the actual Skia surface across all component branches, and request focus if the window/container owns it.
- Refreshes the current pointer position by dispatching a mouse-move event to the component beneath it. Does not move the cursor, synthesize a click, or activate an unfocused app; owned popups keep focus. Listeners are removed on disposal.
- Compilation and MSI packaging passed. WindowInputRestoreTest passed on the AWT event thread and verifies Skia targeting past unrelated first children. Actual user Alt+Tab interaction was not automated in this session.
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-input-restore-20261012-151817.

## Ten Familiar Artists in horizontal carousel — 2026-10-12

- Raised the Familiar Artists output limit from six to ten (ranked by the listener's play counts).
- Replaced the responsive multi-row grid with a LazyRow of 4:5 posters. Width adapts to the panel (180–252 dp), keeps cards readable, and scrolls horizontally. Previous/More artists arrow controls sit at the right below the posters and move three positions, with availability based on actual scroll bounds.
- Compilation and MSI packaging passed (2m39s). No new tests for this reversible layout change.
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-ten-artists-20261012-152311.

## Home Shortcuts section — 2026-10-12

- Inspected LiMusic's Shortcuts.svelte and ShortcutPicker.svelte in the existing local reference: wide curated tiles, visible empty section, library filter picker and fresh library metadata. Implemented an Auralis Compose section, without copying Svelte code.
- Added Shortcuts below the Home greeting, with 64 dp wide tiles, circular artist artwork, horizontal scrolling, Add picker with filtering and duplicate prevention, and remove controls. Uses HomeViewModel's existing persisted pinned-item storage.
- Picker includes local playlists, saved artists and Familiar Artists. Local playlist tiles open the app's playlist page rather than remote album browsing. Current library metadata updates pinned covers/names/song counts.
- Compiled, rendered and visually inspected windows/build/screens/shortcuts.png. MSI packaging passed (2m46s).
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-shortcuts-20261012-153146.

## Sidebar local and YouTube Music playlist creation — 2026-10-12

- Added New playlist below Downloads. It opens an Auralis dialog with a name field and side-by-side YouTube Music / This device destinations, rather than LiMusic's stacked radio rows.
- Inspected LiMusic commands.rs and innertube endpoints.rs: remote creation uses authenticated playlist/create with title and PRIVATE privacyStatus, reading the returned playlistId. Auralis uses its existing selected-account scoped YouTubeMusicClient; publishes/caches the new playlist in that account and opens it after confirmation. Account changes during the request are detected.
- Device creation uses the existing local library repository and opens the created playlist. Busy state prevents repeat clicks/dismissal during writes; name validation and inline errors keep unconfirmed requests from being reported as success. YouTube creation waits for verified account loading to finish.
- Seven YouTubeLibraryTest checks passed, including private create payload, selected auth-user/page, normalized browse ID, blank names and missing playlistId. Tests use fake transport; no live test playlist was created. Visually inspected windows/build/screens/create-playlist.png. Compilation/render/MSI build passed (3m9s).
- Initial normal shutdown did not close Auralis; user explicitly authorized stopping its processes before installation.
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-create-playlist-20261012-154107.

## Real Windows downloads and grouped offline library — 2026-10-12

- Root cause: DesktopPlatformActions download actions were placeholders and DesktopLibraryPlatform exposed empty flows. Downloads routed to LibraryScreen's phone-only hub slot without a Windows implementation, producing a blank page.
- Added DesktopDownloads: serialized actual audio transfers through the existing ranged stream relay, complete byte-length/container validation, temporary files published only on success, persistent metadata, live status/progress/errors, retry and owned-file removal. Download queues are persisted once per enqueue batch to avoid UI stalls on large playlists.
- Files follow the phone's public folder structure at %USERPROFILE%\Downloads\Auralis\Individual songs\ and one sanitized folder per playlist; identical playlist names get distinct collision folders. Playlist files retain order numbering. Metadata lives at %APPDATA%\Auralis\download-index.json; logical track IDs remain unchanged, including Spotify imports.
- Wired song and playlist menus (including Home album downloads), LibraryPlatform flows, storage byte count, and a real Windows Downloads page with groups, completion/failure counts, progress, retry, removal and offline play. Removed obsolete unsupported-download text.
- Playback now prefers an indexed local file and bypasses network matching/stream relay. Live validation downloaded the complete 4,838,193-byte Tere Liye WebM into windows/build/download-live; muted VLC played it from disk with the resolver configured to fail if used (position 1,522 ms, duration 279,841 ms). This caught and fixed VLC's Windows file-URI handling: local playback uses the native absolute path.
- Three focused tests passed: live complete file/offline playback, mock transfer/group/restart/error/truncation/removal checks, and safe Windows names. Visually inspected windows/build/screens/downloads-groups.png. Final MSI build passed (2m41s).
- Previous placeholder clicks cannot be recovered as audio downloads; the user must queue those songs again.
- Full app-data backup before installation: C:\Users\shrey\Downloads\Auralis-checkpoints\before-downloads-20261012-161116.

## Liquid glass options and search popups — 2026-10-12

- Root cause: options menus used a translucent fill; search used a separate blurred song cover. Neither sampled the app beneath the popup, so crisp background imagery showed through.
- Added one app-only display-list source, recorded only while popups are open. Options and search draw the correctly positioned region through the same 18 dp blur, 1.5 saturation and Kyant refraction/chromatic glass shader used by the phone/dock. Text and controls render afterward, sharp; menu layout is retained.
- Compose desktop popup coordinates already include the popup position. Actual Popup rendering tests caught double-offset sampling and an empty display-list recording; both corrected before release. Verified spatial crop, striped-background blur, live page changes, sharp foreground detail and recording cleanup on dismissal. Existing dock rendering check also passed.
- No GPU screenshot readback; capture uses Compose graphics layers. MSI is installed only after a full app-data backup and data/hash checks.
- Full app-data backup: C:\Users\shrey\Downloads\Auralis-checkpoints\before-popup-glass-20261012-163348. Installed main/core jars match the release; all 68 checked data files preserved.

## Playlist header sizing — 2026-10-12

- Increased Windows playlist covers from 156 to 184 dp and names from 26 to 32 sp, retaining Auralis typography.
- Reduced the header content's bottom inset from 24 to 8 dp, shifting cover/details down 16 dp and reducing the gap above track headings.
- Visually inspected the rendered playlist-header.png; build and installer verification recorded below.
- Render/MSI build passed (5m39s). Installed with full backup at C:\Users\shrey\Downloads\Auralis-checkpoints\before-playlist-size-20261012-164305; all 68 checked data files preserved.

## Artwork colour fidelity — 2026-10-12

- Root cause: browsing used a 70%-bright, 48 dp blurred, 1.10x zoomed cover under palette-coloured panels. Sparse coloured details (e.g. No Dogs Allowed's red signature) could select a red accent and tint the whole neutral background pink.
- Matched Liquify's static-cover pipeline: centred cover, 7 dp blur, 45% brightness; neutral dark/light glass overlays instead of blending the accent palette into page surfaces. Accent colours and full-player blur defaults are retained. Applies automatically to song and idle-playlist covers across Windows modes.
- Reference: https://github.com/NMWplays/Liquify/blob/main/user.css (.liquify-bg-layer).
- Two ArtworkPageThemeTest checks passed, including rendered white-with-red-detail and blue covers, neutral panels, 45% pixel brightness and song-switch hue preservation. Inspected neutral-page.png and blue-page.png under windows/build/screens/artwork-colours.
- Installed successfully; full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-artwork-colours-20261012-172801; all 69 checked data files preserved.

## Queued Windows navigation, Home, Library and downloads refinements — 2026-10-12

- Removed the floating dock and lowered snackbar spacing. Home horizontal shelves, recents, Familiar Artists and Shortcuts now support primary mouse drag after a horizontal threshold; card clicks, vertical scrolling and touch behavior remain available.
- Reduced full-player interlude dots from 0.70 to 0.58 of the lyric-size factor. Windows pin/unpin labels now say Shortcuts.
- Shortcut cards now expose three-dot options for songs, albums, playlists and artists, and animate a cover-derived glow behind their labels. Local playlists use their four-song artwork when no cover was selected.
- Search results fill the content panel and use the title-bar search field; removed the duplicate page field. Ctrl+F focuses the title-bar field.
- Root cause of missing newly created YouTube playlists: the account loader published them, but the Library grid merged only local playlists and saved albums. The grid now includes the selected account's playlists and opens them through the YouTube browser. Missing YouTube playlist artwork is repaired from track art and cached by account. No new live YouTube playlist was created during verification.
- Missing playlist covers use four distinct song covers (or the first available cover for fewer than four), with a cached composite for animated backgrounds. Download cards use playlist cover metadata persisted in the download index; Individual songs use the track-art collage. Folder cards include the artwork, folder badge, saved count and options menu.
- Focused tests passed: bidirectional mouse drag without accidental card activation; ordinary card click; Library source/filter/account-playlist merge; downloads transfer/group/reload/error/removal and cover persistence; seven YouTube account/create tests; search popup motion; actual rendered four-cover artwork, single playlist artwork, shortcut menu/removal and moving cover background. Existing live download/offline playback verification is documented above.
- Inspected shortcuts.png and folder-artwork.png under windows/build/screens/queued-ui and downloads-groups.png. Synthetic colour fixtures deliberately make artwork sourcing and animation measurable.
- Final MSI build passed (1m38s). Installed after normal app shutdown with full backup at C:\Users\shrey\Downloads\Auralis-checkpoints\before-queued-ui-20261012-174924; all 69 checked data files preserved. Installed main/core jars match the release, desktop shortcut retained, and reopened Auralis window responds.
- Startup verification encountered external YouTube HTTP 403/network backoff and account-sync database write-bandwidth limits. Live remote sync could not be confirmed during this run; account-playlist merge, cache persistence and creation behavior were verified with focused tests. Cached account playlists remain available in Library.

## Explicit YouTube playlist selection correction — 2026-10-12

- Corrected the preceding change: loading a YouTube account must not populate Auralis Library or Home's From your library shelf with every account playlist. Removed the account-wide merge and unselected-playlist artwork prefetch. The import page remains the explicit path for choosing existing YouTube playlists.
- After a successful YouTube playlist creation through Auralis, retain just that confirmed playlist using the same stable imported:youtube_music:<remote ID> repository identity as selected imports. It survives restart and repeat retention does not duplicate it.
- User identified the existing creation as the empty aaaaa playlist with the default icon (VLPLcHketZqCSmc), distinguished from another aaaaa playlist with artwork. Restore only that exact playlist after a full backup; no remote playlists are deleted or changed.
- Four LibraryFiltersTest checks passed, including persistence after the repository's asynchronous write, no duplicate on repeat creation retention, explicit import preservation and exclusion of an unselected account playlist. Build/install verification follows.
- MSI build passed (2m23s); installed after normal shutdown with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-explicit-playlists-20261012-184842. All 69 checked data files matched before the intentional one-playlist recovery. Recovery verified that all existing Library content was unchanged and added only the selected empty aaaaa entry. Installed app jar matches the release.

## Stronger Shortcut glow — 2026-10-12

- User clarified that the moving glow should increase, rather than card dimensions. Increased only Shortcut aura brightness from 0.45 to 0.78 and saturation from 1.4 to 1.7. Existing animation, card dimensions, text, artwork, full player and sidebar rendering retain their settings.
- This is a visual parameter adjustment; package compilation and installed-code/data verification follow.
- MSI build passed (2m36s); installed with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-shortcut-glow-20261012-185525. All 69 checked data files preserved and installed app code matches release.

## Search Back navigation — 2026-10-12

- Focusing title-bar search via Ctrl+F or Home's search action no longer navigates to an empty Search page before a query is submitted.
- Navigation entries now retain the committed search query, independently of edits/clearing in the title bar. Back/Forward restores that submitted query/results when necessary, without adding a recent-history entry. Distinct submitted queries retain their own route identity; album/artist pages keep the query they came from.
- Three DesktopNavigationTest checks passed, covering multiple queries, result-to-album Back/Forward, Home return, forward-history truncation and metadata loading without extra navigation steps.
- MSI build passed (2m40s). Installed after normal shutdown with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-search-navigation-20261012-190102; all 69 checked data files preserved and installed app code matches the release.

## Restore soft song-synced browsing blur — 2026-10-12

- The colour-fidelity adjustment reduced browsing blur too far to 7 dp, leaving enlarged cover details visible. Restored the previous 48 dp blur and 1.10x image overscan for the app background. It continues following the current song (or idle playlist) across modes; neutral overlays and 45% brightness preserve the colour correction.
- Both ArtworkPageThemeTest checks passed (47s build/test), verifying neutral glass locals, no red/pink tint from sparse artwork accents, expected brightness and hue after switching covers. Inspected the rendered blue-page.png and confirmed its soft blurred field.
- MSI build passed (2m32s); installed after normal shutdown with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-background-blur-20261012-190609. All 70 checked data files preserved; installed code matches release.

## Windows taskbar preview playback controls — 2026-10-12

- Added an ITaskbarList3 thumbnail toolbar to the Auralis taskbar preview: Previous, Play/Pause and Next, with native icons/tooltips. The middle icon follows playback; buttons disable when no track is loaded and Next disables at the queue end. Existing lock-screen/media-key controls remain a separate Windows integration.
- Routes THBN_CLICKED through the existing root-window procedure, dispatching playback to Swing. Registers on taskbar readiness and recreates after Explorer's TaskbarButtonCreated notification; delayed readiness falls back to updating an existing toolbar. Disposal hides buttons, detaches the listener and releases COM/icon resources.
- Native integration check uses a disposable undecorated window and real Explorer COM calls/WM_COMMAND messages. Verified registration, command delivery, no-track and end-of-queue behavior, unrelated-message pass-through and cleanup. COM uses the multithreaded apartment: the initial STA setup on the Java event thread exposed an AWT toolkit shutdown hang during testing, corrected before release.
- Reference: https://learn.microsoft.com/en-us/windows/win32/api/shobjidl_core/nf-shobjidl_core-itaskbarlist3-thumbbaraddbuttons and THUMBBUTTON layout documentation.
- Final native integration check passed (17s), including a delayed taskbar-ready message; MSI build passed (2m25s). Installed with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-taskbar-controls-20261012-192056; all 70 checked data files preserved and installed app code matches release.


## 2026-10-12: Artist pages during background cooldown

- Reproduced the all-artists failure: a background song lookup received YouTube HTTP 403 and activated the five-minute network cooldown. Artist channel resolution and browse then failed locally without sending HTTP requests. A fresh read-only request for Tame Impala returned five top songs and seven albums.
- Scoped explicit artist-page requests independently of that background cooldown, including channel resolution and song fallback. The coroutine scope ends when the page request ends; ordinary searches/imports remain paused, and actual server errors still propagate to the existing Retry UI. Added artist-specific diagnostic logging without account data.
- Regression coverage verifies that an artist without a channel ID resolves and loads during the cooldown while an ordinary request still fails before reaching the transport. An opt-in live check covers Tame Impala, TV Girl and Sidney Gish during the same cooldown.
- Verification: AURALIS_VERIFY_ARTIST_PAGES=1 ./gradlew.bat :windows:test --tests *ArtistPageLoadingTest* passed both tests (4m52s rebuild; 4.1s test execution). Live responses were HTTP 200: Tame Impala five songs/seven albums; TV Girl five songs/eight albums; Sidney Gish five songs/two albums. The same cooldown reproduction failed before the fix with zero HTTP requests.
- Packaging/install: :windows:packageMsi succeeded in 3m7s. Installed 1.0.0 after normal shutdown with backup C:/Users/shrey/Downloads/Auralis-checkpoints/before-artist-pages-20261012-193741; all 70 checked data files and the existing shortcut were preserved. Installed app and shared-core jars match the tested build; the reopened window responds and taskbar controls register. Final deterministic test rerun passed in 10s; the opt-in live check was skipped on that final run, after passing with all three artists above. Genuine YouTube HTTP 403 responses can still occur; this fix removes the unrelated local cooldown rejection and does not claim to eliminate server throttling.


## 2026-10-12: Account panel transition

- The title-bar account popup now fades, slides slightly down and expands from its top-right anchor over 240ms, with a shorter 150ms closing transition. The glass surface, dimensions and account actions are unchanged.
- Keep the popup composed through its exit instead of removing it immediately; release popup focus as soon as dismissal starts, and reset Manage accounts when reopening. The transition can reverse when reopened during dismissal.
- Verification: desktop compilation and :windows:packageMsi passed (3m25s). Installed 1.0.0 after normal shutdown; backup C:/Users/shrey/Downloads/Auralis-checkpoints/before-account-animation-20261012-194525. All 70 checked data files and the existing shortcut were preserved, and the installed app jar matches the build. No live account mutations were performed.


## 2026-10-12: Compact menus anchored to song clicks

- Reduced the existing glass options menu uniformly by 12 percent (approximately 334px wide at 100 percent display scale), preserving its layout, actions and glass rendering.
- Capture the initiating press at the app root in Compose window pixels before child click handlers run. Both options popup hosts use that captured point instead of polling the later OS cursor/focused window; a cursor move after the click no longer moves the menu. Initial-pass recording does not consume the press. Existing edge flipping/clamping keeps menus on screen.
- :windows:test --tests *SongMenuPositionTest* passed all four tests in 25s, including a real Compose pointer/click test at 150 percent density with nested padding, cursor movement after clicking, a second song click, bottom-right flipping and a small-window constraint.
- Packaging/install: :windows:packageMsi passed in 2m35s. Installed 1.0.0 after normal shutdown with backup C:/Users/shrey/Downloads/Auralis-checkpoints/before-compact-song-menu-20261012-195206; all 70 checked data files and the existing shortcut were preserved. Installed app code matches the release.


## 2026-10-12: Automatic single-song queue

- Windows now starts a single-song radio session with the selected track plus up to fourteen recommendations (fifteen total), and requests another fourteen when one upcoming track remains, i.e. on the fourteenth song of the first batch. Manual Next or natural completion can wait for an already-running refill and advance once it arrives.
- Home song selections, song shortcuts, Search/results/dropdown, History and Start radio use this mode. Explicit Library/album/download lists retain their own queue, including one-track albums/playlists; Listen Together queues do not receive automatic recommendations. The existing Auto load more setting controls the behavior.
- Uses the same InnerTube radio provider as Android, with recent heavy rotation/history/liked songs and then artist-search fallback. Blocked artists and queued track IDs are excluded; each append is capped at fourteen. Fewer songs are possible when providers and local fallback do not have fourteen unused candidates.
- Queue generations cancel/discard stale requests on a new selection, one in-flight refill prevents duplicate batches, manual additions during loading are preserved, and disabling autoplay or entering a room prevents a late append.
- Verification: :windows:test --tests *DesktopAutoRadioTest* passed all four tests (final rebuild 22s), exercising initial/refill counts, no early refill, duplicate removal, a single concurrent request, manual additions during loading, end-of-queue advancement, disabled settings/room policy, stale queue cancellation and disabling/re-enabling autoplay during a request.

Installed the MSI on 2026-10-12. Backup: before-auto-radio-20261012-201222. All 70 checked data files were preserved; installed application and core jar hashes match the build. Desktop shortcut preserved.

## 2026-10-12: Pending playback, selectors and artist catalogue update

- Fix an idle Listen Together service permanently installing a room handler: connect that handler only while a room code exists, and expose reactive room state for player controls. This restores shuffle, repeat and automatic radio outside rooms. Repeat modes suspend radio extension so Repeat All can wrap the queue; switching repeat off resumes radio.
- Desktop appearance/audio/storage choices now use selection dialogs with opaque surfaces and selected radio rows. Seekbar styles reuse the phone's four animated preview cards and actual AuralisPlayerSlider in both desktop player views. Refresh gesture callbacks after song changes so seeking uses the loaded duration.
- Keep the title-bar account button sharp when its popup is open by blurring only the content panel.
- Follow artist shelf browse IDs and opaque params for expanded songs/albums/singles (up to 100 additional items per shelf), retaining previews if a secondary request fails. Show Videos and artist playlists and retain official collaborations/instrumentals from the channel's own shelves. Top songs opens that loaded catalogue rather than replacing it with a broad search. Reference: https://github.com/SimoHypers/limusic/blob/master/src-tauri/src/commands.rs#get_browse_grid
- Eleven headless tests passed after regenerating damaged Gradle cache metadata: artist shelf expansion/deduplication/section separation, both wave animations and pause behavior, fresh seek callbacks, queue refill safeguards, repeat transitions and the software background raster experiment. The muted real-libVLC check also passed shuffle, repeat all/one/off, no radio fetch in repeat modes, active-room locks and automatic recommendations outside rooms.
- Live artist browsing passed after increasing the initial browse timeout to twelve seconds: Tame Impala returned 100 songs/seven albums, TV Girl 80/eight, and Sidney Gish 27/two. YouTube still intermittently answers 403 to artist search; the exact-name fallback resolved the channel. Explicit Retry now drops its cached page so it can fetch the missing catalogue again.
- Installation is deliberately pending while the owner is playing Valorant. No app restart or new test windows were opened after that instruction.
- Final :windows:packageMsi passed in 1m54s. MSI ProductVersion is 1.0.0; SHA-256 is 94CB239AA243DB516FFF6CA863C6EC490D34E842D587143621278E68E0BEA449. The queued-fixes installer helper and the previous installed-version rollback MSI are prepared in windows/build.
- Installed on the owner's explicit request at 2026-10-12 22:18 IST after normal app shutdown. Backup: C:/Users/shrey/Downloads/Auralis-checkpoints/before-queued-fixes-20261012-221828. All 70 checked data files and the existing shortcut were preserved; installed application and core jar hashes match the build. NVIDIA software rendering remains pending its native performance check.
- NVIDIA experiment remains pending: OpenGL and Direct3D both load nvspcap64.dll; software rendering avoids it, but the native glass diagnostic measured only five frames in five seconds. Cached low-resolution cover motion retains the effect and rendered 30 offscreen frames in 1173ms; a native performance check is still required. The installer retains GPU rendering, and NVIDIA's game overlay settings have not been changed.

### Native NVIDIA investigation after the owner finished gaming

- SOFTWARE_FAST basic animation: 324 frames in five seconds, NVIDIA capture module absent. Artwork/glass checks: 3–14 frames in five seconds; SOFTWARE_COMPAT also failed the ten-fps minimum. Tests isolated the slowdown to artwork drawing/compositing, rather than the basic software renderer. Native redraw performance remained poor after clipping, lower-resolution filters, preblurred/pre-sized opaque covers and cached panel filtering. No software renderer was installed or enabled.
- Restored production PlayerScreen and LiquidPopupSurface after unsuccessful experiments; experimental blur/cache code is preserved under windows/build/nvidia-render-experiments. Existing installed UI and NVIDIA settings remain unchanged.
- NVIDIA staff document disabling only the overlay notification as a global workaround, independent of app recognition: https://forums.developer.nvidia.com/t/how-to-prevent-in-game-overlay-from-triggering/256026/10 . This is distinct from disabling the overlay, recording or game tools, but still needs owner agreement because the hint would also be hidden in games.
- Windows computer-use skill loaded; node_repl + @oai/sky initialization failed twice (with a kernel reset between attempts): "failed to write kernel assets: The system cannot find the path specified. (os error 3)". No NVIDIA UI automation or preference changes were performed.


## Glass playlist and shortcut dialogs � 2026-10-12

- Routed desktop New playlist, the Library New Playlist dialog and Home Add a shortcut through a shared glass dialog host, using the account popup's live page capture and lens/blur effect. Text, controls and artwork thumbnails draw above the blur.
- The centered modal uses a separate popup scene, excludes its scrim/panel from page capture, dismisses on Escape or background click, and scrolls in small windows. Playlist creation's busy/error and destination behaviour remain intact.
- Three render checks passed (two centered/create-dialog checks plus the existing popup backdrop check): correct backdrop region and colours, sharp foreground, capture cleanup and live page updates. Inspected the dark-theme New playlist rendering.
- NVIDIA's global hint/overlay settings remain unchanged at the user's request. The Auralis-only overlay issue is unresolved; the slow software-rendering experiment was not installed.
- MSI package passed (1m10s), SHA256 80E7B8853F035CA698F6470DA53051F0D919C3E562E7478E5BF7468A452EC9C4. Installed after normal shutdown with backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-glass-dialogs-20261012-234957. All 70 checked data files preserved; installed main/core jars match the build; desktop shortcut retained and Auralis reopened.

## Tighten Squiggly seekbar waves � 2026-10-12

- Replaced the Windows Squiggly slider's 80 raw-pixel wavelength with 28 dp spacing, bringing the widely spaced desktop peaks closer to the phone's appearance. The shared player/style-preview slider uses the same spacing at each desktop density. Preserved wave height, playhead and the original 3 1/3 second animation cycle.
- Existing seekbar checks passed for both animated styles: movement during playback, flattening after pause, and seeking with the loaded song's duration. Inspected the Squiggly preview; MSI package passed (2m22s combined verification/build).
- Installed with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-squiggly-spacing-20261012-235516. All 73 checked data files preserved, desktop shortcut retained, installed main/core jars match the build and Auralis reopened.

## Muted inactive lyric hover � 2026-10-13

- Full-player and sidebar inactive lyric hover now uses 55% text opacity instead of full singing white, retaining the existing rounded highlight and removing blur only from the hovered row. Active lyrics remain bright. Sidebar word hover no longer lights future unsung words.
- Hover render checks passed for both full player and sidebar: muted but readable text, row highlight, click-to-seek callback and active-line brightness. Existing sidebar word/style checks also passed; inspected the full-player hover PNG.
- MSI build passed (2m29s) and installed after normal shutdown with backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-lyric-hover-20261013-000437. All 73 checked data files preserved; installed main/core jars match the build; desktop shortcut retained and Auralis relaunched.

## Blend sidebar lyrics card into artwork � 2026-10-13

- Reduced the Now playing sidebar lyrics card's white overlay from 14% to 7%, preserving more of the underlying song artwork colours and reducing the milky appearance. Text, hover and word highlighting are unchanged.
- Rendered and inspected the existing Now playing sidebar preview: the softer card retains the underlying red/purple colour transition and sharp foreground controls.

## Responsive lyrics settings sliders � 2026-10-13

- Lyrics text size and line spacing now keep pointer changes in local slider state and persist once on release, avoiding per-move storage writes and app-wide appearance recomposition. Labels update with the pointer immediately. Appearance writes are serialized and use the latest stored settings to avoid stale copies overwriting other choices.
- Sidebar lyrics now apply the selected text size and line spacing for plain, MetroLyrics, Fluid and letter renderers, preserving the existing 24sp default scale.
- Pointer gesture check passed with delayed persistence: no commits while dragging, one final commit, and final value follows the pointer. Existing sidebar word/style and lyric hover checks also passed (five checks total). Includes the previously reviewed 7% lyrics-card tint update.
- MSI build passed (2m25s); installed the combined slider/tint update with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-settings-sliders-20261013-001507. All 73 checked data files preserved; main/core jars match the build and the desktop shortcut is retained.

## Brighter song-synced app background � 2026-10-13

- Raised browsing artwork brightness from 45% to 68%, keeping the 48dp blur, 1.10x overscan, current-song switching and neutral translucent page surfaces.
- Both artwork theme render checks passed: expected brighter white/blue cover pixels, preserved blue hue after song change and neutral panels without accent tint contamination.
- MSI package passed (1m58s); installed with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-background-brightness-20261013-001949. All 73 checked data files preserved; installed app jar matches the build, desktop shortcut retained and Auralis relaunched.

## Progressive artist overview and complete-page recovery � 2026-10-13

- Compared the local LiMusic artist route and InnerTube parser: it displays the overview with five top songs and opens full lists separately. Auralis previously waited for sequential catalogue calls, rendered up to 100 songs before albums, and cached search-only recovery as a complete page. The user's Sidney Gish log also contained a YouTube HTTP 403 during artist resolution.
- Artist loading now publishes overview shelves before expanding catalogues concurrently. The overview displays five top songs; its More button uses the artist's actual top-songs playlist target when supplied. Resolved channel IDs are retained to avoid repeating name lookup.
- Added playlist endpoint classification for Featured on/localized playlist shelves and biography parsing from description shelves. Search-only and incomplete-catalogue pages offer Retry and are excluded from the complete-page cache. Each in-flight update targets its exact navigation entry, so aliases and superseded pages cannot receive a mismatched response.
- Catalogue requests now have an eight-second background deadline; overview loading does not wait for it. Initial live checks returned Tame Impala/TV Girl/Sidney Gish overviews in 1070/793/664ms with 7/8/2 albums; Sidney's original four-second catalogue request timed out while its overview remained usable.
- Final four artist checks passed, including concurrent expansion, overview-first delivery and incomplete fallback recovery. Live overviews arrived in 1093/741/649ms for Tame Impala/TV Girl/Sidney Gish; complete catalogues in 1519/1056/918ms. MSI installed successfully with backup before-artist-progress-20261013-003900; 73 checked user data files preserved, installed main/core jars match the build, and Auralis reopened with a responding main window.

## Remaining queued UI/playback fixes — 2026-10-13

- Fullscreen lyrics now collect the live appearance store like the sidebar. The text-size range no longer plateaus at the previous clamp; line spacing affects wrapped text, plain lyrics and the gaps between short lyric rows.
- Queue history contains only individual queue positions that reached actual PLAYING state, excluding jumped-over and failed songs and distinguishing duplicate recordings. Insert/remove operations preserve the mapping. The queue opens at Continue Playing, with history above it; extra trailing room handles a short remaining queue.
- Stats period and clear-stat menus use anchored LiquidPopupSurface sampling with sharp foreground controls. Shortcut candidate rows now show a full-width rounded hover and prevent repeated Add clicks after pinning.
- Squiggly wavelength adjusted from 28dp to 48dp, retaining the existing amplitude, pause flattening and shared animation phase. This is a desktop calibration between the earlier sparse and dense versions; phone display density was not available for an exact physical match.
- Ported the phone's fourteen-page Wrapped story, brown/gold animated background, floating shapes, count-up numbers, display font, cover/ranking pages and explicit playlist save. Desktop opens it over the app, with Previous/Next, keyboard navigation and Escape. Stats come from measured current-year listens; songs rank by play count then listened time; album/artist rankings use measured time. Artist portraits resolve separately. Included the font's SIL OFL notice.
- Focused checks passed for queue jumps/duplicate occurrences, initial scroll and upward history access, live fullscreen settings, radio expansion/repeat behaviour, Wrapped year filtering/rankings and representative story rendering, actual dropdown backdrop sampling, lyric hover and shortcut hover/click guards. Rendered Wrapped, dropdown and shortcut previews were inspected.
- NVIDIA overlay remains unresolved under the requested Auralis-only constraint. GPU backends inject the capture module; native software artwork/glass rendering was too slow in prior checks. NVIDIA staff document a global hint-notification workaround (https://forums.developer.nvidia.com/t/how-to-prevent-in-game-overlay-from-triggering/256026/10), which the owner explicitly declined. No game or global overlay settings changed.
- Final single-line spacing, live settings, shortcut hover/click guard and revised seekbar preview checks passed; MSI package succeeded. Installed with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-queued-fixes-20261013-011043. All 73 checked data files preserved; main/core jars match the build, shortcut retained, and Auralis reopened with a responding main window. Version remains 1.0.0.

## Center Wrapped rankings — 2026-10-13

- Centered the shared songs/albums/artists ranking block under its heading. The block fits its content up to 560dp; rows align consistently and long labels ellipsize within the available window width.
- Rendered and inspected the ranking preview: numbering, covers and labels form a compact centered list rather than beginning at the window's left edge.
- Wrapped render check and MSI package passed. Installed with backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-wrapped-centering-20261013-095554; all 74 checked data files preserved, installed main/core jars match the build, and Auralis reopened with a responding main window. Version 1.0.0 and existing shortcut retained.

## Retain unplayed songs when jumping through the queue — 2026-10-13

- Reviewed the owner's phone video and corrected the local queue transition: unheard songs between the current position and a tapped song move behind the selection in their existing order. They remain visible in Continue Playing and can be played next, while only occurrences that reached PLAYING contribute to history.
- Preserved heard-song ordering for Previous/Next and repeat navigation. Clear history now removes heard occurrences while retaining unheard tracks and the current song's listening state. Active room queues retain the host's shared positions.
- Added regressions for jumping 1 to 3, subsequent playback of retained song 2, partly heard/skipped history, duplicate recordings, skipping before playback starts, clearing history and repeated backward navigation.
- All 13 focused queue/repeat/radio checks passed and MSI package succeeded. Installed with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-queue-retention-20261013-100934; all 74 checked data files preserved, main/core jars verified against the build, and Auralis reopened responding. Version 1.0.0 and the existing shortcut retained.

## Extend Add-shortcut hover to the right — 2026-10-13

- Shortcut candidates now fill the dialog's available content width instead of stopping at a fixed 420dp, extending the highlight toward the right edge. Increased the row's right padding from 8dp to 20dp so Add has more highlighted space after it.
- Existing hover/click-guard render check and MSI package passed. Installed with backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-shortcut-hover-width-20261013-101422; all 78 checked data files preserved, installed main/core jars verified and Auralis reopened responding. Version 1.0.0 and existing shortcut retained.

## Faster timestamp seeking — 2026-10-13

- Replaced the relay's uncached 512 KiB downloads with aligned 32 KiB blocks, a bounded 8 MiB LRU per retained stream (four-stream limit), shared in-flight block fetches and generation-cancelled prefetch up to 2 MiB ahead. Cached audio can be reused across overlapping/backward seeks; fresh seeks need a much smaller first transfer. Foreground requests run independently of the background queue.
- Retained refusal retries/fresh-URL recovery and same-file length checks. Validate upstream range offsets and block sizes; a whole-file 200 response can no longer be mistaken for a nonzero range. Refreshing URLs is serialized; prefetch never triggers expensive URL refreshes.
- Reduced VLC's loopback-relay network buffer from 1500ms to 300ms, keeping the larger buffer for direct remote URLs and precise timestamp seeking.
- Relay regression passed against a throttled range server: exact bytes for random/overlapping/cross-block/end ranges, repeated cache hits without re-fetching, and maximum upstream requests of 32 KiB. Cached overlap returned in 1ms.
- Native muted VLC playback check passed using a generated 180-second WebM/Opus fixture served at 160 KiB/s. Seeking to 30/140/60/20 seconds and waiting for audio position to advance another 250ms took 815/1045/858/839ms. These are controlled local-source timings, not a guarantee for an uncached YouTube server. MSI package passed.
- Installed with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-seek-cache-20261013-102735; all 78 checked data files preserved, installed main/core jars verified and Auralis reopened responding. Version 1.0.0 and existing shortcut retained.

## Smooth hover feedback throughout the desktop UI — 2026-10-13

- Added a shared 180ms hover fade for foundation clickable rows and Material buttons, icon controls, menu items, checkboxes, radios, sliders and clickable surfaces. Material controls retain their native press/focus interactions while their hover layer uses the shared transition. Click callbacks remain immediate.
- Standard controls animate in draw nodes rather than recomposing surrounding pages each frame. Slider/switch hover follows the thumb; slider shape state remains stable while dragging. Existing responsive slider persistence is retained.
- Custom shortcut, account, sign-in, library sidebar, search and artist controls now interpolate their highlight/tint colours. Sidebar lyrics scrollbar fades instead of popping in. Existing player, dock and lyric hover transitions remain active.
- Focused checks passed for intermediate hover colours on entry and exit, immediate row/icon/menu clicks, full shortcut highlighting and pinned-row guards, account row edge coverage/exit cleanup, and one settings persistence write after dragging.
- Final row/icon/menu/surface fade and immediate-click checks passed alongside shortcut, account and slider gesture checks; MSI package passed. Installed with full backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-smooth-hover-20261013-105303; all 78 checked data files preserved, installed main/core jars verified and Auralis reopened responding. Version 1.0.0 and existing shortcut retained.

## Lower Squiggly wave height — 2026-10-13

- Reduced Squiggly amplitude from 6px to 3px, keeping the 48dp wavelength, stroke and animation unchanged. The playhead height follows the smaller amplitude.
- Existing Squiggly preview render passed and was visually inspected; MSI package passed. Installed with backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-wave-height-20261013-110242. All 78 checked data files preserved, installed main/core jars verified, and Auralis reopened responding. Version 1.0.0 and existing shortcut retained.

## Instrumental dots throughout songs and Slim hover thickness — 2026-10-13

- The compact sidebar now inserts the same timed instrumental break rows as fullscreen lyrics, scrolls to active dots, dims the completed vocal during a break and supports tapping the dots to skip ahead. Added timed outros using the current track duration. Seeking back removes stale indicators.
- Shared break generation respects overlapping lead/background vocal ends. Existing thresholds remain: at least 5 seconds after a supplied vocal end, or at least 10 seconds between line starts when only line timing is available. The latter uses a display-only sung-length estimate. Fullscreen dots no longer disappear solely because automatic scrolling is disabled.
- Slim progress tracks now animate from 5.5dp to 8dp over 180ms on hover/drag and ease back on exit. Seeking remains immediate.
- Seven focused checks passed: break windows/overlapping vocals, repeated sidebar breaks and outro, skip/backward seeking, dot motion/pause behavior, existing sidebar renderers and live settings, and Slim intermediate hover thickness/exit/immediate click. Inspected later-break and outro render previews; MSI package passed.
- Installed with backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-interludes-20261013-112103; all 78 checked data files preserved, installed main/core jars verified and Auralis reopened responding. Version 1.0.0 and shortcut retained.

## Thicker cover volume slider — 2026-10-13

- Increased the fullscreen cover's vertical volume track from 8dp to 14dp normally, and from 14dp to 20dp on hover/drag. Retained the 180ms transition and 28dp interaction area.
- MSI build passed. Installed with backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-volume-width-20261013-112451; all 78 checked data files preserved, installed app jar verified and Auralis reopened responding. Version 1.0.0 and existing shortcut retained.

## Compact Listen Together popup and corrected hover scope - 2026-10-13

- Listen Together now opens a centered 488dp glass dialog over the current view from both title-bar and library entry points, using the same LiquidPopupSurface capture/blur as search recommendations. Removed the page destination. Kept the existing theme and room backend; compact Join/Host tabs, code/name fields, room members, guest controls, recommendations and leaving/ending rooms remain available. Closing the popup keeps the room connected.
- At the user's explicit request, reversed the broad app-wide hover-control update from 8c84886 after inspecting the corrected 10.6-second recording Auralis 2026.10.13 - 10.15.49.01.mp4. Removed its control wrappers and global indication overrides. Added direct 180ms background transitions to the shortcut candidates and compact recent-song rows shown in that recording instead.
- Focused checks passed for old/new row cross-fades, immediate song clicks, menu clicks without playing the parent row, shortcut full-row/pinned behavior, compact popup dimensions, Host/Create/Leave/Close actions. Inspected Join/Host/connected glass renders. MSI package passed.
- Installed with backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-together-popup-20261013-115433; all 78 checked data files preserved, installed main/core jars verified and Auralis reopened responding. Version 1.0.0 and existing shortcut retained.

## Listen Together signed-in name autofill - 2026-10-13

- Prefill the editable room display name from the active signed-in YouTube identity, falling back to the Auralis profile display name. Signed-out/unnamed users retain Listener. Late-loading profile names update the untouched field; manual edits are retained across tab switches and profile updates.
- Existing compact popup check passed with Shreyanshh prefilled and received by Create room, together with Host/Leave/Close actions. MSI build passed. Installed with backup C:\Users\shrey\Downloads\Auralis-checkpoints\before-together-name-20261013-120523; all 78 checked data files preserved, installed app jar verified and Auralis reopened responding. Version 1.0.0 and shortcut retained.

## 2026-10-13 — Windows Listen Together Android parity

Implemented Android's four room rules in the compact glass popup, both before creation and while hosting: queue additions/edits, play/pause/seek, choosing/skipping songs, and approval. Changing a single rule preserves the others. Guest permission summaries, song search, notes, votes, owner removal, host song approval, play-now, participant roles, slow-connection indicators, activity, and inactivity warnings are available.

The service uses Android's command baseline/sequence safeguards, duplicate-aware shared queue windows, approval decisions, presence timing, readiness reporting and a bounded shared-song start wait. Settings apply before guest track loading can return. Native pause for the start wait is dispatched off VLC's event thread; readiness is confirmed by the paused event. Failed readiness writes are retried. Room closure cleans up isolated recommendations and participants in the order allowed by security rules.

Verification: all 16 permission combinations, stale and denied command replay prevention, long-queue/stale-edit handling and close/sync timing (4 tests); popup name autofill, scrolling, create/end/close actions (1 test). Headless popup interactions use the supported non-captured surface fallback because software blur of the scrolling scene stalled the test. Live isolated Firestore check with two muted VLC players passed creation/join, all settings, shared audio, guest pause/seek, speed, approval, vote removal/addition, preserved permissions, automatic additions, shared queue removal, requested skips with direct song control off, approved song start and room closure. This verifies the shared Android protocol using two Windows players; a physical Android/Windows session still needs user testing.

Pixies remaster 6VG6gIvcjU8 remains pending: the initial stored YouTube session was rejected. After the user reconnected, account verification and the exact player response succeeded (16 formats). The existing extractor could not decode the new web URL. An experimental local V8 decoder read the current player but its audio URLs were refused; subsequent checks again reported the stored session as signed out. No experimental decoder or authenticated playback change is in this release. Its source is preserved under ignored windows/build/pixies-decoder-work for continuation; no credentials or signed URLs were copied into those files.

Installation verified: Auralis 1.0.0 MSI installed; main jar hash matches the packaged source. Backup: C:/Users/shrey/Downloads/Auralis-checkpoints/before-together-parity-20261013-140805. All 78 checked non-cache data files and the existing desktop shortcut were preserved.

## 2026-10-13 — Saved artists appear in Library

The artist menu already persisted saved artists, but Library rendered only playlists/albums. Added saved artists to the unfiltered Library and an Artists pill, with circular cropped portraits, centered names and stored subscriber counts (Artist fallback when unknown). List view, local search and Library sorting also apply; clicking opens the saved artist's page. Existing saves become visible without resaving. Familiar-artist lookup and save actions now retain subscriber metadata when supplied by the provider. The Artists empty state explains how to save an artist.

Verified compilation, headless Library rendering (windows/build/screens/library-artists.png), the existing LibraryFiltersTest (4 passing) and FamiliarArtistLookupTest (2 passing, 1 previously ignored). Installed Auralis 1.0.0; 78 checked data files and the desktop shortcut preserved. Backup: C:/Users/shrey/Downloads/Auralis-checkpoints/before-library-artists-20261013-141647. Only this task's changes are staged in files with pre-existing unrelated modifications.

## 2026-10-13 — Smoother lyric hover from the supplied video

Reviewed Desktop 2026.10.13 - 14.04.40.02.mp4, comparing the first app and Auralis after Alt+Tab. The sidebar previously used a stronger 16% highlight over 140ms, and hovering anywhere in its viewport removed blur from every inactive line. Highlights now use 10% white over a 260ms eased fade (sidebar and fullscreen); sidebar text fades over 260ms and its selected row sharpens over 320ms. Only the hovered line comes into focus. Background/opacity/blur animation values are read during drawing or by a stable graphics layer rather than rebuilding the lyric text layout for each hover frame. Fullscreen progressive blur also keeps its graphics layer present throughout the transition.

Verification: rendered LyricHoverRenderTest passed for inactive readability, active brightness and seek clicks in both layouts; SidebarLyricHoverTest passed intermediate fade-in/fade-out shade, rounded corners, hover without seeking and one seek per click; SidebarWordLyricsTest passed for the supported word-timed styles. This is renderer verification, not a measured comparison of GPU frame rates with the reference app. Installed 1.0.0 MSI, matching main/core jars, responding app window, 78 data files and desktop shortcut preserved. Backup: C:/Users/shrey/Downloads/Auralis-checkpoints/before-lyric-hover-motion-20261013-143047.

## 2026-10-13 — Compact sidebar lyric spacing

Compared the three supplied sidebar screenshots. Removed excess space in intro/interlude dot rows and reduced lyric row padding to match the denser reference. Sidebar dots are 12dp, their compact canvas reserves 18dp with the centers kept at 12dp to retain room for the upward motion; the intro occupies 23dp rather than 60dp and later break rows occupy 22dp. Lyric row vertical padding is 6dp total rather than 18dp, and viewport vertical padding is 8dp. The top fade reaches full opacity sooner so compact dots remain visible. Added an opt-in compact layout to the shared dot indicators and a headless preview using the supplied lyric lines (windows/build/screens/lyrics-spacing.png).

Verified compilation and visually inspected the rendered dot-to-text and between-row spacing. Font size and line-height preferences continue to control the text. This request changes sidebar layout spacing, with no timing logic change.

Installed Auralis 1.0.0 with matching main/core jars; all 78 checked data files and the desktop shortcut preserved. Backup: C:/Users/shrey/Downloads/Auralis-checkpoints/before-lyrics-spacing-20261013-144835.

### 2026-10-13: Import playlists popup
- Windows opens Import playlists in the existing compact liquid glass dialog, keeping the current page behind it.
- Accounts and By link tabs separate account imports from YouTube Music and Spotify link inputs; YouTube playlist selection, retry, select all, and progress stay inside the popup.
- ImportPopupTest checks compact layout, tab switching, clipboard paste, import callback, and close; account and link renders inspected.

### 2026-10-13: Retain YouTube session updates
- Windows merges matching YouTube Set-Cookie responses into each account client and retains valid rotations in the DPAPI-protected session store; stale account responses cannot overwrite a newly connected session.
- Library account snapshots use the refreshed client cookie, and Home observes a session revision so reconnecting the same account reloads its feed.
- YouTubeSessionRefreshTest and YouTubeLibraryTest passed. Live reconnected session verified: 3 Home shelves, 40 recent tracks, 2 release shelves, and 26 library playlists. Session revocation by YouTube still requires reconnection.
