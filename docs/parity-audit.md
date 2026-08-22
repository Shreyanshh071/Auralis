# Auralis Functional Parity Audit

**Date:** 2026-08-21
**Baseline commit:** `02692f2`
**Scope:** functional parity only. No UI redesign.

## Method and legal position

This audit was produced **clean-room**. No third-party reference-app source code, asset, string, or layout was read, fetched, or cloned. The feature inventory below was supplied as a written list of capabilities; every status verdict is derived solely from reading the Auralis codebase. The reference app is GPL-3.0 and nothing from it may be copied into this project.

**Status is assigned from the underlying operation, never from the presence of a button or screen.** A rendered control whose value is never consumed is recorded as *Missing*, not *Partial*.

| Status | Meaning |
|---|---|
| **Verified** | Real operation traced end to end in code and reaches a real effect. |
| **Partial** | Works, but incomplete, unreliable, or broken on one platform. |
| **Missing** | No implementation, or implementation exists but is inert. |
| **Blocked** | Cannot be implemented as currently architected, or is barred by platform/licence terms. |

---

## Parity table

| Feature | Status | Files / services involved | Implementation plan | Test required | Platform / API limitation |
|---|---|---|---|---|---|
| Music/video streaming | **Partial (prod endpoint) / Verified (fallback pool)** | `PlayerContext.tsx:366-448` (hidden `YT.Player`), `services/youtube.ts`, `vite.config.ts:5-92`, `scripts/test-search-reliability.mjs` | Multi-instance fallback pool (9 Piped + Invidious endpoints), concurrent 3-way batch racing, per-query 4.5s timeouts | Search + play across web and Android | Dev middleware (`/api/youtube-search`) is fast path under `vite dev`; production uses the hardened fallback pool. Hardened 2026-08-22 with 6 reliability unit tests |
| Background playback | **Missing** | none. `AndroidManifest.xml` (INTERNET only, zero `<service>`), no `navigator.mediaSession` anywhere | Requires native media pipeline + foreground service + MediaSession; not reachable from a hidden cross-origin iframe | Backgrounded playback + lockscreen controls on a real device | WebView JS timers are throttled/suspended when backgrounded. `targetSdk 36` requires typed `foregroundServiceType` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, none declared. Backgrounding a YouTube embed likely breaches YouTube terms — **confirm before building** |
| Offline download and cache | **Missing** (honest placeholder) | `LibraryView.tsx:150-162` — non-interactive tile, explicit "Coming soon", no `onClick` | Do not implement against YouTube streams. Only viable path is user-supplied/licensed audio | n/a until a lawful source exists | Extracting or storing YouTube media streams is not a permitted use. Placeholder is correctly inert — leave it inert |
| Skip silence | **Missing** | none | Needs RMS analysis over an `AnalyserNode`; impossible on current pipeline | n/a | **Blocked by architecture:** audio lives in a cross-origin YouTube iframe, so no `MediaElementAudioSourceNode` can be attached. Requires replacing the player entirely |
| Sleep timer | **Verified** | `PlayerContext.tsx` (`sleepDeadline` state + deadline-checking countdown effect + `setSleepTimer`); UI `NowPlayingModal.tsx` popover; countdown `Sidebar.tsx` | For true firing while fully backgrounded on Android, a native alarm/foreground service would be required | Set timer, reload, and background the app; confirm it still fires and really pauses | **Fixed 2026-08-22:** the timer is now keyed to an **absolute deadline** persisted under `auralis_sleep_deadline`, re-checked on every 1 s tick **and** on `visibilitychange`. This survives a reload and no longer drifts when the interval is throttled in the background. Inherent remaining limit: a WebView whose JS is fully suspended cannot fire until the next foreground tick — unavoidable without a native alarm |
| Audio normalization | **Missing** | none | Needs a gain stage in a Web Audio graph, plus a loudness source | n/a | **Blocked by architecture** — same cross-origin iframe limitation as Skip silence |
| Tempo and pitch control | **Verified (tempo)** / **Blocked (pitch)** | `PlayerContext.tsx` (`PLAYBACK_RATES`, `playbackRate` state, `setPlaybackRate`, re-applied on each `onStateChange` PLAYING event); UI speed pill `NowPlayingModal.tsx` (`cyclePlaybackRate`) | Independent pitch shift is out of scope on this pipeline | Change rate; confirm audible tempo change, that it re-applies after a track change, and that it persists across reload | **Fixed 2026-08-22:** tempo is wired to the IFrame `setPlaybackRate` and persisted under `auralis_playback_rate`. Because loading a new video resets the rate to 1, it is re-applied whenever playback begins. Speeds are clamped to a set the player always supports, so the control cannot desync. Changing tempo also shifts pitch — accepted. **Independent pitch shift remains blocked** — needs a Web Audio graph the cross-origin iframe cannot provide |
| Equalizer | **Missing** | none | Needs a `BiquadFilterNode` chain (web) or native `AudioEffect` (Android) | n/a | **Blocked by architecture** — cross-origin iframe audio cannot be filtered |
| Live synchronized lyrics | **Partial** | `services/lyrics.ts`, `components/lyrics/SyncedLyrics.tsx`, sync loop `PlayerContext.tsx:472-487` | Add a genuine word-timed provider; fix `cleanTitle`; reject variant matches instead of normalising them | richsync / line-sync / plain / no-lyrics / deliberately mismatched song | Sync-type labelling is **honest** (`hasWordTiming`, `lyrics.ts:40-103`) but LRCLIB returns line-level only, so `syncType: 'richsync'` is effectively unreachable and the word-by-word path is dead. `cleanTitle:20` truncates every title at the first hyphen. "sped up"/"slowed" are stripped from the query rather than rejected, so wrong-tempo lyrics can match. Depends on third-party CORS proxies |
| Lyrics translation | **Verified** | `services/lyricsTranslation.ts`, `components/lyrics/SyncedLyrics.tsx`, `scripts/test-lyrics-translation.mjs` (7 tests) | Multi-provider fallback (MyMemory + Lingva), per-line cache, non-translatable filter, bilingual scroll & cinema mode | Translate timed and plain lyrics into 12 languages | **Implemented 2026-08-22.** Supports bilingual timed subtitles in cinema & scroll modes, plain mode translation, language dropdown with 12 languages, and per-line caching. Fails honestly without faking |
| Personalized quick picks | **Partial** | Real: `PlayerContext.tsx:33-85` (`playCounts`, `getTopTracks`, `getTopArtists`) → `HomeView.tsx:98-103`, empty state `:235-253`. Fake: `HomeView.tsx:50,67` | Delete the `CURATED_TRACKS` seed; let Quick picks be genuinely empty for a new user, as Speed Dial already is | New user sees no fabricated content; recommendations shift after real plays | **Speed Dial is genuinely real and has no hardcoded fallback.** Quick picks initialises to 16 hardcoded songs and, once playback starts, renders them under a "Recommended for you" badge (`:359-364`). Top-artist avatars are song thumbnails, not artist images |
| Search songs, albums, artists, videos, playlists | **Verified (typing & resilience)** | `types/music.ts` (`Artist`/`PlaylistResult`/`SearchResults`), `services/youtube.ts` (`searchAll`, `parsePipedSearchItem`/`parseInvidiousSearchItem`, `bucketItems`, `formatCount`, `raceProviderBatch`), `vite.config.ts`, UI `ExploreView.tsx`, tests `scripts/test-search-parsing.mjs`, `scripts/test-search-reliability.mjs` | Complete with 9-instance fallback pool + fast-first batch racing | Covered by 21 search unit tests | **Implemented & verified 2026-08-22.** `searchAll` returns typed `{songs, artists, playlists}`; `ExploreView` renders typed sections; `raceProviderBatch` queries 3 fallback instances concurrently with 4.5s timeouts |
| Library management | **Verified** | `LibraryView.tsx`, `FavoritesView.tsx`, `ExploreView.tsx`, `PlayerContext.tsx`, `lib/queueStorage.ts`, `scripts/test-queue-storage.mjs` | First-class Saved Artists and Saved Albums entities | Add/remove across all entity types, verified after reload | **Implemented 2026-08-22.** Saved artists & albums are stored under `auralis_saved_artists` & `auralis_saved_albums`. Explore cards support +Follow / Bookmark; Library offers All, Playlists, Artists, and Albums filter tabs with click-to-search and click-to-play |
| Local playlists | **Verified** | `PlayerContext.tsx` (`createPlaylist`/`addToPlaylist`/`removeFromPlaylist`/`reorderPlaylist`/`deletePlaylist`), `lib/queueStorage.ts`, `modals/AddToPlaylistButton.tsx`, `LibraryView.tsx`, `Sidebar.tsx` | Complete: create / add / remove / delete / reorder are all implemented | Create → add → reload → remove → delete: covered by `scripts/test-queue-storage.mjs` for the storage rules, manual click-through for the state updates | **Fixed 2026-08-22.** `addToPlaylist` now has a real caller (`AddToPlaylistButton`, present on Home, Explore, Liked, Library rows and Now Playing); `createPlaylist` sets `isCustom` and accepts initial tracks so create-and-add is one step; stored playlists are normalised to `isCustom: true` on read, so ones created before the fix became deletable; the Library FAB creates a playlist, so creation works on a phone; the sidebar opens the playlist that was clicked |
| Playlist import | **Verified** | `services/youtubeImporter.ts`, UI `LibraryView.tsx:281-345` | Reduce reliance on third-party instances | Import a public playlist; confirm a bad URL shows a real error | Genuinely works and **fails honestly** (`youtubeImporter.ts:104-105` returns `null`; the view renders a real error at `:316-321`). Depends entirely on public Piped/Invidious uptime. Correctly scoped to public playlists only |
| Reorder songs in playlists and queue | **Verified** | `lib/queueOps.ts` (`moveItem` / `reorderQueue` / `mapIndexAfterMove` / `removeAt`, covered by `scripts/test-queue-ops.mjs`, 10 cases); wired in `PlayerContext.tsx` (`reorderQueue`, `reorderPlaylist`); UI move up/down in `NowPlayingModal.tsx` (queue rows) and `LibraryView.tsx` (custom playlists) | Optional: layer drag-and-drop on top of the same move operations later | Reorder both, reload, confirm order survives | **Fixed 2026-08-22.** Queue order persists via `auralis_queue`, and the playing index is carried through each move (`mapIndexAfterMove`) so next/prev stay correct; the same helper backs `removeFromQueue`, fixing the earlier index-desync bug. Playlist order persists via `auralis_playlists` and is gated to user (`isCustom`) playlists — derived views (Liked / Recent / Top) are intentionally not reorderable |
| Account login | **Partial** | `AuthContext.tsx:44-51` (`signInWithPopup`), `services/firebase.ts`, `capacitor.config.json` | Install and use a native Google auth plugin, or switch to a redirect flow | Sign in / out / restore session on **Android**, not just web | `signInWithPopup` is unreliable-to-broken inside an Android WebView. `capacitor.config.json` configures a `GoogleAuth` plugin that **is not installed** (`capacitor.plugins.json` is `[]`) — that config block is inert. Same for `SplashScreen` |
| Sync songs, artists, albums, likes, playlists, history, settings | **Partial** (data-loss bug) | `AuthContext.tsx:62-112`, `PlayerContext.tsx:283-343` | Per-entity documents, deterministic IDs, `updatedAt` versioning, tombstones for deletes, explicit conflict resolution, real Firestore rules | Two clients, offline edits on both, reconnect, confirm no silent loss | Only favorites + playlists sync; history, settings, play counts, and queue do not. Whole arrays are overwritten on a single `users/{uid}` doc, last-write-wins, no version field. **`PlayerContext.tsx:315-320` fires on sign-in with the local value, so a fresh device with empty favorites can overwrite cloud favorites with `[]`, racing the fetch at `:283-312`.** Merge at `:291-296` lets a stale local copy resurrect a deletion. **No `firestore.rules` exists in the repo** |
| Listen together in real time | **Missing** | none | Rooms + participants over a real-time backend; host-authoritative transport state broadcast with server timestamps; drift correction on the participant side | Two authenticated clients: create, join by code, host seek, late join, host disconnect, bad code | Firebase is already a dependency, so Realtime Database is the natural fit. Must never render a room UI without a live connection |
| Theme modes, dynamic colors, saved palettes | **Verified** | `src/index.css` (semantic tokens for :root and .dark), `index.html` (zero-flash script), `PlayerContext.tsx` (`theme`, `effectiveTheme`, `setTheme`), `types/music.ts` (`ThemeMode`), `components/common/Header.tsx` (Theme toggle popup), `scripts/test-theme-system.mjs` (9 unit tests) | Semantic CSS variables across all views/components, instant persistence, OS color-scheme detection, light/dark/system mode switching | Full light, dark, and system mode testing across all views, modals, players, and lyric views | **Fixed 2026-08-22:** Full semantic token system implemented across all components (`:root` light and `.dark` palettes), zero-flash `<head>` script, persistent `auralis_theme` storage, real-time `prefers-color-scheme` listener, and theme selector in Header (Moon/Sun/Monitor). 9 unit tests pass |
| Android widget | **Missing** | none — no `appwidget-provider`, no widget layout, no `<receiver>` | Needs a native widget bound to a real media session | Widget reflects state and controls playback | Depends on native background playback existing first. Note `res/xml/config.xml` has a Cordova `<widget>` root element — that is **not** an Android widget |
| Music recognition | **Missing** | none | Mic capture + a fingerprinting service | Recognise a track from ambient audio | No free/open fingerprinting API is known to be suitable; commercial services (paid) are the realistic option. Requires mic permission and a native plugin. Recommend deferring |

---

## Defects that block parity work

These were found during the audit and should be fixed before feature work, in this order.

**1. A foreign application identity is still embedded in the Android project — legal and build-breaking.**
`android/app/src/main/res/values/strings.xml` declares `app_name` and `title_activity_main` as **a foreign app name**, and `package_name` / `custom_url_scheme` under a foreign package. `MainActivity.java` sits in that foreign package directory and declares it, while `build.gradle` sets namespace and `applicationId` to `com.auralis.music`. The installed app would carry **the foreign name**, and the manifest's `.MainActivity` cannot resolve against the namespace, so the class named as the launcher activity does not exist. This directly contradicts the project's branding and licence rules and must be corrected first.

**2. Secrets are committed.** A Firebase API key is hardcoded as a fallback in `services/firebase.ts:6`, and `android/app/google-services.json` is tracked in git.

**3. No Firestore security rules exist in the repository.** Any sync work is unsafe until rules are written and reviewed.

**4. `CURATED_TRACKS` is a silent fake-data path.** `services/youtube.ts:178,264` return 16 hardcoded songs for an empty query and for **every** failure, and all network paths are swallowed by bare `catch {}` (`:197,:255`). No caller can observe a search error or an empty result, so every "no results" and error state in the UI is unreachable, and — because the primary backend does not exist in the Android build — hardcoded data is the *likely* production path rather than an edge case.

**5. `vite.config.ts:19-36` posts to YouTube's private InnerTube endpoint with a spoofed desktop User-Agent and a forged client context.** This is a terms-of-service risk and should not be carried into production.

**6. Inert settings — resolved (2026-08-22).** `playbackRate` is now real first-class player state, wired to the IFrame `setPlaybackRate` with a visible control and persistence. `audioQuality`, `ambientVisuals`, and `karaokeSweep` were removed from `PlayerSettings` and its defaults — each was declared, defaulted, and persisted but read by nothing and exposed by no control, and `audioQuality` is unimplementable on a cross-origin iframe (the IFrame quality API is deprecated), so any control would have been fake. `settings.volume` / `settings.isMuted` remain shadowed by the separate real `volume`/`isMuted` state — harmless duplication, left as the canonical settings mirror. There is still **no settings screen anywhere** outside the lyrics popover.

**7. Header search — resolved.** The header hands the typed query through `onSubmitSearch` (`Header.tsx`) to `App.tsx` (`handleSelectGenre` → `exploreRequest {query, nonce}`), and `ExploreView` runs it via `initialQuery` / `queryNonce`. The nonce lets the same query be resubmitted and still re-run. Verified end to end in the current source; the control is genuinely functional.

**8. `AudioVisualizer.tsx:39-42` generates a pseudo-waveform from `Math.sin`/`Math.cos` and captions itself "Synchronized to active audio stream" (`:85`).** The animation is acceptable as decoration; the caption is a false claim and should be reworded.

**9. Mobile Now-Playing tabs render two panels at once.** `NowPlayingModal.tsx:388,390` OR together `mobileTab` and `activeModalTab`, so with the default `activeModalTab = 'lyrics'` the queue and lyrics panels render simultaneously.

**10. `Sidebar.tsx:149-152` renders a green pulsing "Auralis Cloud Ready" indicator unconditionally.** `Sidebar` does not import `useAuth`; the indicator shows identically when signed out, offline, or misconfigured. `Header.tsx:318-321` likewise renders a static "Cloud Sync Active" that never reflects `isSyncing` or failure.

---

## Correction to the stated baseline

Two items previously assumed working are not:

- **Playback-rate settings** — the value exists but is never sent to the player and has no UI control. Recorded as *Missing*, not partial.
- **Firestore sync** — present but carries the fresh-sign-in overwrite race described above, so it is not safe to build on as-is.

Conversely, three things are better than assumed: the **Speed Dial** is real play-count data with no hardcoded fallback; **playlist import** fails honestly; and the **lyrics sync-type labelling** is already rigorous about not faking word timings.

---

## Items requiring confirmation against live documentation

Web access was unavailable in this session, so the following were **not** verified and are recorded as open questions rather than findings. None should be treated as settled, and no feature above was marked *Blocked* on the strength of these alone — architectural blocks were established from the code.

1. Which account-library operations the YouTube Data API v3 officially supports with user consent (own playlists, liked videos, subscriptions), current quota costs, and whether watch-history access remains unavailable.
2. Whether any official YouTube Music library API exists. Working assumption: it does not.
3. The exact YouTube terms clauses governing stream extraction, offline caching, audio-only playback, and background playback of embeds.
4. Which lyrics providers genuinely return word- or syllable-level timings, and their licensing and auth requirements.

**A standing rule for account features:** Firebase Google sign-in is the Auralis account system and must never be described as YouTube Music sync. `LibraryView.tsx:339-345` already states this limitation accurately to the user and that wording should be preserved.
