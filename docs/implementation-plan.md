# Auralis Implementation Plan

**Created:** 2026-08-22
**Author:** engineering audit + continuation session
**Basis:** reconstructed from git history (`git log`), the current working tree,
and a file-by-file reading of source. Where this document and the older
`docs/parity-audit.md` disagree, **the source code wins** — several audit rows
and its entire numbered "Defects" section are stale (see §2).

---

## 1. Project state reconstruction

Git history (4 commits, working tree clean, pushed to `origin/main`):

| Commit | Meaning |
|---|---|
| `17a193f` | Initial commit — full app scaffold (React 19 + Vite + Capacitor), imported under a foreign vendor's Android package identity (since replaced). |
| `02692f2` | Firebase Google auth + Firestore sync added (web + Android). |
| `5919d98` | **The big one.** Baseline fixes, Android identity → `com.auralis.music`, queue/sync rework, docs, verification tooling. This commit resolved nearly every defect the audit lists as open. |
| `3c594f6` | README rewrite. |

**What previous sessions actually completed (verified in source this session):**

- **Android identity / foreign-vendor purge — DONE.** A case-insensitive repo
  grep for the old vendor name returns nothing. `MainActivity.java` lives at `android/app/src/main/java/com/auralis/music/`,
  `strings.xml` is all-Auralis / `com.auralis.music`.
- **Honest search — DONE.** `services/youtube.ts` throws `SearchUnavailableError`
  on total provider failure and returns `[]` for a genuine empty result. The old
  `CURATED_TRACKS` fake-data path is gone; the remaining `DEMO_TRACKS` is
  explicitly barred from `searchYouTube()` and recommendations.
- **No fake recommendations — DONE.** `HomeView` builds Quick Picks from real
  `searchYouTube` results with real error/empty states; Speed Dial is real
  play-count data. No hardcoded seed content.
- **Firebase config hygiene — DONE.** `services/firebase.ts` reads only from env,
  has no hardcoded key, validates the Web app ID (rejects Android/iOS IDs), and
  exposes `isFirebaseConfigured` / `firebaseConfigError`.
- **Firestore security rules — DONE.** `firestore.rules` exists: owner-only,
  size caps, shape validation, default-deny.
- **Lyrics matching — DONE.** `cleanTitle` no longer truncates at the first
  hyphen; adds `detectRendition`, `isRelatedMatch`, duration tolerance, and honest
  sync-type downgrade for tempo-altered / alternate versions. 15 unit tests pass.
- **Cloud sync data-loss race — FIXED.** `PlayerContext` gates cloud writes
  behind a `hydratedUid` that only opens after a successful read + merge, stays
  shut on read failure, and never drops local or cloud entries. `AuthContext`
  adds `CloudReadError` and per-field `favoritesUpdatedAt` / `playlistsUpdatedAt`
  / `schemaVersion` writes with `{ merge: true }`.
- **Sync-status honesty — FIXED.** `Sidebar` and `Header` both consume `useAuth`
  and render real state (`isAuthAvailable` / `isSyncing` / `authError` /
  `lastSyncedAt`); the old unconditional "Cloud Ready" / "Cloud Sync Active"
  indicators are gone. The account menu states plainly it is *not* YouTube Music
  sync.
- **Sleep timer, tempo control, local playlists (create/add/remove/delete/reorder),
  playlist import, queue + current-track restoration, reorder queue/playlist,
  mobile Liked navigation — DONE** (per audit table "Fixed 2026-08-22" notes,
  consistent with the code and covered by `scripts/test-queue-*.mjs`).

**Build:** verified green on Windows on 2026-08-22 (`scripts/verify-build.ps1`,
16/16, APK 4.17 MB, `BUILD SUCCESSFUL`). Toolchain: JDK 21, Android SDK 36,
Gradle 8.14.3. `npm run test` = 15 passing this session.

**The one human-only blocker:** `VITE_FIREBASE_API_KEY` / `VITE_FIREBASE_APP_ID`
in `.env` are intentionally blank and must be filled from a Firebase **Web** app
config. Until then, Firebase is unconfigured → sign-in is disabled and any
cloud/Listen-Together feature cannot be verified live. The app is fully
functional without an account.

---

## 2. Corrections to `docs/parity-audit.md`

The audit's numbered "Defects that block parity work" section (#1–#5) is **stale**:
identity (#1), committed key fallback (#2), missing Firestore rules (#3),
`CURATED_TRACKS` fake data (#4) are all resolved; #6–#10 were already marked
resolved. Several table rows also under-state reality: **lyrics** `cleanTitle`
and variant rejection are done; **quick picks** no longer seed fake data;
**sync** no longer carries the data-loss race. Only one committed-file nit
remains: `android/app/google-services.json` is tracked (it holds public Android
identifiers, restricted by SHA — low risk, left as-is for the Android build).

---

## 3. Current feature status

Legend: **DONE** (real, verified) · **PARTIAL** (works but incomplete) ·
**BLOCKED** (architecture/policy/needs-live-Firebase) · **NOT STARTED**.

### DONE
Foreign-vendor purge · honest search errors · honest recommendations · Firebase
config hygiene · Firestore rules · lyrics matching/labelling · sleep timer ·
tempo control · local playlists (full CRUD + reorder) · playlist import ·
queue/current-track restoration · cloud sync safety (data-loss race fixed) ·
sync-status honesty · **typed search & discovery** (songs/artists/playlists) ·
**search reliability hardening** (9-instance pool, batch racing, timeouts) ·
**lyrics translation** (multi-provider, per-line caching, bilingual scroll & cinema) ·
**library artist & album entities** (follow/bookmark, library tabs & CRUD) ·
**theme modes (light/dark/system)** (semantic tokens, zero-flash script, system sync) ·
**Google authentication** (web `signInWithPopup`; native `@capacitor-firebase/authentication`
→ `signInWithCredential`, single JS SDK source of truth; unified sign-out; native
persistence; cancellation handling). *Android code + APK build complete; native runtime
awaits a console SHA-1 step — see below.*

### BLOCKED — do not fake (architecture: cross-origin YouTube IFrame)
Equalizer · audio normalization · skip-silence · independent pitch shift ·
*real* spectrum visualizer (current one is honestly decorative). None can attach
a `MediaElementAudioSourceNode` to cross-origin iframe audio.

### BLOCKED — policy / platform
Background playback (WebView JS throttling + `targetSdk 36` foreground-service
requirements + YouTube ToS) · offline download/cache (YouTube ToS) · Android
widget (depends on native background playback existing first) · music recognition
(no free/authorized fingerprinting API).

### BLOCKED — needs a human-only console/creds step to verify live
- **Android native Google auth runtime** — *code + APK build complete* (web
  sign-in verified). The native flow cannot be exercised here because
  `google-services.json` carries only the web OAuth client (`client_type 3`);
  native Google sign-in requires an **Android** OAuth client (`client_type 1`)
  registered with the app's SHA-1 in the Firebase console, then a re-downloaded
  `google-services.json`. No emulator/device with Play Services was available in
  this environment. Debug SHA-1 to register:
  `85:80:EB:7F:A1:B4:22:AE:08:62:01:07:9A:09:DD:EB:EA:AA:E5:80`.
- **Listen Together in real time** — needs a live Realtime DB connection; UI must
  never render a room without one. *Explicitly out of scope this session.*

---

## 4. Recommended implementation order

1. ~~**Typed search & discovery**~~ — **DONE (2026-08-22).**
2. ~~**Search reliability hardening**~~ — **DONE (2026-08-22).**
3. ~~**Lyrics translation**~~ — **DONE (2026-08-22).**
4. ~~**Library artist & album entities**~~ — **DONE (2026-08-22).**
5. ~~**Theme modes (light/dark/system)**~~ — **DONE (2026-08-22).**
6. ~~**Android native Google auth**~~ — **DONE (2026-08-22)** (code + APK build;
   native runtime awaits the console SHA-1 step — see §3 and §11).
7. **Listen Together in real time** — next up; needs a live Realtime DB connection.
   *Not started (explicitly out of scope this session).*

---

## 5. Shipped Task: Typed Search & Discovery ✅ DONE (2026-08-22)

- `types/music.ts`: `Artist`, `PlaylistResult`, `SearchResults`.
- `services/youtube.ts`: `searchAll(query)` with typed parsers.
- `vite.config.ts`: dev middleware emits songs, artists, and playlists.
- `ExploreView.tsx`: 3 typed sections (artist re-search, playlist import-play).
- `scripts/test-search-parsing.mjs`: 15 unit tests.

---

## 6. Shipped Task: Search Reliability Hardening ✅ DONE (2026-08-22)

- `services/youtube.ts`: Added `PUBLIC_SEARCH_PROVIDERS` fallback pool with 9 healthy Piped and Invidious instances.
- Added `queryProvider` with `AbortSignal.timeout(4500)`.
- Added `raceProviderBatch` concurrent fast-first resolution (races 3 instances in parallel).
- `services/youtubeImporter.ts`: Added 5s per-instance timeouts and fallback endpoints.
- `scripts/test-search-reliability.mjs`: 6 unit tests covering fallback pool, error propagation, racing, and empty results.

---

## 7. Shipped Task: Lyrics Translation ✅ DONE (2026-08-22)

- `services/lyricsTranslation.ts`: Built multi-provider translation service (`translateText`, `translateLyricLines`, `translatePlainLyrics`) with MyMemory + Lingva fallback, HTML entity decoding, non-translatable filter, and per-line in-memory caching.
- `types/music.ts`: Added `translatedText` to `LyricLine`, and `translatedPlainLyrics` / `translatedLanguage` to `LyricsData`.
- `components/lyrics/SyncedLyrics.tsx`:
  - Added translation toggle button and target language dropdown (12 supported languages).
  - Bilingual subtitle rendering in **Cinema Mode** (`activeLine.translatedText` and `nextLine.translatedText`).
  - Bilingual line rendering in **Scroll Mode** beneath each timed line.
  - Translated plain text rendering in **Plain Mode**.
- `scripts/test-lyrics-translation.mjs`: 7 unit tests covering language options, caching, entity decoding, and timing preservation.

---

## 8. Shipped Task: Library Artist & Album Entities ✅ DONE (2026-08-22)

- `types/music.ts`: Defined `SavedArtist` and `SavedAlbum` interfaces.
- `lib/queueStorage.ts`: Added validation (`isSavedArtistLike`, `isSavedAlbumLike`), parser (`parseStoredArtists`, `parseStoredAlbums`), and storage methods (`loadStoredArtists`, `saveStoredArtists`, `loadStoredAlbums`, `saveStoredAlbums`) under keys `auralis_saved_artists` and `auralis_saved_albums`.
- `context/PlayerContext.tsx`: Wired `savedArtists`, `saveArtist`, `removeArtist`, `isArtistSaved`, `savedAlbums`, `saveAlbum`, `removeAlbum`, `isAlbumSaved` with persistence and feedback toasts.
- `components/views/ExploreView.tsx`: Added `+ Follow` / `Following` buttons on artist cards and `Bookmark` / `Saved` buttons on playlist/album cards.
- `components/views/LibraryView.tsx`:
  - Added filter tabs: `All`, `Playlists (N)`, `Artists (N)`, `Albums (N)`.
  - Added Followed Artists section with initial fallback avatars, search on click, and unfollow action.
  - Added Saved Albums section with album art, click-to-play with loading spinner, and remove action.
- `scripts/test-queue-storage.mjs`: Added unit tests for artist and album storage cycles (all 16 tests in suite pass).

---

## 9. Shipped Task: Theme Modes (Light / Dark / System) ✅ DONE (2026-08-22)

- `src/index.css`:
  - Defined comprehensive CSS Custom Properties for `:root` (light mode) and `.dark, [data-theme="dark"]` (dark mode).
  - Backgrounds: `--bg-base`, `--bg-surface`, `--bg-surface-elevated`, `--bg-surface-hover`, `--bg-card`, `--bg-card-hover`, `--bg-input`, `--bg-input-focus`, `--bg-header`, `--bg-sidebar`, `--bg-nav`, `--bg-player-bar`, `--bg-modal`, `--bg-popover`, `--bg-glass`.
  - Text: `--text-primary`, `--text-secondary`, `--text-muted`, `--text-subtle`, `--text-inverse`.
  - Borders: `--border-subtle`, `--border-medium`, `--border-strong`, `--border-glass`.
  - Shadows & Accents: `--shadow-sm`, `--shadow-md`, `--shadow-lg`, `--shadow-xl`, `--accent-lime`, `--accent-purple`.
- `index.html`: Added pre-mount `<script>` in `<head>` inspecting `localStorage.getItem('auralis_theme')` and `window.matchMedia('(prefers-color-scheme: dark)')` to prevent theme flash before React renders.
- `src/types/music.ts`: Added `ThemeMode = 'dark' | 'light' | 'system'` and updated `PlayerSettings` with `theme: ThemeMode`.
- `src/context/PlayerContext.tsx`:
  - Added `theme`, `effectiveTheme`, and `setTheme` state.
  - Implemented dynamic OS preference change listener (`mediaQuery.addEventListener('change')`).
  - Synced `.dark` / `.light` classes and `data-theme` attribute on document root element.
  - Persisted selection under `auralis_theme` and `auralis_settings`.
- Converted all application views and components to semantic theme tokens:
  - `App.tsx`, `Sidebar.tsx`, `Header.tsx`, `MobileNav.tsx`, `Toast.tsx`.
  - `HomeView.tsx`, `ExploreView.tsx`, `LibraryView.tsx`, `FavoritesView.tsx`.
  - `MiniPlayer.tsx`, `NowPlayingModal.tsx`, `SyncedLyrics.tsx`, `AudioVisualizer.tsx`.
  - `CreatePlaylistModal.tsx`, `AddToPlaylistButton.tsx`.
- `components/common/Header.tsx`: Added Theme Toggle dropdown with Dark (Moon), Light (Sun), and System (Monitor) options with active checkmark indicator.
- `scripts/test-theme-system.mjs`: Added 9 automated unit tests verifying theme resolution, zero-flash script presence, CSS token definitions, type definitions, context listener, and header toggle integration.

---

## 10. Shipped Task: Google Authentication ✅ DONE (2026-08-22)

**Web: verified live. Android: code + APK build complete, native runtime awaits a
Firebase-console SHA-1 step (see §3).**

- `services/googleSignIn.ts` (new): platform-branched auth service that keeps the
  Firebase **JS SDK as the single source of truth**.
  - Web → `signInWithPopup(auth, googleProvider)`.
  - Native → dynamic `import('@capacitor-firebase/authentication')`,
    `FirebaseAuthentication.signInWithGoogle({ skipNativeAuth: true })`, then
    `signInWithCredential(auth, GoogleAuthProvider.credential(idToken, accessToken))`
    — no duplicate native Firebase session.
  - `signOutEverywhere(auth)`: clears the native session (best-effort) **and** the
    JS SDK session.
  - `isSignInCancellation(error)`: classifies web (`auth/popup-closed-by-user`,
    `auth/cancelled-popup-request`, `auth/user-cancelled`) and native (`12501`,
    "cancel"/"dismiss" messages) cancellations so a user backing out is never
    logged or surfaced as an error.
- `services/firebase.ts`: native-aware durable persistence via `createAuth` —
  `initializeAuth(app, { persistence: [indexedDBLocalPersistence, browserLocalPersistence] })`
  on native (falls back to `getAuth`), so the session survives an app restart.
- `context/AuthContext.tsx`: delegates to the service (`googleSignIn` /
  `signOutEverywhere` / `isSignInCancellation`); drops the direct
  `signInWithPopup`/`signOut` imports; `onAuthStateChanged` remains the source of
  truth; existing Firestore favorites/playlists sync untouched.
- `components/common/Header.tsx`: uses `isSignInCancellation` so web + native
  cancellations are handled uniformly (no error toast on user cancel).
- `capacitor.config.json`: replaced the inert `@codetrix GoogleAuth` block with a
  real `FirebaseAuthentication` config (`skipNativeAuth: true`,
  `providers: ["google.com"]`).
- `android/variables.gradle`: `rgcfaIncludeGoogle = true` — bundles the Google
  Sign-In runtime deps (Credential Manager + play-services-auth + googleid), which
  the plugin otherwise declares `compileOnly` (→ runtime crash).
- `package.json`: added `@capacitor-firebase/authentication ^8.4.0` (the only
  Capacitor-8-compatible choice; peers match `@capacitor/core >=8` + `firebase ^12`).
- `scripts/test-google-auth.mjs` (new): 10 tests — cancellation classification
  (web/native/genuine-failure), service wiring (platform branch, popup, dynamic
  plugin import, `skipNativeAuth`, credential exchange, unified sign-out),
  drift-guard, `AuthContext` delegation, native persistence, Capacitor config,
  gradle flag, and the package dependency.

**Genuine Android blocker:** native runtime needs an Android OAuth client
(`client_type 1`) for `com.auralis.music` registered with the app's SHA-1 in the
Firebase console; the tracked `google-services.json` currently has only the web
client. Add SHA-1 `85:80:EB:7F:A1:B4:22:AE:08:62:01:07:9A:09:DD:EB:EA:AA:E5:80`
(debug) → re-download `google-services.json` → rebuild. No Play-Services
device/emulator was available here to verify the native flow at runtime.

---

## 11. Verification Summary

- **Unit test suite:** `npm test` runs 8 test files (`test-queue-storage`,
  `test-queue-ops`, `test-lyrics-matching`, `test-search-parsing`,
  `test-search-reliability`, `test-lyrics-translation`, `test-theme-system`,
  `test-google-auth`). **88 tests passing, 0 failures.**
- **TypeScript / build:** `npm run build` (`tsc -b && vite build`) compiles and
  builds cleanly with zero errors.
- **Android debug build:** `gradlew assembleDebug` → `BUILD SUCCESSFUL`
  (APK ~8.2 MB) with the Firebase auth plugin + Google runtime deps bundled.
- **Web Google auth:** verified in-browser — sign-in is enabled
  (`isFirebaseConfigured === true`) and clicking it initiates a real Google OAuth
  popup to `auralis-70cf8.firebaseapp.com/__/auth/handler`.
- **Android Google auth:** code complete and the APK builds; native runtime not
  verifiable in this environment (console SHA-1 step + a Play-Services device
  required — see §10).
