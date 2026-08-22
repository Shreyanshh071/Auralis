# Auralis 🎵

> A modern, fluid, and immersive music streaming experience built with React 19, TypeScript, Tailwind CSS, and Capacitor.

![Auralis Banner](https://raw.githubusercontent.com/shreyanshchoubey09/Auralis/main/public/banner.png)

---

## ✨ Features

- 🎧 **Rich Audio Experience**: Seamless streaming with full player controls, queue management, shuffle, repeat, and playback speed adjustment.
- 📜 **Synchronized Lyrics**: Real-time synchronized lyrics integration via LRCLIB with line-by-line active tracking and fallback support.
- 📊 **Dynamic Audio Visualizer**: Ambient audio waveform visualization synced to the active playback state.
- 🎨 **Adaptive Palette & Glassmorphism**: Dynamic color extraction that adapts the player UI theme to the current track's album art.
- 📱 **Cross-Platform**: Web, PWA, and native Android application powered by Capacitor (`com.auralis.music`).
- ⚡ **Speed Dial & Personalization**: Quick-access speed dial computed from real play counts, favorite tracks, and custom user playlists.
- ☁️ **Cloud Sync with Firebase**: User authentication (Google Sign-In & Email/Password) with real-time Firestore sync for playlists, favorites, and history.
- 🔍 **Honest Search & Discovery**: Fast, error-resilient search across millions of tracks with instant suggestion chips and genre discovery.

---

## 🛠️ Tech Stack

- **Frontend**: [React 19](https://react.dev/), [TypeScript](https://www.typescriptlang.org/), [Vite](https://vitejs.dev/)
- **Styling**: [Tailwind CSS v4](https://tailwindcss.com/), [Framer Motion](https://www.framer.com/motion/)
- **Icons**: [Lucide React](https://lucide.dev/)
- **Backend & Auth**: [Firebase](https://firebase.google.com/) (Auth, Cloud Firestore)
- **Mobile Container**: [Capacitor 8](https://capacitorjs.com/) (Android SDK 36, JDK 21)
- **Lyrics Provider**: [LRCLIB API](https://lrclib.net/)
- **Quality & Linting**: [Oxlint](https://oxc.rs/)

---

## 🚀 Getting Started

### Prerequisites

- **Node.js**: `v20+` or `v22+`
- **npm** or **pnpm**
- **Android Development** *(optional, for APK builds)*:
  - JDK 21
  - Android SDK (API 36)
  - Gradle 8.14+

### Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/shreyanshchoubey09/Auralis.git
   cd Auralis
   ```

2. **Install dependencies**:
   ```bash
   npm install
   ```

3. **Configure Environment Variables**:
   Copy the example `.env` file and populate your Firebase credentials:
   ```bash
   cp .env.example .env
   ```

4. **Start the development server**:
   ```bash
   npm run dev
   ```
   Open `http://localhost:5173` in your browser.

---

## 📱 Mobile Build (Android)

1. **Build web assets and sync Capacitor**:
   ```bash
   npm run cap:sync
   ```

2. **Open in Android Studio**:
   ```bash
   npm run cap:open:android
   ```

3. **Build Debug APK via CLI**:
   ```powershell
   cd android
   .\gradlew assembleDebug
   ```
   The generated APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.

---

## 🧪 Testing & Verification

Run the test suite to verify queue operations, storage persistence, and lyrics fuzzy matching:

```bash
npm run test
```

To run a production web build:

```bash
npm run build
```

---

## 📂 Project Architecture

```
Auralis/
├── android/               # Capacitor Android project (com.auralis.music)
├── docs/                  # Architectural audit, baseline fixes, & parity docs
│   ├── baseline-fixes.md  # Detailed defect and identity fixes
│   └── parity-audit.md    # Feature status and parity tracker
├── scripts/               # Build verification & unit test scripts
├── src/
│   ├── components/        # UI components (player, modals, views, visualizer)
│   ├── context/           # React Contexts (PlayerContext, AuthContext)
│   ├── lib/               # Utility modules (queueOps, queueStorage)
│   ├── services/          # Services (firebase, lyrics, youtube, artwork)
│   ├── types/             # TypeScript type definitions
│   ├── App.tsx            # Main application root
│   └── index.css          # Core design tokens and CSS variables
├── capacitor.config.json  # Capacitor mobile configuration
└── vite.config.ts         # Vite build configuration
```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
