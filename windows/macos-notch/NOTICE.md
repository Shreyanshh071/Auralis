# Auralis Notch: upstream attribution

This is a modified, music-only derivative of [TheBoredTeam/boring.notch](https://github.com/TheBoredTeam/boring.notch), **v2.7.3**, commit `16b0f11f51c79d42e27c10d77fd9e53c11410fdb`. GNU General Public License version 3; see LICENSE. No warranty. You may modify and redistribute under those terms.

Original authors include Harsh Vardhan Goswami, Richard Kunkli, Hugo Persson, Mustafa Ramadan, Kraigo, Kai Azim, Alexander, and the upstream contributors. Original file headers are retained. The notch shape derives from DynamicNotchKit by Kai Azim; its MIT notice is retained in THIRD_PARTY_LICENSES.

Modified for Auralis on **2026-11-11**:

- Retained native SwiftUI/AppKit music artwork, matched geometry, marquee text, hover controls, timeline, decorative spectrum, shape, sizing and spring parameters.
- Adapted ContentView, BoringHeader, BoringViewModel and the music controls to a fixed music-only layout. Removed calendar, shelf, camera, battery, system HUDs, unrelated gestures, onboarding, updater, account integrations and standalone settings.
- Replaced MusicManager's media sources with private parent-process JSON over stdin/stdout, supporting Auralis alone. No MediaRemote, AppleScript, global media control or Spotify/YouTube controllers are included.
- Added distinct bundle identity, parent-process/EOF cleanup, heartbeat hiding, screen handling and pointer pass-through outside the visible notch.
- Removed Xcode preview macros so the retained components compile using Command Line Tools. Added spectrum timer cleanup, safe slider division, initial marquee height, and finite/bounded progress handling.
- Retained the default white slider, artwork tint, lighting effect, curved shape, hover timing and default previous/play/next control layout; these are fixed defaults rather than a settings system.

The app's About this notch menu opens the bundled source and licenses. Auralis is separately licensed under GPL v3 as well.

2026-11-12: restored the beta-style lyrics slot under the artist, powered entirely by Auralis’s existing lyrics controller. Added original CoreText karaoke rendering, a SpicyLyricsMotion-style letter lift/scale/glow, true provider syllables and word fallback, actual unsynced text, playback-rate handling, prefetching, and pause/buffer/seek/track-change behavior. No upstream lyrics scraping/service is included.
