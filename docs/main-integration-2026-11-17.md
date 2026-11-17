# Approved main → macos integration — 17 November 2026

## Scope and Git state

The owner approved groups 1, 3 (covers only), 4, 5, 6 and 7 of the main review. Group 2 was already fixed on macos and was preserved. The new fullscreen/sidebar expansion animation was explicitly excluded.

Before integration, all accumulated sync/pilot/notch work was saved as `63eb1afc`. Local main was fast-forwarded to fetched `origin/main` at `ffd5b50f`. This integration merges that revision with deliberate resolutions; excluding an upstream feature is intentional, not an unresolved conflict. Nothing was pushed or deployed as part of this work.

Included:

- Desktop request budgets, shared lookup/cache coordination, persistent YouTube cooldown, stream/intent failure handling, outgoing-audio stop and 256 KiB relay reads.
- Upstream Canvas lookup, signed-in Spotify fallback, persistent song IDs, cache recovery, parallel segment downloads, animated-cover local files, and first-frame handling. Animated covers stay on album artwork; the lyrics stage uses Spotify Canvas. Slow startup alone no longer discards a valid Canvas file.
- Artist credits throughout track metadata, Connect, stats, desktop persistence and Android Room v10→11 (adds nullable `artistsJson`). Adapted Cloudflare listening events to carry optional compact `as` metadata too, including old-app Firestore bridge import/export and server normalization. Longer legacy copies retain known artist credits.
- Android queue/shuffle/Play Next/swipes, animated cover startup, external playlist imports, Vivi-based lyrics/share/preview and reduced-effects settings, and Listen Together UI changes. These do not move the Listen Together backend to Cloudflare.
- Discord presence/link improvements, bot timestamp skipping, Windows bot launcher/setup scripts, probe tooling and upstream documentation.

Preserved exactly from the pre-integration macos revision: `AuralisDesktopApp.kt`, `Shortcuts.kt`, `MacWindowChrome.kt`, `WindowTitleBar.kt`, `NativeWindowFrame.kt` and desktop `SearchScreen.kt`. Main.kt only adds cooldown/diagnostic persistence and artist-credit persistence. Our Command shortcuts, native Mac window setup, notch, existing player transition, guarded platform APIs, bundled VLC/browser/signatures, pilot profile guards and disabled public sync flags remain. PlayerExpandOverlay and its coupled shortcut changes were omitted.

## Integration fixes

- Removed duplicate TrackDao fake methods that both branches independently added.
- Updated Android test setup for the new app-visibility background policy and the playlist-removal notification string; production timing/assertions were not relaxed.
- Preserved our off-main-thread VLC video initialization/cleanup and hidden-player suspension when importing the upstream cover fixes.
- Made native playback tests use the bundled Mac VLC payload instead of hard-coded Windows installation paths. Updated the relay cache test to exercise 256 KiB blocks and an actual block-boundary crossing.
- Native tests reproduced a shutdown race: a late lookup could call `setVolume` on a released native player and crash the JVM. Late lookup completion now checks coroutine cancellation; synchronous play/volume/control entry points and queued seek/crossfade work are guarded against release. Added native tests for controls after release and a provider returning after cancellation.

## Validation and limits

- Server: **109/109**, including actual Worker push/catch-up preserving artist metadata.
- Desktop core: **295 tests, zero failures, one skip** (294 passed), including artist metadata codec and local-server client cases.
- Android selected sync/artist/lyrics/Discord/swipe suites: **102/102**.
- Focused desktop run: **100 tests, 99 passed, one failed**. Account sync **50/50**, player/cover **22/22**, Mac shortcuts **2/2**, settings shortcuts **1/1**, notch **15/15**, artist-list handling **3/3**; native window chrome **6/7**.
- The failing native search drag-selection test is in unchanged Mac code/test files. It expected text to be selected/deleted but the query stayed unchanged. A broad UI run also deadlocked in unchanged headless artwork animation tests (confirmed by JVM thread dump); it was stopped. Do not describe the full desktop UI suite as green. These remain verification limitations, not evidence that every UI interaction is correct.
- A packaged isolated, signed-out Mac smoke run used bundled libVLC, started actual audio in **3381 ms**, advanced playback and paused, rendered an Apple animated-cover first frame in **1517 ms**, and opened/closed the existing full player. No personal profile or cookies were copied, and no Firebase account was used. The pilot was quit afterwards.
- Android release and Mac distributable built. The root release APK was refreshed and its original signing certificate verified. The final Mac app passed strict deep code-signature verification; **378 Mach-O binaries** had only system or bundle-relative dependencies. A final post-fix native regression run passed **8/8**, followed by a successful distributable rebuild.

No staging/production deploy, Firestore rule update, account migration, public activation or personal app-data update was performed. The artist-field server extension is local until a separately authorized staging deployment; the current public flags stay blank. Windows runtime and real Android v10→11 device upgrade are not claimed by these checks.
