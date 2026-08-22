# Baseline fixes

Scope of this pass: repair the baseline defects recorded in
[`docs/parity-audit.md`](./parity-audit.md). No UI redesign, and no new
parity features were implemented.

No third-party reference-app source code, assets, branding, or UI was read or
copied. Everything below is a change to Auralis' own code.

---

## 1. Android identity and launcher

The Android project still carried another application's identity. The Gradle
`namespace` and `applicationId` were already `com.auralis.music`, but the Java
source tree and the string resources were not, so the manifest's launcher
reference could not resolve.

**Changed**

- Added `android/app/src/main/java/com/auralis/music/MainActivity.java`, a
  `BridgeActivity` subclass in the package that matches the Gradle `namespace`.
  The manifest declares `android:name=".MainActivity"`, which resolves relative
  to the namespace, so before this file existed there was no such class.
- Rewrote `android/app/src/main/res/values/strings.xml`. `app_name` and
  `title_activity_main` carried a foreign app name; `package_name` and
  `custom_url_scheme` carried a foreign package. All four are now Auralis /
  `com.auralis.music`. `custom_url_scheme` matters beyond cosmetics: it is the
  deep-link scheme Capacitor registers, and a foreign value breaks OAuth
  redirects back into the app.
- Removed a stray foreign-branding comment in `src/components/player/MiniPlayer.tsx`.

**Verified**

- A repo-wide case-insensitive search for the previous owner's name and package
  across source, configs, Android files, manifests, filenames and generated
  output returns nothing.
- `namespace`, `applicationId`, `capacitor.config.json` `appId`, and
  `google-services.json` `package_name` all read `com.auralis.music`.
- Exactly one `MainActivity` type exists in the source tree.

**The stale Java package has been deleted**, after confirming it was unused: the
file it contained declared no `package` and no type (it had been reduced to
comments only), no reference to the old package existed anywhere in the repo, and
exactly one `MainActivity` class remains (`com.auralis.music`). The Java source
tree now contains only
`android/app/src/main/java/com/auralis/music/MainActivity.java`.

**Staging note.** `git status` showed the deletion unstaged and the *new*
`com/auralis/` directory **untracked**. Committing in that state would remove the
only `MainActivity` from the repo. Both changes need to be staged together:

```
git add -A android/app/src/main/java/
```

This could not be done automatically — a stale `.git/index.lock` was present, and
removing another process's lock risks corrupting the index.

**Android debug build: not run. Exact blocker.** `./gradlew assembleDebug`
cannot execute in the environment these fixes were made in, and the missing
pieces cannot be fetched:

| Requirement | State |
| --- | --- |
| JDK | Only OpenJDK 11 present (`/usr/lib/jvm` has `java-11-openjdk-amd64` only). `android/app/capacitor.build.gradle` sets `sourceCompatibility`/`targetCompatibility` to `VERSION_21`, and AGP 8.13.0 requires JDK 17+. |
| Android SDK | `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset; no SDK directory exists; `sdkmanager`, `adb`, and `aapt2` are all absent. |
| Gradle distribution | `gradle-wrapper.properties` requires `gradle-8.14.3-all.zip`; `~/.gradle/wrapper/dists` does not exist, so nothing is cached. |
| Network | `https://services.gradle.org/...` and `https://dl.google.com/...` both return HTTP `000` (no connection). |

So the Android identity fix is verified **statically**, by the checks listed
above, and not by a compile. Running `./gradlew assembleDebug` on a machine with
JDK 17+ and the Android SDK is still needed to confirm.

---

## 2. Search honesty

Previously a network failure was indistinguishable from a real result:
`searchYouTube()` swallowed provider errors and callers fell back to a
hand-written list of well-known tracks, which was then presented as
recommendations. That made a broken backend look like working software.

**Changed — `src/services/youtube.ts`**

- `CURATED_TRACKS` renamed to `DEMO_TRACKS` and documented as optional demo
  content only. It must not be returned from search, must not be used as
  recommendations or quick picks, and must only appear behind an explicit,
  user-visible demo label. No code path returns it today.
- Added `SearchUnavailableError`, carrying the list of endpoints attempted, so
  callers can tell "every provider was unreachable" apart from "the providers
  worked and found nothing".
- `searchYouTube()` now has four explicit outcomes and no fallback: empty query
  → `[]`; a provider returned results → those results; a provider answered with
  nothing → `[]`; every provider failed → throws.
- Only non-empty result sets are cached, so a failed or empty query is retried
  rather than remembered.
- The dev-only `/api/youtube-search` middleware also answers `200 {results: []}`
  on its own internal errors, so an empty response from it is no longer treated
  as authoritative — the public providers are tried before concluding
  "no results".
- Unknown durations are reported as `0` instead of a fabricated default, and
  `ExploreView` renders that as `--:--`.

**Changed — UI**

- `HomeView.tsx`: dropped the `CURATED_TRACKS` import, the seeded initial state,
  and the silent `catch` that logged a warning and kept showing the fixed list.
  Quick picks now render one of four real states — loading, error with a Retry
  button, genuinely empty, or results. `handleSpeedDialClick` and
  `handleChipClick` previously called `searchYouTube` with no error handling and
  would now throw on failure; both report the failure in a dismissible notice.
- `ExploreView.tsx`: real error state with Retry replacing `console.error`, and
  the empty state is reachable now that failures are no longer silent.
- `Header.tsx`: the typeahead reports search failure in the dropdown instead of
  showing "No matching tracks found", which previously implied an authoritative
  empty answer to a request that never completed.

**Changed — header query propagation**

Pressing Enter in the header used to call `setActiveView('explore')` and discard
the typed text, so Explore ran its default trending query instead of the user's
search. `Header` now accepts `onSubmitSearch` and passes the query up; `App`
holds `{ query, nonce }` and passes both to `ExploreView`, whose effect depends
on the nonce so submitting the *same* query twice still re-runs the search.

**Verified**

`src/services/youtube.ts` was compiled with `tsc` and its real
`searchYouTube` exercised against a stubbed `fetch` (`/tmp/searchtest/run.mjs`).
All 12 checks passed:

- empty query returns `[]` without issuing a request
- all providers down throws `SearchUnavailableError`, listing 3 attempted endpoints
- providers reachable with no matches returns a real empty `[]`
- that empty result is not the demo list
- provider results are returned and parsed; `Artist - Title` split correctly
- unknown duration surfaces as `0`, not a fake default
- unusable video ids are dropped
- a failed query is retried on the next call, not served from cache
- a `200 {results: []}` from the dev endpoint falls through to public providers
- `DEMO_TRACKS` is exported but unreachable from the search path

Static: `grep -rn CURATED_TRACKS src/` returns nothing, and `DEMO_TRACKS`
appears only at its definition.

---

## 3. Unsafe cloud sync

There was a data-loss race. The persist effect had dependencies
`[favorites, user]`, so it fired the moment `user` became non-null — with
whatever was in local state — while the fetch-and-merge effect was still in
flight. Because `setDoc(..., { merge: true })` replaces the `favorites` array
field wholesale, signing in on a fresh device wrote `favorites: []` and wiped the
account's existing cloud favorites. The same applied to playlists.

**Changed — `src/context/PlayerContext.tsx`**

Added a hydration gate. `hydratedUid` holds the uid whose cloud document has been
read and merged; cloud writes are refused until it matches the signed-in uid.
Local-storage writes are unchanged and still happen on every change, split into
their own effects.

The gate also stays shut when the read *fails*, because in that case local state
has not been reconciled and writing it back could destroy cloud data. The user is
told via a toast that cloud saving is paused, rather than the failure being
swallowed.

**Changed — `src/context/AuthContext.tsx`**

- `fetchCloudData()` previously returned `null` both for "document does not
  exist" and for "the read threw", which made the safe behaviour impossible to
  implement. It now returns `null` only for a missing document and rejects with
  `CloudReadError` on a real failure.
- Writes record `updatedAt`, plus per-field `favoritesUpdatedAt` /
  `playlistsUpdatedAt` and `schemaVersion`. The legacy `lastSyncedAt` field is
  still read for compatibility.
- Added `isAuthAvailable`, `authError`, and `lastSyncedAt` so the UI can state
  real sync status instead of asserting one.
- The merge policy is a union keyed by id, with local entries winning on
  collision. Nothing is dropped on either side.

**Changed — Firebase configuration**

`src/services/firebase.ts` had the whole web config hardcoded as `||` fallbacks
behind the env vars, which pinned the app to one project and masked a missing
setup. All six values now come from the environment with no fallbacks. When a
required variable is absent the module exports `isFirebaseConfigured: false` and
a `firebaseConfigError` explaining which variables are missing; `auth` and `db`
are `null` and every consumer checks them. The app keeps working without an
account, and the Sign In button is disabled with the real reason on hover
instead of appearing functional.

`android/app/google-services.json` was **not** deleted — it is the native
Android config and is a separate concern from the web SDK values.

Added `.env.example` documenting every variable and where to find it, and
`.gitignore` now excludes `.env` while keeping `.env.example`.

One real configuration bug found while doing this: the previous hardcoded
`appId` was an **Android** app ID (`1:30030184374:android:...`). The Firebase JS
SDK needs a **Web** app ID (`1:<sender>:web:<hash>`). `.env.example` calls this
out explicitly. If no Web app is registered in the Firebase project, one has to
be created before sign-in can work.

`src/services/firebase.ts` now **rejects a non-Web app ID at startup** rather
than only documenting the requirement. An Android or iOS app ID in
`VITE_FIREBASE_APP_ID` produces a precise message naming the platform and where
to get the right value; anything else that is not `1:<sender>:web:<hash>` is also
refused. Without this the SDK accepts the wrong ID at `initializeApp()` and fails
much later with an opaque auth or Firestore error, which is what made the
original bug so hard to see. Verified with 5 cases (the real old Android ID, a
valid Web ID, an iOS ID, empty, and a project ID pasted by mistake) — 5/5 as
expected.

A `.env` has been prepared with the five values that are legitimately derivable
from `android/app/google-services.json`: `PROJECT_ID` (`auralis-70cf8`),
`AUTH_DOMAIN`, `STORAGE_BUCKET`, `MESSAGING_SENDER_ID` (`30030184374`), and
`GOOGLE_CLIENT_ID` (the `client_type: 3` OAuth client, which is the web one and
matches `capacitor.config.json`). It is gitignored.

Two values are deliberately left **blank** because they are Web-app-specific and
do not exist in `google-services.json`, and filling them with plausible-looking
substitutes would be exactly the kind of fake configuration this pass removed:

- `VITE_FIREBASE_APP_ID` — only obtainable from the Firebase console.
- `VITE_FIREBASE_API_KEY` — `google-services.json` has an *Android* key
  (`AIzaSyBSJX…`), which is normally restricted to Android apps and rejected from
  a browser origin. Use the Browser key from the Web app's config block.

Until `APP_ID` is filled the app runs normally with sign-in disabled and states
the exact missing variable, which is the intended honest degradation.

**Also found: the Android app was shipping the pre-fix bundle.**
`android/app/src/main/assets/public/` is where Capacitor copies the built web app,
and it held a bundle built before any of these fixes. It contained the hardcoded
Android `appId` and API key, the old `CURATED_TRACKS` search fallback
(`kh.slice(0,8)`), and the old `lastSyncedAt`-only Firestore write, and none of
the new markers (`favoritesUpdatedAt`, `SearchUnavailableError`, `hydratedUid`);
13 source files were newer than it. So an Android build run today would still
exhibit every defect in this document.

It is gitignored (`android/.gitignore:96`), so nothing leaked into git history,
and it was left in place rather than deleted because it is regenerated build
output that an Android build consumes — deleting it would yield a blank-screen app
instead of a stale one. **It is only fixed by `npm run build && npx cap sync
android`**, which `scripts/verify-build.ps1` does.

**Changed — Firestore security rules**

Added `firestore.rules` (and a minimal `firebase.json` pointing at it). Rules:
a user may `get` only `users/{their own uid}`; collection `list` is denied so the
set of user ids cannot be enumerated; `create`/`update` are owner-only and also
type-check `favorites`/`playlists` as lists and the timestamps as numbers, with
size caps (5000 favorites, 500 playlists, 16 keys) so a compromised client cannot
use the document as bulk storage; nested collections and everything outside
`users/{uid}` are denied explicitly rather than by omission.

Deploy with `firebase deploy --only firestore:rules`.

**Verified**

- The gate's effect ordering was modelled and executed
  (`/tmp/gate/gate.test.mjs`), including a control case reproducing the original
  data loss. All 6 checks passed: no write is attempted before hydration; cloud
  favorites survive sign-in from an empty device; local state is hydrated from
  the cloud; a failed read produces zero writes and leaves the cloud untouched;
  and the old ungated behaviour does empty the cloud, confirming the test would
  have caught the bug.
- `firestore.rules` brace balance, `rules_version = '2'`, and the presence of a
  default-deny block were checked programmatically.
- **Not verified:** the rules were not run against the Firestore emulator, and
  no sign-in or Firestore round-trip was executed. `firebase-tools` is not
  installed, the npm registry is blocked in this environment (HTTP 403), and no
  Firebase credentials were available. The rules and the sync flow need one
  manual round-trip test — sign in on a device with an empty library against an
  account that already has cloud favorites, and confirm nothing is lost.

---

## 4. False and inert controls

- **Sidebar "Auralis Cloud Ready"** — was a hardcoded green pulsing dot shown
  regardless of whether Firebase was configured, anyone was signed in, or any
  write had ever succeeded. Now derived from real state, with six distinct
  outcomes: not configured, sync problem, signed out (device-only), syncing,
  synced, and signed in but not yet synced.
- **Header "Cloud Sync Active"** — same problem, and it also overstated the
  feature. Replaced with the real state (`authError` / `isSyncing` /
  `lastSyncedAt`) plus an explicit scope line: *"Auralis favorites and playlists
  only — not YouTube Music library sync."* This is Firebase Google sign-in with
  Firestore sync of Auralis' own data. It does not read or write any YouTube
  Music library, artists, albums, or playlists.
- **Sign In button** — now disabled with the configuration error as its tooltip
  when Firebase is unconfigured, and the success toast only fires after
  `signInWithPopup` actually resolves. Failures surface the real message instead
  of always blaming the internet connection.
- **Visualizer caption** — claimed "Fluid Harmonic Visualizer / Synchronized to
  active audio stream". The bars are sine waves driven by the play/pause flag;
  there is no frequency analysis. Playback runs through a cross-origin YouTube
  IFrame player, so no `MediaElementAudioSourceNode` can be attached and real
  spectrum data is not available to this app at all. Recaptioned "Ambient Motion
  / Decorative animation driven by playback state, not audio analysis".
- **Playback rate** — `settings.playbackRate` was stored and persisted but had no
  UI control and was never passed to the player: a setting that did nothing.
  Removed from `PlayerSettings` and from the defaults, with a comment recording
  that playback-speed control should return together with real wiring to the
  player's `setPlaybackRate` API and a visible control. Nothing read the value,
  so no behaviour changed. Stale values in existing `localStorage` are ignored
  harmlessly.
- **Mobile Now Playing tabs** — the right-hand panel's three conditions OR-ed
  `mobileTab` with `activeModalTab`, so on a phone with Queue selected while
  `activeModalTab` was still `'lyrics'`, the lyrics view and the queue both
  rendered stacked inside the same panel. Replaced with a single derived
  `panelTab`, which makes rendering two tabs structurally impossible.

**Verified**

`npx tsc -b --force` passes with no errors across the whole project after every
change above. The indicator states were traced by reading the derivation against
`AuthContext`'s exported values.

---

## 5. Repository-wide name purge

The Android project was forked from another application, so its name and package
appeared in places a source grep of `src/` would miss. A final sweep removed the
last Auralis-owned occurrences.

**Changed**

- Renamed the parity audit to [`docs/parity-audit.md`](./parity-audit.md) — its
  filename previously carried the old project name — and rewrote its four in-text
  occurrences to neutral wording ("the reference app"). The document's findings
  and verdicts are unchanged.
- Rewrote the thirteen occurrences in this file. Where the old identifier was
  being quoted as evidence of a defect, the description now names the *kind* of
  value that was wrong rather than the literal string, so the audit trail still
  reads correctly.
- Updated the cross-link in this file to the new filename.

**Verified**

The sweep was case-insensitive and covered the bare project name plus its dotted
and slashed package forms. It ran over filenames and folder names, tracked file
contents, every file on disk, and the generated bundles in `dist/` and
`android/app/src/main/assets/`. All categories return zero.

Identity values now read:

| Location | Value |
|---|---|
| `strings.xml` `app_name` / `title_activity_main` | `Auralis` |
| `strings.xml` `package_name` / `custom_url_scheme` | `com.auralis.music` |
| `capacitor.config.json` `appId` / `appName` | `com.auralis.music` / `Auralis` |
| `build.gradle` `namespace` / `applicationId` | `com.auralis.music` |
| Only Java package declaration | `package com.auralis.music;` |

**No third-party dependency carries the name.** `package.json` and
`package-lock.json` contain zero occurrences, no package directory under
`node_modules` matches, and a content scan of `node_modules` returns nothing. So
there is no externally-required reference that had to be preserved.

**One occurrence cannot be removed without rewriting history.** The initial
commit `17a193f` contains the file at the old path, and the git index still lists
it because the deletion has not been staged. Committing the staged rename removes
it from the working tree and from all future commits, but the blob remains
reachable in history. Erasing that requires `git filter-repo` or a fresh initial
commit, which rewrites every commit hash — worth doing only if the history is not
yet shared.

---

## Build results

Two rounds: everything verifiable inside the Linux sandbox, then the steps that
required Windows. The Windows column is authoritative for anything involving a
native binding or the Android toolchain.

| Check | Sandbox (Linux) | Windows |
| --- | --- | --- |
| `tsc -b --force` (full typecheck) | **Passes**, no errors | — |
| `searchYouTube` behaviour suite | **12/12 pass** | — |
| Cloud hydration gate suite | **6/6 pass** | — |
| `firestore.rules` structural check | **Passes** | — |
| PowerShell parser (`ParseFile`) | not possible, no runtime | **Passes** |
| `npm run build` (`tsc -b && vite build`) | **Blocked**, native binding | **Passes** |
| `npx cap sync android` | pointless without a build | **Passes** |
| `oxlint` | **Blocked**, same cause | not re-run |
| `./gradlew assembleDebug` | **Blocked** | **Blocked**, no JDK 21 / SDK |

`npm run build` runs `tsc -b && vite build`. The `tsc -b` half completes cleanly;
`vite build` then fails before touching any application code:

```
Error: Cannot find native binding.
Cannot find module '@rolldown/binding-linux-x64-gnu'
```

`node_modules` was installed on Windows and contains only
`@rolldown/binding-win32-x64-msvc`, so the Linux binary Rolldown needs is
absent. It cannot be installed here: `npm view @rolldown/binding-linux-x64-gnu`
returns `403 Forbidden` because the npm registry is not reachable from this
environment. `oxlint` fails identically for its own native binding.

This is an environment limitation, not a defect in the changes — no application
module is involved in the failure. **`npm run build` should be run on Windows,
where the correct bindings are already installed, to confirm the bundle.**

### The shipped bundle was stale — now resolved

At the time the fixes were made, `dist/` predated them, and so did the copy under
`android/app/src/main/assets/public/`:

| Evidence | Value |
| --- | --- |
| `dist/assets/index-*.js` mtime | `2026-08-21 19:04:01` |
| newest modified source file | ~4h *later* |
| `dist` vs `android/.../assets/public` SHA-256 | **identical** (`10e44a92…`) |
| old hardcoded Android `appId` in bundle | present |
| old hardcoded `apiKey` in bundle | present |
| curated-tracks fallback in bundle | present |
| `favoritesUpdatedAt`, `SearchUnavailableError`, `hydratedUid` | **all absent** |

Both copies were the same pre-fix bytes, so syncing would have copied stale output
over stale output. The order could not be shortened:

```
npm run build          # regenerates dist/ from current source
npx cap sync android   # only meaningful after the line above succeeds
```

Both have since run on Windows. The current bundle is `index-CI2j1GTY.js`
(644,611 bytes) and the shipped Android copy is byte-identical to it
(SHA-256 prefix `7aac19d1b103e904`), so the Android project no longer carries
pre-fix code. `verify-build.ps1` now asserts that equality on every run rather
than leaving it to be checked by hand — see *Verification correctness* below.

### Why these two builds were not run here

The fixes were made from a Linux sandbox (`Ubuntu 22.04`, `uname -a` reports
`Linux claude`), not from Windows. It has no view of the Windows filesystem
(`/mnt/c` and `/c` do not exist) and no Windows shell (`powershell.exe`,
`cmd.exe`, `wsl.exe` are all absent), so it cannot install anything on the host or
invoke the host toolchain. Re-tested rather than assumed:

| Probe | Result |
| --- | --- |
| `curl https://registry.npmjs.org/` | `000` (no connection) |
| `npm view @rolldown/binding-linux-x64-gnu` | `403 Forbidden` |
| `curl https://services.gradle.org/distributions/` | `000` |
| `curl https://dl.google.com/android/repository/` | `000` |
| `curl https://api.adoptium.net/...` | `000` |
| `apt-get install openjdk-21-jdk` | `E: Unable to locate package` |
| JDKs present | `java-11-openjdk-amd64` only |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | unset; no SDK dir; no `sdkmanager`/`adb`/`aapt2` |

There is no path to either build from here: the required binaries cannot be
downloaded and the host cannot be reached.

**What to run.** Two scripts, both plain ASCII with CRLF endings:

```powershell
.\scripts\detect-android-toolchain.ps1          # inspect; -Apply to configure
.\scripts\verify-build.ps1                      # the full build sequence
```

`verify-build.ps1` checks the toolchain, reinstalls dependencies if the Windows
native bindings are missing, runs `npm run build`, then `npx cap sync android` —
which is what replaced the stale shipped bundle described above — asserts the
shipped Android bundle is byte-identical to `dist/`, then runs
`gradlew.bat assembleDebug`, logging everything to `build-verification.log`.

### The script had to be rewritten once: a PowerShell encoding trap

The first revision was not valid PowerShell and failed with cascading
`Unexpected token '$('`, `Unexpected token '|'`, and `Missing closing ')'`
errors. The cause was **not** the syntax. The file contained en and em dashes and
was saved as UTF-8 without a BOM; Windows PowerShell decodes a BOM-less `.ps1`
using the ANSI codepage, so `—` (`E2 80 94`) became three characters ending in
`”` (U+201D) — which PowerShell accepts as a **string delimiter**. Every string
after the first dash terminated in the wrong place.

Both scripts are now pure ASCII with CRLF, which decodes identically under
Windows-1252, UTF-8, and UTF-8-with-BOM, so the fault cannot recur. Neither could
be executed in the sandbox (no PowerShell runtime), so each was checked with a
tokenizer that tracks quote, here-string, comment, `$( )`, and bracket state, was
proved to catch eight fault-injected defects, and carries this in its header for
whoever verifies it on Windows:

```powershell
$e = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path .\scripts\verify-build.ps1), [ref]$null, [ref]$e) | Out-Null
$e
```

### Verification correctness

Two of the original bundle markers were unsound and were replaced. Both had to be
made **stricter**, not relaxed, because each could report the wrong answer.

**1. `:android:` as a substring could never pass.** It was meant to catch a
leaked Android app ID in the shipped JavaScript. But `src/services/firebase.ts`
contains the guard that *rejects* such an ID:

```ts
const platform = /:android:/.test(appId) ? 'an Android' : ...
```

The literal therefore ships in every correct build. The check could not tell the
detector apart from the defect it detects, so it reported `FAIL` on a bundle that
was in fact clean — exactly one occurrence, at byte 519,542, being
`/:android:/.test(e)`. It now tests the **shape of a real identifier** instead:

```powershell
'1:[0-9]+:(android|ios):[0-9A-Za-z]+'
```

`:web:` is deliberately excluded, because Vite inlines `VITE_FIREBASE_APP_ID` as a
literal at build time, so a legitimate Web app ID is *expected* in the bundle once
`.env` is filled. Flagging it would make the check fail on a correct build.

**2. `kh.slice(0,8)` passed for the wrong reason.** It was meant to prove the
curated-tracks fallback was gone. `kh` is a *minifier-generated* name, and the
minifier renamed it to `i` in the next build, so the marker matched nothing and
reported `PASS` regardless of what the code did. Any check keyed to a minified
identifier is worthless. It was replaced with positive markers on **string
literals**, which minification preserves:
`SearchUnavailableError`, `favoritesUpdatedAt`, `playlistsUpdatedAt`, `Web app ID`.

Measured against the current bundle, and then against deliberately corrupted
copies of it:

| Case | Result |
| --- | --- |
| the bundle as built | **PASS**, all six checks |
| a real Android app ID injected | **FAIL** (correctly) |
| the `google-services.json` API key injected | **FAIL** (correctly) |
| a legitimate `:web:` app ID injected | **PASS** (correctly not flagged) |
| `SearchUnavailableError` removed | **FAIL** (correctly) |
| `favoritesUpdatedAt` removed | **FAIL** (correctly) |

So the `:android:` marker now passes because the check became accurate, not
because it was loosened. No source change was needed: the source was already
correct, and `1:30030184374:android`, `b4dabb16a9c3a96e71cb17`, `AIzaSyBSJX`, and
`DEMO_TRACKS` all occur **zero** times in the bundle.

### Android toolchain: the remaining blocker

`gradlew assembleDebug` still cannot run, because Windows has no JDK 21 and no
Android SDK. Neither can be resolved from the sandbox: it cannot see the Windows
filesystem or registry, and it has no network egress, so a probe has to run on
Windows. `scripts/detect-android-toolchain.ps1` is that probe. It is read-only by
default and never downloads anything. It searches:

- `JAVA_HOME`, and whatever `java.exe` is on `PATH`, walked back to its home
- JDK vendor directories under `%ProgramFiles%`, `%ProgramFiles(x86)%`, and
  `%LOCALAPPDATA%\Programs` (Adoptium, Microsoft, Corretto, Zulu, BellSoft, Oracle)
- **Android Studio's bundled JBR**, which on current versions *is* a JDK 21, so
  the requirement may already be satisfied with nothing to install
- `HKLM\SOFTWARE\JavaSoft\*` and the Android Studio registry keys
- `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `%LOCALAPPDATA%\Android\Sdk`,
  `%USERPROFILE%\Android\Sdk`, the `Android SDK Tools` registry keys, and any
  existing `android\local.properties`

Every location is either read from the environment or the registry, or enumerated
from disk with `Get-ChildItem`, and then tested — no path is assumed to exist. A
candidate only counts as a JDK if it really contains `bin\java.exe`, and its
version comes from the `release` file or from `java -version`, never from its
directory name. SDK candidates are reported with whether platform 36 and
`build-tools` are actually present.

With `-Apply` it makes exactly two reversible changes, and only for things it
actually found: it writes `sdk.dir` into `android\local.properties` (gitignored,
machine-local, and the canonical way to point Gradle at an SDK) and sets
`JAVA_HOME` for the current user, printing the previous value first so the change
can be undone. `verify-build.ps1` reads `local.properties` as an SDK source, so
once `-Apply` has run the build picks the SDK up with no further configuration.

If nothing suitable is found, the script prints the exact install command for
whichever package manager exists on the machine and stops. It does not install
anything itself: an unattended JDK or Android Studio download is a multi-gigabyte
change to the user's machine, and the instruction was not to do that without a
safe mechanism. **This is the one part of the baseline that cannot be closed
autonomously** — the toolchain has to be installed on the Windows side, after
which `verify-build.ps1` will run `assembleDebug` unattended.

The project targets `JavaVersion.VERSION_21`, AGP 8.13.0, Gradle 8.14.3, and
`compileSdk` 36.

---

## Remaining known issues, deliberately not changed

> **Update 2026-08-22:** the playlist, queue and mobile-navigation defects listed
> in `docs/parity-audit.md` were repaired in a later pass — see
> `docs/library-fixes.md`. Everything below still stands as written.

These are real and were found during this pass, but fixing them would have meant
changing navigation behaviour or building new features, both out of scope.

- `android/app/src/main/java/com/auralis/music/MainActivity.java` and the deletion
  of the old one at the previous owner's package path are **staged as a single
  rename** (`R076`) but deliberately **not committed**. They must land together:
  committing only the deletion would leave the tree with no `MainActivity` at all,
  and the manifest's `.MainActivity` would not resolve in a fresh clone.
  `verify-build.ps1` warns if the new file is ever untracked again.

- The "Lyrics" shortcuts on Home and the mini player set `activeModalTab` but not
  the mobile tab, and the phone layout opens Now Playing on the player tab, so on
  a phone those shortcuts do not land on lyrics. Fixing it properly needs a
  one-shot "requested tab" signal separate from the persistent tab state.
- `capacitor.config.json` still hardcodes a `GoogleAuth.serverClientId`. That
  file is read at native build time and cannot consume Vite env vars, so it was
  left alone rather than broken. The value is a public OAuth client id, not a
  secret.
- `/api/youtube-search` is registered by a Vite `configureServer` middleware, so
  it exists only under `vite dev`. In a production web build or the Capacitor
  Android build it is absent and search depends entirely on public Piped /
  Invidious instances. Search now reports this honestly instead of hiding it, but
  the underlying gap is a parity item, not a baseline defect.
- `MOODS` in `src/services/youtube.ts` is unused, and `DEMO_TRACKS` is exported
  without a consumer. Both were left in place: the instruction was to preserve
  the curated list as labelled demo content, not to delete it.
- `git status` shows `android/gradlew.bat` as modified with 94 insertions and 94
  deletions. This is **line endings only** — the working copy has CRLF on all 94
  lines while the committed blob is LF, and the content is byte-identical once
  CR is stripped (both hash to `94102713eb8fb22d`). Nothing in this pass touched
  the file; something on the Windows side normalised it, and CRLF is the correct
  form for a `.bat`. It was deliberately left alone. Adding a `.gitattributes`
  with `*.bat text eol=crlf` would stop the diff noise recurring, but that is
  outside the scope of these fixes.
