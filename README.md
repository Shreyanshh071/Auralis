<div align="center">
  <img src="docs/logo-round.png" width="130" height="130" alt="Auralis Logo" />
  <h1>Auralis</h1>
  <p><b>Next-Generation Native Music Streaming & Collaborative Group Listening</b></p>

  ![GitHub release (latest by date)](https://img.shields.io/github/v/release/shreyanshchoubey09/Auralis?style=for-the-badge&color=8A2BE2)
  ![APK Size](https://img.shields.io/badge/APK%20Size-8.3%20MB%20(Universal)-32CD32?style=for-the-badge&logo=android&logoColor=white)
  ![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)
  ![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
  ![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
  ![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=for-the-badge)
  [![Buy Me A Chai](https://img.shields.io/badge/Buy%20Me%20A%20Chai-☕-orange?style=for-the-badge&logo=coffeescript&logoColor=white)](https://www.buymeachai.in/shreyanshh071)

  <br />

  **Auralis** is a modern, high-performance, ad-free native music streaming and collaborative listening experience crafted with **Jetpack Compose**, **AndroidX Media3**, **Kotlin Coroutines**, and **Material 3**.

  <br />

  [Website](https://auralis-self-nu.vercel.app) • [Download APK](https://github.com/shreyanshchoubey09/Auralis/releases/latest) • [Screenshots](#-screenshots) • [Ultra-Lightweight (~8.3 MB)](#-ultra-lightweight--universal-architecture-83-mb) • [Features](#-features) • [FAQ](#-frequently-asked-questions-faq) • [Tech Stack](#%EF%B8%8F-architecture--tech-stack) • [Sponsor](#-sponsor-this-project)

</div>

> [!WARNING]
> **Regional Restriction** — If YouTube Music is unavailable in your region, this app will not work without a VPN or proxy connecting to a supported region.

---

## ⚡ Ultra-Lightweight & Universal Architecture (~8.3 MB)

> **Unlike most modern music apps that weigh anywhere between 30 MB to 100+ MB (and force users to download separate architecture-specific split APKs), Auralis delivers a full-featured, universal production APK at just ~8.3 MB.**

### 🛠️ **How We Reduced the APK Size to Just 8.3 MB**
- **100% Pure Native Jetpack Compose & AndroidX**: **We do not bundle heavy JavaScript runtimes, WebViews, Electron wrappers, or cross-platform framework overhead.** Every screen is rendered directly on the native GPU canvas.
- **Zero Heavy C/C++ Native Binary Bloat**: **Rather than packaging 50+ MB of redundant native `.so` binaries (like heavy custom FFmpeg or VLC engines), Auralis uses an ultra-optimized native AndroidX Media3 / ExoPlayer pipeline and lightweight OkHttp/InnerTube engine directly.**
- **Aggressive R8 / ProGuard Optimization**: **Production builds run full R8 whole-program optimization with automated dead-code stripping, member inlining, and class merging.**
- **Automated Resource & Vector Shrinking**: **All icons, badges, and illustrations are authored as scalable Android Vector Drawables with dynamic runtime gradients rather than heavy uncompressed raster bitmaps.**

### 🚀 **The Major Pros & Real-World Advantages**
- **Universal Compatibility for 100% of Android Devices**: **No need to guess your phone's processor architecture (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) — a single universal 8.3 MB APK installs flawlessly on any modern Android device, emulator, or Chromebook.**
- **Lightning-Fast Cold Starts & Instant 120 FPS Navigation**: **With minimal bytecode and zero framework bloat, Auralis launches in under 200 milliseconds and consumes a fraction of the RAM of other music clients.**
- **Maximum Free Storage for Your Music**: **Saves precious device storage so you can download hundreds of high-fidelity offline songs without filling up your internal drive.**
- **Instant Over-The-Air (OTA) Updates**: **Lightweight download size means updates download and install in seconds, even on slow or metered mobile data connections.**

---

## 📸 Screenshots

<div align="center">
  <table>
    <tr>
      <td align="center" width="25%">
        <img src="docs/screenshots/player.jpg" alt="Now Playing" width="100%" /><br />
        <sub><b>Now Playing</b></sub>
      </td>
      <td align="center" width="25%">
        <img src="docs/screenshots/lyrics.jpg" alt="Synced Lyrics" width="100%" /><br />
        <sub><b>Synced Karaoke Lyrics</b></sub>
      </td>
      <td align="center" width="25%">
        <img src="docs/screenshots/artist.jpg" alt="Artist Profile" width="100%" /><br />
        <sub><b>Artist Discography</b></sub>
      </td>
      <td align="center" width="25%">
        <img src="docs/screenshots/listen_together.jpg" alt="Listen Together" width="100%" /><br />
        <sub><b>Listen Together</b></sub>
      </td>
    </tr>
    <tr>
      <td align="center" width="25%">
        <img src="docs/screenshots/recognition.jpg" alt="Music Recognition" width="100%" /><br />
        <sub><b>Music Recognition</b></sub>
      </td>
      <td align="center" width="25%">
        <img src="docs/screenshots/search.jpg" alt="Search & Explore" width="100%" /><br />
        <sub><b>Search & Discovery</b></sub>
      </td>
      <td align="center" width="25%">
        <img src="docs/screenshots/account_sync.jpg" alt="Cloud Sync & Importer" width="100%" /><br />
        <sub><b>Playlist Importers</b></sub>
      </td>
      <td align="center" width="25%">
        <img src="docs/screenshots/discord.jpg" alt="Discord Rich Presence" width="100%" /><br />
        <sub><b>Discord Presence</b></sub>
      </td>
    </tr>
  </table>
</div>

---

## ✨ Features

### 🌐 Listen Together (Real-Time Group Listening)
- **Synchronized Playback Rooms**: Listen to tracks simultaneously with friends in real time (<50ms sync latency) powered by Firebase Firestore and dynamic drift calculation.
- **Dedicated Host Controls & Listener Protection**: Prevents accidental desync by blocking listener playback alterations from Bluetooth earphones, TWS touch gestures, or lockscreen controls while keeping volume independent.
- **Smart Song Recommendations & Voting**: Room members can search, recommend, and upvote songs in a shared room queue.
- **Real-Time Member Presence & Pill Alerts**: Instant floating animated notifications when friends join, leave, or disconnect.

### 🎨 Visual Excellence & Modern Aesthetics
- **Dynamic Blurred Artwork Player**: Fluid multi-layer ambient background that adapts seamlessly to the playing song's artwork palette.
- **Glassmorphic Floating Sheets & Controls**: Ultra-premium Frosted Glass / Haze effect across player sheets, dialogs, and popups.
- **Fluid Spring Animations & 120 FPS Scrolling**: Zero-lag scrolling performance with optimized artwork caching and lightweight list item bindings.
- **Verified Studio Artworks & Portrait Fallback**: High-resolution studio album covers (`=w1200-h1200`) and automatic Wikipedia portrait resolution for artists with blank avatars (e.g. Kanye West).

### 📜 Multi-Engine Synced Lyrics Ecosystem
- **5-Tier Lyrics Integration**: Real-time karaoke-style line-by-line and word-by-word synced lyrics from **Musixmatch** (Spotify catalog), **LRCLIB**, **KuGou** (200M+ synchronized catalog), **AMLL**, and official **YouTube Music** record-label lyrics.
- **AI Translation & Romanization**: One-tap AI translation to English and Pinyin/Romaji/Hangul transliteration for foreign language tracks.
- **Spotify-Style Lyric Card Sharing**: Generate and export customizable aesthetic lyric cards directly to social media.
- **Offline Caching**: Automatically saves fetched synchronized lyrics for instant offline access.

### 🎧 Audiophile-Grade Playback Engine
- **Uninterrupted Background Streaming**: Rock-solid playback with `PARTIAL_WAKE_LOCK`, `WifiLock`, and native foreground `MediaSessionService` that never sleeps.
- **Android 13/14 Quick Settings & Lock-Screen Deck**: Native system media card featuring monochrome app badge, interactive scrub seekbar, previous/next controls, like/heart toggle, and repeat modes.
- **Offline Downloads & Local Library**: Download tracks directly to local storage for offline playback with high-fidelity audio options.
- **Spatial Audio & Custom Equalizer**: Fine-tune your soundstage with built-in spatialization, pitch, and playback speed adjustments.

### 🔍 Discovery, Search & Music Recognition
- **Instant Search & Autocomplete**: Lightning-fast search suggestions across songs, albums, artists, and playlists with parallel query filtering.
- **Music & Voice Recognition**: Identify songs playing around you using built-in acoustic fingerprinting (Shazam / ACRCloud / SongRec).
- **Taste Profiler & Speed Dial**: Personalized home feed tailored to your real listening habits, heavy rotation, and top-played artists.
- **Full Artist Discography**: Artist bios, monthly listener counts, top tracks, albums, singles, and related artist graphs.

### ☁️ Cloud Sync & Playlist Importer
- **One-Click Playlist Import**: Effortlessly import playlists from Spotify and YouTube directly into your library.
- **Google Account & Firebase Sync**: Sync your favorites, custom playlists, and listening history securely across devices.

### 🔄 In-App Direct OTA Updater
- **Instant Update Notifications**: Checks GitHub Releases automatically and notifies you of new versions.
- **In-App Background Download & Install**: Download APK updates with a live progress bar and install seamlessly with one tap.

---

## 🛠️ Architecture & Tech Stack

| Layer | Technologies |
| :--- | :--- |
| **UI & Presentation** | [Jetpack Compose](https://developer.android.com/jetpack/compose), [Material 3](https://m3.material.io/), [Haze Blur](https://github.com/chrisbanes/haze), [Coil 2.6](https://coil-kt.github.io/coil/) |
| **Audio Engine** | [AndroidX Media3](https://developer.android.com/media/media3) (`ExoPlayer`, `MediaSessionService`, `ForwardingPlayer`), `AudioTrack` |
| **Concurrency & Reactive** | [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html), [StateFlow & SharedFlow](https://developer.android.com/kotlin/flow) |
| **Local Persistence** | [Room Database](https://developer.android.com/training/data-storage/room) (`AuralisDatabase`), [AndroidX DataStore](https://developer.android.com/topic/libraries/architecture/datastore) |
| **Networking & Extraction** | [OkHttp 4](https://square.github.io/okhttp/), Custom InnerTube Web Client, NewPipe Extractor |
| **Backend & Sync** | [Firebase Auth](https://firebase.google.com/products/auth), [Cloud Firestore](https://firebase.google.com/products/firestore), Google Sign-In |
| **Lyrics Providers** | Musixmatch, LRCLIB, KuGou, AMLL, YouTube Music |

---

## 📂 Project Structure

```
Auralis/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/auralis/music/
│   │   │   │   ├── data/
│   │   │   │   │   ├── datastore/      # Preferences & Settings DataStore
│   │   │   │   │   ├── download/       # Offline Audio Download Manager
│   │   │   │   │   ├── local/          # Room DB, DAOs, Entities
│   │   │   │   │   ├── network/        # InnerTubeClient, LyricsClient, Spotify/YT Importers
│   │   │   │   │   ├── parser/         # LRC & TTML timestamp parsers, LyricsMatcher
│   │   │   │   │   ├── repository/     # Repository implementations
│   │   │   │   │   ├── service/        # AuralisAudioPlayer, YouTubeAudioEngine
│   │   │   │   │   └── sync/           # ListenTogetherManager & Math Engine
│   │   │   │   ├── domain/             # Domain Models, Auth & Interfaces
│   │   │   │   ├── service/            # AuralisMediaService (Media3 Session & Deck)
│   │   │   │   ├── ui/                 # Jetpack Compose UI
│   │   │   │   │   ├── components/     # Reusable UI Cards, Modals, Pills
│   │   │   │   │   ├── home/           # HomeScreen, SpeedDial & Sections
│   │   │   │   │   ├── explore/        # Search & Explore screens
│   │   │   │   │   ├── library/        # Playlists, Downloads & History
│   │   │   │   │   ├── lyrics/         # Synced Lyrics & Lyric Card Creator
│   │   │   │   │   ├── player/         # MiniPlayer & NowPlaying Fullscreen Modal
│   │   │   │   │   ├── screens/        # ArtistScreen, Settings & Sub-views
│   │   │   │   │   └── viewmodel/      # Architecture ViewModels
│   │   │   │   └── MainActivity.kt     # Main Android Entry Point
│   │   │   └── res/                    # Drawables, icons, layout values
│   │   └── build.gradle.kts
│   └── build.gradle.kts
├── .github/
│   └── FUNDING.yml                     # Sponsor Configuration
└── README.md
```

---

## ❓ Frequently Asked Questions (FAQ)

<details>
<summary><b>Why is the Auralis APK only 8.3 MB compared to other 30–100 MB music apps?</b></summary>
<br>

Auralis is built 100% natively using modern **Jetpack Compose**, **AndroidX Media3**, and an ultra-lean network extractor without packing heavy C/C++ native runtime binaries or web engine bloat. Thanks to rigorous **R8 whole-program optimization** and vector-first assets, Auralis achieves an ultra-lightweight **8.3 MB Universal APK** that installs on any Android device with blazing-fast 200ms cold starts.
</details>

<details>
<summary><b>Which APK should I download? Do I need to know my phone's CPU architecture?</b></summary>
<br>

**You do NOT need to check your phone's processor!** Simply download `Auralis-v1.0.0-universal.apk` (or `Auralis.apk`). It is a single, universal build that automatically supports all Android CPU architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) out of the box.
</details>

<details>
<summary><b>How does Auralis recommend music for brand-new users?</b></summary>
<br>

Fresh installs start with an instant offline/online taste seed pool across curated artist discographies (*Tame Impala, Kanye West, Karan Aujla, Radiohead, KR$NA, Arijit Singh, KK, Shreya Ghoshal, Atif Aslam*) with non-music noise spam filtered out. As soon as you begin listening, our real-time adaptive engine smoothly learns your authentic taste.
</details>

<br />

> 🌐 **Have more questions?** Visit our official website & help center at **[auralis-self-nu.vercel.app/#faq](https://auralis-self-nu.vercel.app/#faq)** for additional FAQs, setup guides, and feature walkthroughs.

---

## 💖 Sponsor This Project

If you love using **Auralis** and want to support its ongoing development:

[![Buy Me A Chai](https://img.shields.io/badge/Buy%20Me%20A%20Chai-☕-orange?style=for-the-badge&logo=coffeescript&logoColor=white)](https://www.buymeachai.in/shreyanshh071)

Your support helps keep the project fast, 100% ad-free, open-source, and constantly improving!

---

## 📄 License

This project is free and open-source software licensed under the **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)**.
You are free to use, modify, and distribute this software under the terms and copyleft protections of the GPL-3.0 license.
