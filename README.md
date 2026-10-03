<div align="center">
  <img src="docs/logo-round.png" width="130" height="130" alt="Auralis logo" />
  <h1>Auralis</h1>
  <p>Native Android music streaming and listening together</p>

  ![Latest release](https://img.shields.io/github/v/release/Shreyanshh071/Auralis)
  ![Android](https://img.shields.io/badge/Android-7.0%2B-green)
  ![License](https://img.shields.io/badge/license-GPL--3.0-blue)

  [Download APK](https://github.com/Shreyanshh071/Auralis/releases/latest) · [Website](https://auralis-self-nu.vercel.app) · [Release notes](docs/release-notes-v1.1.1.md)
</div>

Auralis is an ad-free music client built with Kotlin, Jetpack Compose, AndroidX Media3, Room, and Firebase. Music availability depends on YouTube Music and your region. Lyrics, recognition, and catalog results depend on their respective providers.

## Current release: 1.1.1

Auralis 1.1.1 brings playback fixes, improved lyrics, playlist downloads, and better Listen Together cleanup.

- **App version:** 1.1.1; Android version code: 3.
- **Source code:** [1.1.1 release source](https://github.com/Shreyanshh071/Auralis/tree/v1.1.1-rebuilt).
- **APK:** `Auralis-v1.1.1-universal.apk`, approximately 24.6 MB (23.5 MiB), Android 7.0/API 24 or newer.
- **Build:** release variant with R8 and resource shrinking, non-debuggable. It uses the project's existing signing certificate for update compatibility; the current Gradle configuration uses the debug signing configuration for release.

### New in 1.1.1

| Area | Included changes |
| --- | --- |
| Playback | Foreground-service idle callback protection; downloaded queues stay offline/download-only; existing selected playback and stream-resolution improvements. |
| Lyrics | Provider/parser improvements, preserved original script, full wording checks, rejection of defective micro-stutter timing, scrolling/alignment and blur presentation updates, and re-sync control. Lyrics quality still varies by source. |
| Playlists | Handle-only drag reordering, playlist order lock, stable imported playlist identity, playlist listening statistics and Speed Dial ranking. |
| Downloads | Playlist download jobs, progress/folder presentation, public playlist copies under `Download/Auralis`, offline playback and removal management. |
| Import and search | Spotify/YouTube import UI, YouTube account/playlist picker, selected mixed-search ranking, and identifiable Shorts filtering/continuation. |
| Artwork | Existing Spotify release artwork is preserved during YouTube playback matching. Explicit artwork selections have a persisted write boundary separate from playback identity. No automatic canonical-release selector or bulk artwork repair is included. |
| Account and rooms | Account isolation, coordinated account deletion, Listen Together membership/vote cleanup, retained listener requests, room-close cleanup, and persisted retry handling. |
| Interface | Selected player, profile, playlist, lyrics and pill presentation improvements. |

See [the release notes](docs/release-notes-v1.1.1.md) for verification scope and limitations.

## Features

### Music and playback

- Search songs, albums, artists and playlists through YouTube Music.
- Background playback with Android media notification and lock-screen controls.
- Queue management, repeat/shuffle, offline downloads and audio settings.
- Artist pages, listening statistics, recommendations and playlist Speed Dial.
- Music recognition integrations; availability depends on provider configuration.

### Lyrics

- Synced lyrics from multiple providers, including Musixmatch, LRCLIB, KuGou, AMLL and YouTube Music.
- Line and word timing where available, lyric cards, cached lyrics and re-sync.
- Translation/romanization options where supported. Automatic rewriting of original-script lyrics is not a release guarantee.

### Listen Together

- Shared rooms, host playback controls, member presence, song requests and voting.
- Leaving listeners lose membership and votes; their requests remain for the ongoing session.
- Host exit closes the room and cleans its related records. Alone/idle closure includes a warning period.
- Failed/offline cleanup is retried when the app can reconnect. There is **no independent server cleanup job** on the current Spark setup; permanent offline/uninstalled hosts cannot be guaranteed immediate cleanup.
- Synchronization depends on network/device conditions; no fixed latency guarantee is made.

### Accounts and imports

- Google sign-in and Firebase synchronization of supported library/listening data.
- Spotify and YouTube playlist import. Upstream mappings matter: a Spotify compilation record can legitimately carry compilation artwork, including playlists converted by third-party services.
- YouTube matching supplies playback identity without blindly replacing existing Spotify release artwork.
- Account deletion coordinates cloud cleanup and authentication deletion. Normal deletion was verified; every failure/re-authentication/multiple-device scenario has not been live-tested.

### Downloads and updates

- Offline audio and playlist download/removal controls.
- Public playlist copies appear under `Download/Auralis/<playlist folder>`. **Public copies may remain after Clear storage or uninstall**; app-private data and public exports have different lifecycles.
- GitHub release checks and APK download/install flow. Android installation permission and provider access may be required.

## Screenshots

Screenshots illustrate the interface and may differ from the current build.

| Player | Lyrics | Listen Together |
| --- | --- | --- |
| ![Player](docs/screenshots/player.jpg) | ![Lyrics](docs/screenshots/lyrics.jpg) | ![Listen Together](docs/screenshots/listen_together.jpg) |

## Verification

The current source passed 57 focused JVM tests covering Spotify artwork preservation, lyrics validation, handle-only reorder, foreground release behavior, managed download storage, room cleanup persistence, room cleanup and account deletion helpers. Debug and release builds completed successfully. The version-only rebuild was installed and opened on the connected test phone.

Earlier device/emulator checks covered normal playback, selected lyrics/reorder/download behavior, normal Firebase account deletion, and Listen Together exit/retry/timer scenarios. These checks are scoped evidence, not a claim that every device, provider response or failure mode has been tested. Room instrumentation tests are present in the source; they are not included in the 57-test JVM total.

No old-library artwork migration or restoration was performed for this release. There is no promise to automatically correct every historical cover.

## Build from source

Use the **published release tag**, not an arbitrary default-branch checkout, for this APK's source.

The Android project is under `android/`. Use the Gradle wrapper with JDK 21, Android SDK 35 and the configured NDK. Firebase configuration and any provider credentials must match your own setup. The build also expects the configured Discord/WebRTC SDK JARs, native libraries and Discord C++ headers; these local dependencies are not all tracked in Git.

```powershell
cd android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug assembleRelease
```

Do not publish private credentials, user databases or backups. Native SDK dependencies have their own licensing/setup requirements.

## Technology

Kotlin · Jetpack Compose / Material 3 · AndroidX Media3 · Room / DataStore · Coroutines / Flow · OkHttp · Coil · Haze · Firebase Auth / Firestore

## Support and license

[Website](https://auralis-self-nu.vercel.app) · [Support the project](https://www.buymeachai.in/shreyanshh071)

Licensed under [GPL-3.0](LICENSE). Third-party components retain their respective notices and licenses.
