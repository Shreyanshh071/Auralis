# Android animated covers

Settings → Appearance → Player contains Animated covers, source selection
(Auto, Apple Music, Tidal, Curated library), and a Wi-Fi-only switch.
Defaults: enabled, automatic source, all networks.

The classic, new, and immersive players keep their static cover visible while
looking up and loading motion artwork. Media3 plays HLS or MP4 silently on a
TextureView, fading in after its first frame. It pauses with music and when the
activity stops, releases on disposal, and falls back to the still cover on errors.
Only the active song's page creates a video player. Wi-Fi-only mode reacts to
network changes and removes video playback when Wi-Fi is unavailable.

The Android resolver adapts the existing desktop matching: recording version,
credited artists, and known album must match. Found URLs expire after a day;
misses retry after a minute. No result is fabricated when a source is unavailable.
Availability depends on the selected catalog and song.

Reference behavior and sources:
- https://github.com/vivizzz007/vivi-music/blob/main/app/src/main/kotlin/com/music/vivi/ui/screens/settings/MotionartworkSelection.kt
- https://github.com/vivizzz007/vivi-music/blob/main/app/src/main/kotlin/com/music/vivi/ui/player/MotionartworkPlayer.kt
- https://github.com/vivizzz007/vivi-music/tree/main/Motionartwork

Validation: AnimatedCoverMatchTest checks recording/version discrimination.
AnimatedCoverDeviceTest resolves an actual cover and checks first-frame decoding,
silent output, and pausing on hardware. Its isolated test runner avoids starting
unrelated account or playback services.
Build the isolated test APK with `:app:assembleDebugAndroidTest -PmotionArtworkTests`
and run `com.auralis.music.test/com.auralis.music.MotionArtworkTestRunner`.
The three matching tests and the live hardware decoding/pause test passed on the
connected Motorola edge 50 fusion on 2026-10-25.
