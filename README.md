# Auralis 🎵

> A modern, sleek, and high-performance native music streaming app built with **Jetpack Compose**, **AndroidX Media3**, **Kotlin Coroutines**, and **Material 3**.

---

## ✨ Features

- 🎧 **Uninterrupted Background Audio**: Full background streaming with `PARTIAL_WAKE_LOCK`, `WifiLock`, and native foreground `MediaSessionService` that never stops when minimized or screen locked.
- 🎛️ **Android 13/14 Quick Settings & Lock-Screen Deck**: Native system media card featuring monochrome app badge, interactive scrub seekbar, previous/next controls, like/heart toggle, repeat mode, and play/pause.
- 📜 **Multi-Provider Synced Lyrics Engine**: Real-time synchronized line-by-line karaoke lyrics powered by **Musixmatch** (Spotify catalog), **LRCLIB**, **KuGou** (200M+ synchronized catalog), **AMLL**, and official **YouTube Music** record-label lyrics.
- 🎨 **Official Record-Label Studio Artwork**: High-resolution studio album covers (`=w1200-h1200`) and verified circular artist avatar photos.
- ⚡ **Dynamic Speed Dial & Taste Profiler**: Personal speed dial recommendations built from real listening history, heavy rotation, and top-played tracks.
- 🧑‍🎤 **Full Artist Profiles & Discography**: Explore complete artist profiles with official portrait banners, subscriber count, bio, top songs, studio albums, singles, and similar artists.
- 🔍 **Real-Time Search & Autocomplete**: Instant search suggestions across songs, albums, artists, and playlists with parallel query filtering.
- 📦 **Local-First Architecture**: Powered by Room database (`AuralisDatabase`) with full offline caching of tracks, playback history, favorites, and playlists.
- ☁️ **Cloud Sync & Playlist Import**: Firebase Auth and Google Account Sync with one-click Spotify & YouTube playlist import.

---

## 🛠️ Architecture & Tech Stack

- **UI & Presentation**: [Jetpack Compose](https://developer.android.com/jetpack/compose), [Material 3](https://m3.material.io/), [Coil](https://coil-kt.github.io/coil/) (Image Loading)
- **Audio & Media**: [AndroidX Media3](https://developer.android.com/media/media3) (`MediaSessionService`, `ExoPlayer`, `ForwardingPlayer`)
- **Async & Reactive**: [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & [StateFlow](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- **Local Storage**: [Room Database](https://developer.android.com/training/data-storage/room) & [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- **Networking**: [OkHttp 4](https://square.github.io/okhttp/) & Custom InnerTube Web Client
- **Lyrics Providers**: Musixmatch, LRCLIB, KuGou, AMLL, and YouTube Music
- **Cloud & Auth**: Firebase Auth, Google Sign-In, Firestore Sync

---

## 🚀 Building & Installing

### Prerequisites

- **Android Studio Ladybug** or newer
- **JDK 17** or **JDK 21**
- **Android SDK** (API 34 / 35 / 36)

### Build Debug APK

```powershell
cd android
.\gradlew.bat assembleDebug
```

The APK will be generated at:
`android/app/build/outputs/apk/debug/app-debug.apk`

### Install Directly to Connected Android Device

```powershell
cd android
.\gradlew.bat installDebug
```

---

## 📂 Project Structure

```
Auralis/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/auralis/music/
│   │   │   │   ├── data/
│   │   │   │   │   ├── local/          # Room DB, DAOs, Entities
│   │   │   │   │   ├── network/        # InnerTubeClient, LyricsClient, Spotify/YT Importers
│   │   │   │   │   ├── parser/         # LRC & TTML timestamp parsers, LyricsMatcher
│   │   │   │   │   ├── repository/     # Repository implementations
│   │   │   │   │   └── service/        # AuralisAudioPlayer, YouTubeAudioEngine
│   │   │   │   ├── domain/             # Domain Models & Repository Interfaces
│   │   │   │   ├── service/            # AuralisMediaService (Media3 Session & System Deck)
│   │   │   │   ├── ui/                 # Jetpack Compose UI (Screens, ViewModels, Themes)
│   │   │   │   │   ├── home/           # HomeScreen & SpeedDial
│   │   │   │   │   ├── explore/        # Search & Explore screens
│   │   │   │   │   ├── library/        # Playlists & Favorites
│   │   │   │   │   ├── lyrics/         # Synced Lyrics view
│   │   │   │   │   ├── player/         # MiniPlayer & NowPlaying Modal
│   │   │   │   │   ├── screens/        # ArtistScreen & Sub-views
│   │   │   │   │   └── viewmodel/      # Architecture ViewModels
│   │   │   │   └── MainActivity.kt     # Main Android Entry Point
│   │   │   └── res/                    # Drawables, icons, layout values
│   │   └── build.gradle.kts
│   └── build.gradle.kts
└── README.md
```

---

## 💖 Sponsor This Project

If you love using **Auralis** and want to support its ongoing development:

[![Buy Me A Chai](https://img.shields.io/badge/Buy%20Me%20A%20Chai-☕-orange?style=for-the-badge&logo=coffeescript&logoColor=white)](https://www.buymeachai.in/shreyanshh071)

Your support helps keep the project fast, ad-free, open-source, and constantly improving!

---

## 📄 License

This project is open-source under the [MIT License](LICENSE).
