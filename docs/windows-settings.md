# Windows settings

Settings open as a centered popup inside Auralis, without a separate Windows window or title bar. Stable themed surfaces keep controls legible without capturing and blurring the playback page. Its size follows the app's client area, including interface zoom. Closing it, clicking the dimmed backdrop or pressing Escape leaves the underlying route intact. The layout follows Limusic's settings organization, adapted to Auralis's Compose and VLC implementation.

Each opening waits for the first popup draw, then fades the panel and backdrop in over 360 ms. The panel scales from 90% to full size and rises by 24 dp. The search field matches section names, descriptions and control keywords, with a clear button and an empty-result message. Both navigation and settings content scroll independently.

App language changes apply immediately to the open desktop interface. Desktop UI copy uses `desktopText`, which observes the shared translation revision and reuses the bundled language resources. New desktop translations live in `windows/core/src/desktopMain/resources/i18n/desktop/translations.tsv`; missing entries fall back to English. Regional catalogues inherit their parent language. Language preferences are written synchronously to `desktop-language.properties`; selecting System default restores the original Windows locale. Translate only interface labels, keeping stored option values, filenames, track metadata and account names unchanged.

## Editing the settings

- `windows/.../settings/SettingsWindow.kt`: popup, navigation and desktop controls.
- `windows/.../settings/DesktopSettingsDialog.kt`: shared Auralis appearance, content, translation and privacy controls.
- `windows/.../settings/DesktopPreferences.kt`: persisted desktop options, Windows autostart and release checks.
- `windows/.../settings/DesktopThemeOverrides.kt`: custom accent, font families and Material control shapes.
- `windows/.../settings/DesktopGlobalHotkeys.kt`: native Windows hotkey registration and conflict reporting.
- `windows/.../settings/DesktopScrobbling.kt`: protected service credentials, metadata rules and listening thresholds.

The existing appearance, player, privacy, content, Discord and system stores are retained. Additional desktop options live in `%APPDATA%/Auralis/desktop-preferences.properties`. Scrobbling credentials and configuration are protected with Windows DPAPI in `scrobbling.properties`. The updater does not replace this data directory.

## Behavior

| Section | Implementation |
|---|---|
| General | App language, play history, tray, Windows notification center with a test button, verified Windows startup registry entry, start minimized on automatic startup, native title bar applied immediately |
| Appearance | Existing Auralis palettes and backgrounds, custom accent/tint, zoom, Material control roundness, installed/file fonts, window/taskbar icon, stage/tabbed player, open on playback, sidebar motion covers |
| Playback | Quality, actual VLC normalization, autoplay, preloading, speed, two-player crossfade, duplicates and shuffle rules, synchronized silent sidebar video, ambient sampling, video search filtering, blocked artists, extractor client selection and lyric source priority |
| Global hotkeys | RegisterHotKey on a dedicated message thread; bindings are persisted and registration conflicts are displayed |
| Discord | Existing local IPC settings and actual live preview; no Discord account authentication |
| Scrobbling | Last.fm desktop authorization, ListenBrainz token validation, actual-play time accounting, now playing, source metadata cleanup, ordered rules, per-song edits/skips, rule import/export |
| Data & storage | Live proxy configuration, current download folder and folder selection, actual cache sizes and clearing |
| About | Runtime version, GitHub stable/prerelease check, installer release link, diagnostics copy/export and repository/issues links |

The stream clients shown are the ones configurable through Auralis's NewPipe extractor (VisionOS and the optional iOS fallback), rather than Limusic's Rust client list. Crossfade is suspended in listening rooms. Videos retain audio playback independently and fall back to cover artwork when the recording has no usable video stream. Animated artwork and videos remain confined to the sidebar.

Last.fm requires an API application key/shared secret and browser approval. ListenBrainz requires the listener's token. Account-dependent scrobbling cannot be tested against a user's service until they connect it; local metadata, signatures and thresholds have automated checks.

Release checks query GitHub and show an actual result or failure. Installation of published releases remains an explicit installer action. There is no simulated update result.

Windows versions are defined independently in `windows/version.properties` (currently 1.0.0). Both the installer and bundled About/version fallback read this file; Android version settings do not participate. Publish desktop releases with `windows-v<version>` tags, for example `windows-v1.0.1`, and attach an uploaded, nonempty Auralis `.msi` or `.exe` installer. The desktop updater requires both that tag namespace and a Windows installer. APK-only releases, drafts, incomplete uploads and other platforms are ignored. Preview tags/flags honor Include prereleases. All release-feed pages are checked and versions are ordered numerically; equal or older versions never offer an update link. With only Android releases published, About reports that no Windows release has been published.

## Verification

Windows integration checks exercise actual native behavior: `WindowsStartupRegistrationTest` uses isolated registry keys to verify enable/disable, quoted executable arguments, stale paths and Windows approval state; it never changes the user's startup entry. `WindowsSongNotificationsTest` sends a silent toast under an isolated application identity, verifies it in Windows notification history and removes it. `SongNotificationPlaybackTest` plays muted local audio through VLC, delays a crossfade handoff and checks that buffering and resume cannot cause missing or duplicate notices. `verifyWindowFrame` switches system decorations on and off on the same window handle, checks the native caption and client/canvas bounds, then exercises resize, Snap, minimize and maximize. Song notices use a registered Auralis identity and Windows' built-in WinRT notification service through a hidden Windows PowerShell helper; metadata enters as encoded XML data. Windows controls whether banners are shown. Title-bar changes retain the window, application composition and playback.

Language coverage includes the shared resource strings and a checked-in inventory of desktop interface copy. Every offered locale must have a bundled entry for every inventoried string, with matching format arguments; merely translating the Settings label does not satisfy the check. Catalog fills are machine-assisted baselines for native-speaker review, preserving existing contributed translations and the reviewed overrides in `i18n/desktop/reviewed`. They are generated during development with `scripts/desktop_translations.py --fetch`, never by the running app. That script sends only checked-in interface text and preserves format arguments. Serbian Latin fills are transliterated to the correct script; Nynorsk fills use Apertium's Bokmål–Nynorsk pair after the Norwegian baseline (`--convert-nynorsk` upgrades an existing baseline). Song names, artists, user names and file paths are inserted after translation. The packaged Java runtime includes `jdk.localedata` for native language names and localized date/number formatting.

Desktop preferences now expose an observable Compose snapshot as well as the runtime flow, so controls and the active theme react to the same saved change. Appearance updates read and write atomically. Toggle rows are clickable across their full width, and option dialogs use a focused popup above Settings. Seek-bar choices match the renderer's supported values. Sidebar lyrics honor scrolling, click-to-seek, blur, alignment, romanization and translated lines. Two unsupported phone-only controls (high refresh rate and landscape scaling) are omitted from the Windows pane.

AI lyrics are opt-in and use the configured provider's real model and translation endpoints. Draft changes require Apply; model discovery returns the provider's actual list. Errors are shown, invalid line counts are rejected, and original lyric timing is preserved. Tests use a local HTTP server without real credentials or paid API calls.

`SettingsBehaviourTest` sends pointer events to toggle rows, sliders, option popups and lyric lines, and verifies live theme changes and atomic persistence. `DesktopAiLyricsTest` checks HTTP requests, model discovery, translation results and malformed-response handling.

Fullscreen lyric wheel input uses one desktop handler for both line and word synchronization. Travel is measured in display pixels, with bounded event/burst distances and frame-by-frame easing; reversing direction drops pending travel. Reaching the list ends or interrupting with a drag cannot leave stored momentum or disable later wheel input. Manual scrolling suspends automatic following through the existing re-sync behavior. Automatic centering also animates small corrections instead of snapping deltas below 48 pixels. `LyricsScrollBehaviourTest` exercises actual wheel events, differing lyric row heights, both production lyric formats, fine deltas, direction changes, boundaries, interrupted gestures and sub-48-pixel centering.

`SettingsOverhaulTest` renders every section at 1080 and 720 pixels, checks close handling, persisted options, hotkey parsing, metadata corrections and timing. `CrossfadePlaybackTest` runs muted local WAV playback through real VLC and checks overlapping streams and pause cleanup. Related navigation, Discord, queue, session and guest-search regressions are included in the final test run. `probeGuest` performs real anonymous searches and audio byte requests.
