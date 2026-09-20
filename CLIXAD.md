# Auralis — Android Music Streaming App

**What:** Native Android music streaming app with real-time collaborative listening, synced lyrics, and YouTube Music integration. ~8MB universal APK.

**Stack:** Kotlin 2.0, Jetpack Compose, AndroidX Media3/ExoPlayer, Room, Firebase, OkHttp, NewPipeExtractor.

## Layout
```
android/                    # Main Android project
  app/src/main/java/com/auralis/music/
    data/                   # Repositories, network, DB, downloads
    domain/                 # Domain models & interfaces
    service/                # MediaSession service
    ui/                     # Compose screens & components
      components/           # Reusable UI
      player/               # Mini & full-screen player
      lyrics/               # Synced lyrics renderer
      screens/              # Artist, settings, etc.
      viewmodel/            # State holders
  app/build.gradle.kts      # App config, env vars, deps
  build.gradle.kts          # Root plugins
```

## Build & Test
```bash
cd android

# Debug APK (33MB)
./gradlew assembleDebug

# Release APK (8MB, minified)
./gradlew assembleRelease

# Unit tests
./gradlew testDebugUnitTest

# Device tests (needs connected device)
./gradlew connectedDebugAndroidTest
```

## Run
Install `android/app/build/outputs/apk/debug/app-debug.apk` to device, or use:
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Environment
Create `.env` in workspace root or `android/`:
```
GOOGLE_WEB_CLIENT_ID=xxx
YOUTUBE_API_KEY=xxx
SPOTIFY_CLIENT_ID=xxx
SPOTIFY_CLIENT_SECRET=xxx
```
Build reads these into `BuildConfig` fields.

## Conventions
- **Package:** `com.auralis.music`
- **MinSdk:** 24, **TargetSdk:** 35, **Java/Kotlin:** 17
- **Lyrics:** Draw-phase karaoke in `SyncedLyricsView.kt` (zero recomposition)
- **Player:** `AuralisAudioService` extends Media3 MediaSessionService
- **Network:** InnerTube client + NewPipeExtractor for stream URLs
- **DB:** Room with KSP codegen; migrations in `data/local/`
- **Tests:** Unit tests under `app/src/test/`, instrumentation under `app/src/androidTest/`

## Gotchas
- Requires YouTube Music region or VPN (regional restriction)
- `google-services.json` optional; Firebase applies conditionally if present
- Release build uses debug signing config (update for production)
