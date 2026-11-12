# Auralis Notch on macOS

Auralis now bundles a music-only derivative of **Boring Notch 2.7.3**, using its native SwiftUI/AppKit music interface instead of the initial Compose replacement. Distribution targets Apple Silicon and macOS 14 or later.

Enable it under Settings → Appearance → Immersive mode → Auralis notch. The native UI retains Boring Notch’s curved shape, 640 × 190 expanded layout, spring parameters, matched artwork transition, artwork glow and app badge, marquee title/artist, hover buttons, timeline, and decorative spectrum. Calendar, shelf, camera, battery, system HUDs, onboarding, updater, standalone settings, media-source selection, and independent launch-at-login registration are removed. The gear opens Auralis’s existing settings.

## Integration and lifecycle

All playback remains in the common desktop Kotlin controller. `AuralisNotch` sends its state to a private child over newline-delimited JSON; controls return over stdout. No network port, MediaRemote API, AppleScript, global media reader, or separate user installation is involved. A browser cannot replace the Auralis track or receive its buttons. Remote Connect playback does not take the local notch. Unknown, stale-track, and invalid seek commands are rejected.

The child has its own bundle identity (`com.auralis.music.notch`) and lives inside Auralis’s bundled resources. It is started hidden before requesting notch ownership. EOF/parent exit terminates it; stale heartbeats hide it. Disabling the notch stops it. A parent shutdown closes the pipe, and child cleanup has a force-termination fallback. Pausing retains ownership for 10 seconds; playback/buffering cancels that timer. Locking hides the notch and yields to the existing lock-screen player.

The native window uses a transparent nonactivating `NSPanel` above the menu bar, matching upstream. It keeps the SwiftUI canvas fixed so SwiftUI animates the actual notch rather than repeatedly resizing an AWT window. Pointer pass-through follows the visible content bounds. Screen changes recalculate logical geometry and camera safe areas. A screen without a notch uses upstream’s non-notched sizing.

## Boring Notch coexistence

The integration identifies the original app by bundle ID and never changes its installation, preferences, updater or login items. It never quits or relaunches it.

Handoff still uses public `NSRunningApplication.hide/unhide`. Auralis waits for both the hidden flag and disappearance of onscreen windows, restores only apps it hid, and respects manual reveals. Missing or failed helpers release any acquired visibility. Hard-killing the parent cannot guarantee restoration of another app’s visibility.

**Boring Notch 2.7.3 on the development Mac refuses the hide request.** Automatic handoff remains unavailable there. Auralis suppresses its panel and explains the conflict in settings. Quit Boring Notch manually to use the Auralis-only interface. Reusing its UI source does not grant control over an existing stock Boring Notch process.

## Build and verify

`windows/macos-notch` contains the pinned upstream components, native adapter, build script, GPL license, third-party notices and modification history. The helper bundles corresponding source and build instructions; About this notch opens them. It has no third-party Swift packages. Command Line Tools suffice; full Xcode is not required. An explicit SwiftUI State property-wrapper alias avoids the optional SDK 27 Xcode macro.

Gradle builds and signs the helper for macOS development runs, tests and packaging:

```sh
./gradlew :windows:test --tests 'com.auralis.music.windows.notch.NotchTest'
./gradlew :windows:packageDmg
```

Tests cover pause/session timing, remote playback, conflict leases and restoration, bounded command parsing, seek clamping, Swift/Kotlin protocol compatibility, native nonactivating panel configuration, settings persistence, EOF cleanup and orphan prevention. Native protocol tests run hidden and do not change Boring Notch’s visibility.

For visual and interactive checks:

```sh
bash windows/macos-notch/build.sh /tmp/AuralisNotch.app
/tmp/AuralisNotch.app/Contents/MacOS/AuralisNotch --self-test
/tmp/AuralisNotch.app/Contents/MacOS/AuralisNotch --preview
/tmp/AuralisNotch.app/Contents/MacOS/AuralisNotch --render-preview /tmp/auralis-notch.png
```

The interactive preview is centered away from the physical notch. Real-device checks of Spaces, fullscreen apps, sleep/wake and lock/unlock are still needed across supported OS releases. The Mac distribution now bundles Java, libVLC/codecs, the native notch, and a private Chromium fallback for YouTube sign-in; no separate applications or developer tools are required. The build remains ad-hoc signed rather than Developer ID signed/notarized. See `docs/macos-beta-distribution.md` for the packaged runtime check.

## Syllable, word and static lyrics

The expanded notch includes the beta-style lyrics row under the artist. It uses `DesktopLyricsController`, its selected provider/aligned timing, and the same smooth playback clock as the main player. The notch renders the same `PlayerLyricsContent` composable as the main player in a persistent offscreen Compose scene, following the selected animation style and bundled font. Genuine nested syllables produce the player’s karaoke sweep and rising/scaling/glowing letter animation; timed words work without inventing syllables. Line-only sources change on real line timestamps. Plain sources display their actual unsynced text with an indicator and no karaoke effect.

Three upcoming/current lines are sent through the private pipe so the native timeline can cross boundaries between heartbeats. While expanded, the parent sends compact PNG frames over the existing private pipe at up to 60 fps; the Swift shell displays them without recreating the animation. The surface is disposed when collapsed and slows while paused. Provider whitespace and unknown durations remain unchanged. Vocal rests, pause/buffering, playback speed, backward seeks, loading, missing lyrics and track changes are handled explicitly. The compact notch is unchanged; the expanded panel gains 28 points of height to keep controls clear.
