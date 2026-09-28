The biggest Auralis update so far: a smoother app from launch to lyrics, much better search, stats that follow your account, real offline caching, and a rebuilt Listen Together.

**Updating:** this project-signed APK can install over the project's 1.0.0 APK without clearing local data. The in-app updater checks GitHub Releases for newer versions.

## ✨ New

- **Your stats follow your account.** Listening stats can be backed up when you're signed in and restored after a reinstall or on a new phone when a backup is available. Clearing stats also clears the account copy.
- **Song cache.** Played audio is kept on your phone up to your "Max song cache size" (Settings › Storage). Fully cached songs can replay without internet; older cache entries are removed when the limit is reached.
- **Search: "Also matching".** An album and a song that share a name can appear in the Top result and **Also matching** cards, based on query relevance and available play counts. Results depend on the source catalog.
- **Remove from queue** in a queued song's ⋮ menu.
- **Discord profile.** Your Discord avatar shows in Discord Integration.
- **Mini-player designs:** Expanded, New and Classic, plus a Pure Black option.
- **Page transitions:** Settings and its pages slide in and out instead of snapping.
- **Home and Stats unfold** section by section when they open.
- **Player & Audio settings** page.

## 🚀 Smoother

- **Launch:** reduced blank flashing after the launch screen. The launch screen matches the app's colours, and Home artwork loads sooner.
- **Player:** smoother song changes, title/artist transitions and backgrounds; improved scrolling and blur performance.
- **Artwork** fades in instead of popping.
- **Volume:** a new wavy dial that turns as you drag it.
- **Speed dial:** faster pinning feedback.

## 🎤 Lyrics

- Word-by-word karaoke highlighting with smoother sweeps, plus a new word-synced lyrics provider.
- Improved highlight timing after tapping a lyric line.
- Improved music-break placement and countdowns for intros and longer breaks.
- Filters some stray "Artist - Title" header lines from lyric sources.
- Hindi/Urdu lyrics can be shown in Latin script when transliteration is available.
- More songs can get synced lyrics; remix matching and lyric-length checks were improved.

## 🔍 Search & albums

- Search uses title relevance and available play counts to rank results.
- Same-name albums and songs can be distinguished in search and opened separately.
- Spelling variants of Hindi titles (wada / vada) count as the same song.
- **View album** tries to identify a song's original release instead of a compilation. Identified standalone singles show **Single**.
- Album filtering reduces duplicate DJ remixes where the original is already present.

## 👥 Listen Together

- Separate host permissions for "control playback" and "play songs", with optional approval (Allow / Decline) for guests' picks.
- The host briefly holds new songs for guests to load, subject to a timeout, and coordinates seeks and skips.
- Hosts close rooms on normal exit, with automatic idle cleanup while the host app is running.
- Full queue sync (up to 100 songs), with live queue edits reaching guests.

## 📊 Stats

- Only real listening time counts; skips don't add a whole song.
- Play counts work (they could all show 0 before).
- Duplicate-upload matching reduces split counts for the same song.

## 🎧 Discord

- Rich Presence options update the activity shown on Discord.
- Seeking updates Discord right away.

## 🛠 Fixes

- Expanded mini-player shows its controls again.
- ⋮ menus are less glaring with dynamic colours.
- Shuffle stays on for new lists and after restarting the app.
- Playlist drag-to-reorder works again, without bouncing.
- The mini-player no longer blocks scrolling, and content no longer hides behind the bottom bar.
- Fixed a crash when scrolling large playlists.
- Fixed a Live Mesh background crash.

## ⚙️ New defaults (new installs)

Blur mini-player background, High audio quality, and a 5-second Discord update interval with the app logo as the small image. Settings you've already changed are kept.

---

**Download:** `Auralis-v1.1.0-universal.apk` supports Android 7.0+ devices using `arm64-v8a`, `armeabi-v7a`, `x86`, or `x86_64`.
