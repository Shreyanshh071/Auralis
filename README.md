<div align="center">
  <img src="docs/logo-round.png" width="130" height="130" alt="Auralis Logo" />
  <h1>Auralis</h1>
  <p><b>Next-Generation Native Music Streaming & Collaborative Group Listening</b></p>

  ![GitHub release (latest by date)](https://img.shields.io/github/v/release/Shreyanshh071/Auralis?style=for-the-badge&color=8A2BE2)
  ![APK Size](https://img.shields.io/badge/APK%20Size-43%20MB%20(Universal)-32CD32?style=for-the-badge&logo=android&logoColor=white)
  ![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)
  ![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
  ![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
  ![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=for-the-badge)
  [![Buy Me A Chai](https://img.shields.io/badge/Buy%20Me%20A%20Chai-☕-orange?style=for-the-badge&logo=coffeescript&logoColor=white)](https://www.buymeachai.in/shreyanshh071)

  <br />

  **Auralis** is a modern, high-performance, ad-free native music streaming and collaborative listening experience crafted with **Jetpack Compose**, **AndroidX Media3**, **Kotlin Coroutines**, and **Material 3**.

  <br />

  [Website](https://auralis-self-nu.vercel.app) • [Download APK](https://github.com/Shreyanshh071/Auralis/releases/latest) • [Screenshots](#-screenshots) • [What's New](#-whats-new-in-110) • [Features](#-features) • [FAQ](#-frequently-asked-questions-faq) • [Tech Stack](#%EF%B8%8F-architecture--tech-stack) • [Sponsor](#-sponsor-this-project)

</div>

> [!WARNING]
> **Regional Restriction** — If YouTube Music is unavailable in your region, this app will not work without a VPN or proxy connecting to a supported region.

---

## 🆕 What's New in 1.1.0

The biggest update so far. [Full release notes →](https://github.com/Shreyanshh071/Auralis/releases/tag/v1.1.0)

- **Stats that follow your account** — listening stats are backed up when you're signed in and come back after a reinstall or on a new phone.
- **Offline song cache** — songs you play are kept on your phone (you choose the size), so replays start instantly and work without internet.
- **Smarter search** — when an album and a song share a name, the more-played one is the Top result and the other shows right below under **Also matching**.
- **Rebuilt Listen Together** — hosts decide who can play, skip or seek; everyone starts each song together; optional Allow / Decline for guests' picks.
- **Word-by-word lyrics** — smoother karaoke highlighting, Hinglish for Indian songs, and no more highlight running ahead after you tap a line.
- **Smoother everywhere** — no blank flash on launch, Home opens with artwork ready, sections unfold into place, and a new wavy volume dial.
- **Remove from queue**, a **Discord profile** in Discord Integration, and dozens of fixes.

### 📦 One universal APK (~43 MB)
There's one APK and it runs on every Android phone, whatever processor it has (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`). Most of its size is Discord's official SDK for Rich Presence, which includes native code for each of those processor types. The app itself is native Jetpack Compose, shrunk with R8.

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
- **Synchronized Playback Rooms**: Join with a 6-character code and listen together in real time. The host holds each new song until everyone has it loaded, so all of you start together.
- **Host Permissions**: Choose whether guests may control playback (play, pause, seek) and whether they may play or skip songs, with optional Allow / Decline approval for their picks.
- **Dedicated Host Controls & Listener Protection**: Prevents accidental desync by blocking listener playback alterations from Bluetooth earphones, TWS touch gestures, or lockscreen controls while keeping volume independent.
- **Smart Song Recommendations & Voting**: Room members can search, recommend, and upvote songs in a shared room queue.
- **Real-Time Member Presence & Pill Alerts**: Instant floating animated notifications when friends join, leave, or disconnect.

### 🎨 Visual Excellence & Modern Aesthetics
- **Dynamic Blurred Artwork Player**: Fluid multi-layer ambient background that adapts seamlessly to the playing song's artwork palette.
- **Glassmorphic Floating Sheets & Controls**: Ultra-premium Frosted Glass / Haze effect across player sheets, dialogs, and popups.
- **Fluid Spring Animations & 120 FPS Scrolling**: Zero-lag scrolling performance with optimized artwork caching and lightweight list item bindings.
- **Verified Studio Artworks & Portrait Fallback**: High-resolution studio album covers (`=w1200-h1200`) and automatic Wikipedia portrait resolution for artists with blank avatars (e.g. Kanye West).

### 📜 Multi-Engine Synced Lyrics Ecosystem
- **Word-by-Word Karaoke Lyrics**: Lyrics light up word by word as the song plays; Hindi/Urdu songs always show in Hinglish (Latin letters).
- **Multi-Source Lyrics**: Line-by-line and word-by-word synced lyrics from **Musixmatch** (Spotify catalog), **LRCLIB**, **KuGou** (200M+ synchronized catalog), **AMLL**, and official **YouTube Music** record-label lyrics.
- **AI Translation & Romanization**: One-tap AI translation to English and Pinyin/Romaji/Hangul transliteration for foreign language tracks.
- **Spotify-Style Lyric Card Sharing**: Generate and export customizable aesthetic lyric cards directly to social media.
- **Offline Caching**: Automatically saves fetched synchronized lyrics for instant offline access.

### 🎧 Audiophile-Grade Playback Engine
- **Uninterrupted Background Streaming**: Rock-solid playback with `PARTIAL_WAKE_LOCK`, `WifiLock`, and native foreground `MediaSessionService` that never sleeps.
- **Android 13/14 Quick Settings & Lock-Screen Deck**: Native system media card featuring monochrome app badge, interactive scrub seekbar, previous/next controls, like/heart toggle, and repeat modes.
- **Offline Song Cache**: Songs you play are kept on your phone up to a size you choose, so replays start instantly and work offline.
- **Offline Downloads & Local Library**: Download songs and whole playlists for offline playback, with Auto / High / Low audio quality (High is Opus up to ~160 kbps).
- **Gapless Playback & Spatial Audio**: The next song is preloaded for gapless transitions, and a Spatial Audio switch widens the soundstage.

### 🔍 Discovery, Search & Music Recognition
- **Instant Search & Autocomplete**: Fast suggestions across songs, albums, artists and playlists.
- **Ranked by Plays**: Exact titles first, then everything by popularity. An album and a song with the same name are weighed against each other and the runner-up appears under **Also matching**.
- **Right Album, Every Time**: "View album" opens a song's original album, not a greatest-hits compilation, and standalone singles are labelled as singles.
- **Music & Voice Recognition**: Identify songs playing around you using built-in acoustic fingerprinting (Shazam / ACRCloud / SongRec).
- **Taste Profiler & Speed Dial**: Personalized home feed tailored to your real listening habits, heavy rotation, and top-played artists.
- **Full Artist Discography**: Artist bios, monthly listener counts, top tracks, albums, singles, and related artist graphs.

### ☁️ Cloud Sync & Playlist Importer
- **One-Click Playlist Import**: Effortlessly import playlists from Spotify and YouTube directly into your library.
- **Google Account & Firebase Sync**: Back up your liked songs, playlists, saved artists and listening stats; sign in on a new phone and they all come back.

### 📊 Listening Stats
- **Real Listening Time**: Only what you actually heard counts; skips don't add a whole song.
- **Top Songs & Artists**: Weekly, monthly and yearly views, with one song counted once even when it's uploaded several times on YouTube.

### 🎮 Discord Rich Presence
- **Show What You're Playing**: Song, artist, album art and progress on your Discord profile via Discord's official Social SDK.
- **Fully Customizable**: Activity name, details, images, status and update interval; seeking updates Discord right away.

### 🔄 In-App Direct OTA Updater
- **Instant Update Notifications**: Checks GitHub Releases automatically and notifies you of new versions.
- **In-App Background Download & Install**: Download APK updates with a live progress bar and install seamlessly with one tap.

---

## 🛠️ Architecture & Tech Stack

| Layer | Technologies |
| :--- | :--- |
| **UI & Presentation** | [Jetpack Compose](https://developer.android.com/jetpack/compose), [Material 3](https://m3.material.io/), [Haze Blur](https://github.com/chrisbanes/haze), [Coil 2.7](https://coil-kt.github.io/coil/) |
| **Audio Engine** | [AndroidX Media3](https://developer.android.com/media/media3) (`ExoPlayer`, `MediaSessionService`, `ForwardingPlayer`), `AudioTrack` |
| **Concurrency & Reactive** | [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html), [StateFlow & SharedFlow](https://developer.android.com/kotlin/flow) |
| **Local Persistence** | [Room Database](https://developer.android.com/training/data-storage/room) (`AuralisDatabase`), [AndroidX DataStore](https://developer.android.com/topic/libraries/architecture/datastore) |
| **Networking & Extraction** | [OkHttp 4](https://square.github.io/okhttp/), Custom InnerTube Web Client, NewPipe Extractor |
| **Backend & Sync** | [Firebase Auth](https://firebase.google.com/products/auth), [Cloud Firestore](https://firebase.google.com/products/firestore), Google Sign-In |
| **Discord** | [Discord Social SDK](https://discord.com/developers/docs/discord-social-sdk/overview) (Rich Presence) |
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
<summary><b>Why is the APK about 43 MB?</b></summary>
<br>

The app itself is small: native **Jetpack Compose** and **AndroidX Media3**, shrunk with **R8**. Most of the download is Discord's official SDK for Rich Presence, which ships native code for every kind of Android processor. That's what lets a single universal APK install on any phone.
</details>

<details>
<summary><b>Which APK should I download? Do I need to know my phone's CPU architecture?</b></summary>
<br>

**You do NOT need to check your phone's processor!** Simply download `Auralis-v1.1.0-universal.apk` from the [latest release](https://github.com/Shreyanshh071/Auralis/releases/latest). It is a single, universal build that automatically supports all Android CPU architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) out of the box.
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
