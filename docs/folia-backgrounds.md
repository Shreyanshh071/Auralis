# Optional Folia-inspired Android backgrounds

Inspected Beautiful Lyrics Reborn revision `b96b6119fd417bf7c4271296c098c7afbdf00482`.
Its vendored Folia revision is `43605cd8e562b7aa0e5897478856ce3604a054a1` (0.7.18).

## Verified upstream inventory

The mode registry contains 13 modes: Classic, Cadenza, Partita, Cappella, Tilt,
Claddagh, Monet, Diorama, Pendolo, Sonnet, Tempera, Lumiere and Still. Fume is excluded.
These include lyric layouts and lyric-driven directors; they are not thirteen background renderers.

The separate background registry contains Common, Latent, Monet, Nomand and Sora.
After this distinction was explained, the requested scope was narrowed to background layers only,
with the existing lyrics system left untouched.

## Android choices

Only these five retained choices are optional entries in Appearance → Player Background:

| Choice | Native presentation |
| --- | --- |
| Folia · Latent | Moving palette mesh fields with a stable grain layer |
| Folia · Monet | Softened artwork, color wash, streaks and slow drift |
| Folia · Paper texture | Palette-mapped artwork with fibers and grain |
| Folia · Lens distortion | Radial artwork distortion and chromatic separation |
| Folia · Sora | Drifting/twinkling starfield |

These are independent Kotlin/Canvas interpretations of the background treatments, not copies of
Folia's React/WebGL renderers or pixel-identical shader ports. Latent does not claim measured
frequency-band or beat reactivity. No microphone, audio-capture permission, Spotify analysis,
network visualizer service, new lyric view or upstream player shell is used.

The 13 lyric visualizer modes are **not implemented**. Their names are not added as duplicate
background entries. No upstream fonts, emoji, avatars, lyric code, shader code or assets are bundled.

Common, Dithering, Fluted glass and Halftone dots were removed at the user's request.
Previously saved selections for these removed modes resolve to the existing Blur background.
The older Paper texture and Lens distortion preference labels still resolve to their retained modes.
These additions are Android only; no Folia backgrounds were added to Windows.

## Resource behavior and integration

Native drawing uses the existing player background host, plus an optional branch in the immersive
player's background layer. Album artwork, controls, gestures and lyrics remain foreground content.
The five original full-player options and mini-player options/defaults remain available unchanged.
The existing string preference stores the selected background; no settings/data migration is added.

Only the active optional background owns resources. Artwork treatments run off the UI thread on a
256×256 bitmap, once per source/effect/palette change, with a palette fallback if loading fails.
Drawing never performs image processing. No full-resolution offscreen blur textures are used.
Nomand variants are static. Other new backgrounds update at most 30 times/second and freeze when
paused, invisible, below STARTED, in power saver, or when system animations are disabled. Disposing
the composable cancels its frame coroutine and image request and unregisters its observers/receiver.
No per-frame Compose recomposition is requested: the time state is read during Canvas drawing.

## Licensing and provenance

- [Reborn Folia integration](https://github.com/yeahnangua/beautiful-lyrics-reborn/tree/b96b6119fd417bf7c4271296c098c7afbdf00482/Extension/Folia)
- [Folia source](https://github.com/chthollyphile/folia-major/tree/43605cd8e562b7aa0e5897478856ce3604a054a1)
- [Reborn notice](https://github.com/yeahnangua/beautiful-lyrics-reborn/blob/b96b6119fd417bf7c4271296c098c7afbdf00482/NOTICE.md)

The vendored Folia source is AGPL-3.0; Reborn's new work is AGPL-3.0-or-later. The older original
Beautiful Lyrics source has a separate, unresolved license boundary noted by Reborn. The Android
implementation here was written independently using native drawing primitives and original image
treatments; upstream source/assets are not redistributed or translated into this implementation.
Auralis's existing license is unchanged. The inspection checkout is local ignored scratch data,
not a build dependency or shipped artifact.

## Original validation before the five-choice cleanup

- 35 JVM tests: new background keys and power/lifecycle gating, existing main-player transitions,
  mini-player background behavior and appearance defaults.
- Four native tests on a Motorola edge 50 fusion (Android 16): distinct rendering for all nine
  choices, animation versus static behavior, paused/hidden clock freezing, foreground button
  interactions through the real player background host, and scrolling to every entry in the
  actual Appearance picker without changing stored preferences.
- Pixel captures of all nine backgrounds retrieved from the phone and visually inspected.
- SHA-256 comparison confirms 194 existing lyrics/provider/TTML/Connect/settings-model source
  files are unchanged from the start of this task.
- Release build and release lint passed. Both root APK deliverables were copied from their
  matching variants and hash verified. The final release installed successfully with ADB
  (`install -r`) on the connected phone after the instrumented tests.

This verifies targeted compatibility, not every app workflow or a long-duration battery benchmark.

## Five-choice cleanup validation

Only Latent, Monet, Paper texture, Lens distortion and Sora remain in the Android picker and
renderer. The four removed renderer branches and enum entries were deleted. All 35 background
compatibility unit tests and four native rendering/picker/interaction tests passed on a Samsung
Galaxy A22 running Android 13. The verified release APK updated its older Auralis installation
successfully with `adb install -r`, without clearing app data. Windows source and installed
project jars were inspected: no Folia background implementation was added there.
