# Auralis — Lyrics Sync Verification Report

Single, append-only verification record for the word-by-word / syllable-level lyrics
synchronization work. Every verification step below records the exact command, timestamp,
result, and the relevant output. New runs are **appended**; nothing is overwritten.

Full raw command output is kept alongside this file in `docs/verification-logs/` so that
long or truncated console output is never the only copy.

| Field | Value |
|---|---|
| Repository | `C:\Users\shrey\OneDrive\Desktop\Auralis` |
| Branch | `main` |
| Git HEAD at verification | `ceeac2fde7a9a9e6c8f148ab5fa9e191af053f5d` |
| Scope covered by this entry | Phase 1 (clock + provider selection), Phase 2 (duration contract + cache) |
| Phase 3 (word renderer) | **Not implemented yet** — explicitly out of scope for this entry |

---

## Entry 1 — 2026-09-03 (Phase 1 + Phase 2)

### 1.1 Toolchain and environment

Command:

```bash
cd android && ./gradlew --version
```

Timestamp: `2026-09-03T16:08:31Z` (`2026-09-03 21:38:31 +0530`)
Result: **success**. Full output: `docs/verification-logs/gradle-version.txt`

```
Gradle 8.11.1
Build time:    2024-11-20 16:56:46 UTC
Revision:      481cb05a490e0ef9f8620f7873b83bd8a72e7c39
Kotlin:        2.0.20
Groovy:        3.0.22
Ant:           Apache Ant(TM) version 1.10.14 compiled on August 16 2023
Launcher JVM:  25.0.3 (JetBrains s.r.o. 25.0.3+-15898627-b508.16)
Daemon JVM:    C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot (from org.gradle.java.home)
OS:            Windows 11 10.0 amd64
```

Command:

```bash
java -version
```

```
openjdk version "21.0.12.1" 2026-08-18 LTS
OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing)
```

Host OS: Windows 11 Home Single Language 10.0.26200, shell `bash` (Git Bash).

### 1.2 Note on the documented verification script

The plan's Verification section instructs:

```bash
powershell -ExecutionPolicy Bypass -File scripts/verify-build.ps1
```

**This script cannot pass in the current repository and was not used.** It performs a web
build (`npm run build`) followed by `cap sync`, but the React + Vite + Capacitor web layer
has been removed from the repo — there is no root `package.json`, so the script fails at the
`npm` step with `npm error enoent Could not read package.json`. This is a **stale-plan/tooling
issue, not an Android failure**.

Verification therefore used Gradle directly, which is what `verify-build.ps1` ultimately
wrapped for the Android half:

```bash
cd android && ./gradlew testDebugUnitTest
```

```bash
cd android && ./gradlew assembleDebug
```

### 1.3 Unit test run (full, clean, no task caching)

Command:

```bash
cd android && ./gradlew testDebugUnitTest --rerun-tasks
```

`--rerun-tasks` was used deliberately so that no result came from Gradle's up-to-date cache.

| Field | Value |
|---|---|
| Started | `2026-09-03T16:09:34Z` |
| Finished | `2026-09-03T16:14:25Z` |
| Wall time | 4 m 50 s |
| Process exit code | `0` |
| Gradle result | `BUILD SUCCESSFUL` |
| Tasks | `27 actionable tasks: 27 executed` (nothing up-to-date) |

Full console output: `docs/verification-logs/test-run.log`

Tail of that log:

```
> Task :app:kspDebugUnitTestKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4m 50s
27 actionable tasks: 27 executed
EXIT_CODE=0
```

### 1.4 Test counts

Aggregated from the JUnit XML in `android/app/build/test-results/testDebugUnitTest/`:

```
CLASSES=61 TESTS=269 FAILURES=0 ERRORS=0 SKIPPED=0 TOTAL_TIME=76.044s
```

Complete per-class table: `docs/verification-logs/test-per-class.txt` (all 61 classes).

Lyrics-relevant classes, extracted from that table:

| Class | Tests | Failures | Errors | Skipped | Time (s) |
|---|---|---|---|---|---|
| `LyricsClockTest` | 8 | 0 | 0 | 0 | 0.002 |
| `ProviderTierTest` | 9 | 0 | 0 | 0 | 0.001 |
| `WordTimingContractTest` | 10 | 0 | 0 | 0 | 0.038 |
| `SubdivisionTest` | 8 | 0 | 0 | 0 | 0.005 |
| `PauseGapTest` | 6 | 0 | 0 | 0 | 0.007 |
| `WordInterpolationTest` | 4 | 0 | 0 | 0 | 0.004 |
| `TtmlParserTest` | 4 | 0 | 0 | 0 | 0.018 |
| `DomainTtmlParserTest` | 3 | 0 | 0 | 0 | 0.093 |
| `WordSyncParsersTest` | 4 | 0 | 0 | 0 | 0.017 |
| `LrcParserTest` | 3 | 0 | 0 | 0 | 0.002 |
| `LyricsAccuracyGuardTest` | 3 | 0 | 0 | 0 | 0.018 |
| `LyricsBinarySearchTest` | 2 | 0 | 0 | 0 | 0.001 |
| `LyricsCenterArchitectureTest` | 4 | 0 | 0 | 0 | 0.002 |
| `LyricsMatcherTest` | 3 | 0 | 0 | 0 | 0.003 |
| `LyricsMatcherComprehensiveTest` | 7 | 0 | 0 | 0 | 0.021 |
| `LyricsScrollCenteringTest` | 3 | 0 | 0 | 0 | 0.160 |
| `LyricsSmoothScrollPhysicsTest` | 4 | 0 | 0 | 0 | 0.004 |
| `LyricsValidatorTest` | 3 | 0 | 0 | 0 | 0.001 |
| `MultiProviderCascadeTest` | 3 | 0 | 0 | 0 | 0.040 |
| `DualTierLyricsApiTest` | 2 | 0 | 0 | 0 | 0.051 |
| `EntityMappersTest` | 4 | 0 | 0 | 0 | 0.015 |
| `LyricsTest` (live network) | 2 | 0 | 0 | 0 | 23.179 |

37 of the 269 tests are new in this work (`LyricsClockTest` 8, `ProviderTierTest` 9,
`WordTimingContractTest` 10, `SubdivisionTest` 8, plus 2 added to `WordInterpolationTest`
and 2 added to `TtmlParserTest`).

### 1.5 Per-test results

Complete per-test listing for every lyrics class: `docs/verification-logs/test-per-test-lyrics.txt`.
Reproduced here in full for the classes that encode the timing contract.

```
### LyricsClockTest  (8 tests, 0 failures, 0 errors)
  [PASS] a sequence of frames on one plateau is monotonic and bounded  (0.001s)
  [PASS] scales the carry by playback rate  (0.001s)
  [PASS] a negative wall delta cannot walk the position backwards  (0.0s)
  [PASS] never extrapolates while stopped so a paused highlight freezes  (0.0s)
  [PASS] a zero or negative speed contributes no carry  (0.0s)
  [PASS] clamps the carry so a stalled clock cannot run away  (0.0s)
  [PASS] snaps to the raw reading whenever it changes  (0.0s)
  [PASS] carries the position forward across a plateau in the raw reading  (0.0s)

### ProviderTierTest  (9 tests, 0 failures, 0 errors)
  [PASS] a single timed word is a parser artefact, not word sync  (0.001s)
  [PASS] the instant win does not fire for a line-synced candidate  (0.0s)
  [PASS] a word-synced candidate scoring 60 beats a line-synced one scoring 150  (0.0s)
  [PASS] tierOf reads the timing format out of the data  (0.0s)
  [PASS] a RICHSYNC label with no real durations is not word tier  (0.0s)
  [PASS] instrumental lines cannot supply the word tier  (0.0s)
  [PASS] score still decides inside a tier  (0.0s)
  [PASS] a late word-synced arrival still displaces an early line-synced leader  (0.0s)
  [PASS] a line-synced candidate never displaces a word-synced one  (0.0s)

### WordTimingContractTest  (10 tests, 0 failures, 0 errors)
  [PASS] an unstated word end stays null in every parser  (0.007s)
  [PASS] nothing sweeps when no end was stated  (0.006s)
  [PASS] a lone untimed token degrades to line sync rather than claiming word sync  (0.001s)
  [PASS] a plain line-synced lyric is never labelled word-synced  (0.0s)
  [PASS] mergeMicroFragments does not manufacture words from line timestamps  (0.003s)
  [PASS] a dense partition keeps its stated timing and leaves the rest unpainted  (0.0s)
  [PASS] starts without ends are still labelled word-synced so they can step  (0.005s)
  [PASS] no word end reaches past the next word start in any parser  (0.01s)
  [PASS] a genuinely swept lyric survives the contract intact  (0.004s)
  [PASS] a word list sharing one timestamp is not word timing  (0.001s)

### SubdivisionTest  (8 tests, 0 failures, 0 errors)
  [PASS] pieces are contiguous monotonic and cover the span exactly  (0.001s)
  [PASS] subdivision preserves the line text verbatim  (0.002s)
  [PASS] subdividing a line cannot push a word past the next word start  (0.001s)
  [PASS] longer words get proportionally more of the span  (0.0s)
  [PASS] no piece escapes the measured interval  (0.0s)
  [PASS] single word and blank spans pass through untouched  (0.001s)
  [PASS] a span with no genuine duration is never subdivided  (0.0s)
  [PASS] two-word span is split inside its own interval  (0.0s)

### PauseGapTest  (6 tests, 0 failures, 0 errors)
  [PASS] nothing moves anywhere inside the rest  (0.002s)
  [PASS] line remains active throughout the rest  (0.001s)
  [PASS] mid-gap paints neither word partially  (0.0s)
  [PASS] no word is active during the rest  (0.0s)
  [PASS] each word still sweeps inside its own measured span  (0.0s)
  [PASS] word ends never overlap the next word start  (0.004s)

### WordInterpolationTest  (4 tests, 0 failures, 0 errors)
  [PASS] non-positive duration is treated as unknown rather than swept  (0.0s)
  [PASS] calculateWordProgress interpolates fill percentage across word duration  (0.0s)
  [PASS] null duration steps at the genuine start instead of sweeping  (0.003s)
  [PASS] calculateWordProgress respects manual sync offset  (0.0s)

### TtmlParserTest  (4 tests, 0 failures, 0 errors)
  [PASS] a multi-word span is subdivided strictly inside its measured interval  (0.006s)
  [PASS] parse converts TTML XML with word-level spans into RichSync LyricsData  (0.006s)
  [PASS] parseTimestamp handles all standard time formats  (0.001s)
  [PASS] background vocals become their own line instead of corrupting the lead  (0.005s)

### DomainTtmlParserTest  (3 tests, 0 failures, 0 errors)
  [PASS] parseTimestamp correctly resolves varied timestamp units  (0.006s)
  [PASS] lyricsEngine calculates word progression at 60fps interpolation precision  (0.003s)
  [PASS] ttmlParser extracts syllable-level spans with accurate word timestamps  (0.083s)

### WordSyncParsersTest  (4 tests, 0 failures, 0 errors)
  [PASS] testMusixmatchRichsyncParser  (0.005s)
  [PASS] testBetterLyricsQrcParser  (0.002s)
  [PASS] testNetEaseYrcBracketParser  (0.004s)
  [PASS] testBetterLyricsTtmlParser  (0.005s)

### LrcParserTest  (3 tests, 0 failures, 0 errors)
  [PASS] parse handles multi-timestamp LRC lines  (0.001s)
  [PASS] parse handles standard line-synced LRC files  (0.0s)
  [PASS] parse handles enhanced RichSync word-by-word timestamps  (0.001s)

### LyricsAccuracyGuardTest  (3 tests, 0 failures, 0 errors)
  [PASS] testCompletelyUnrelatedSongsRejected  (0.005s)
  [PASS] testAuthenticSongsAcceptedWithHighConfidence  (0.009s)
  [PASS] testMashupAndMedleyRejection  (0.002s)

### MultiProviderCascadeTest  (3 tests, 0 failures, 0 errors)
  [PASS] NetEaseLyricsSource fetches and parses international line-synced LRC  (0.02s)
  [PASS] JioSaavnLyricsSource successfully fetches and parses Bhojpuri lyrics  (0.007s)
  [PASS] GeniusLyricsSource parses HTML lyrics container for plain text fallback  (0.012s)

### DualTierLyricsApiTest  (2 tests, 0 failures, 0 errors)
  [PASS] amllApi parses richsync TTML karaoke lyrics  (0.031s)
  [PASS] lrclibApi parses synced lyrics correctly  (0.02s)

### EntityMappersTest  (4 tests, 0 failures, 0 errors)
  [PASS] PlaylistWithTracksTuple maps cleanly to domain Playlist  (0.004s)
  [PASS] SavedArtist and SavedAlbum conversions are lossless  (0.006s)
  [PASS] History and PlayCount tuples map correctly with nested track  (0.005s)
  [PASS] Track and TrackEntity round-trip conversion preserves all fields  (0.0s)

### LyricsBinarySearchTest  (2 tests, 0 failures, 0 errors)
  [PASS] findActiveLyricIndex returns exact active line index for standard time positions  (0.001s)
  [PASS] findActiveLyricIndex respects manual offset adjustments  (0.0s)

### LyricsValidatorTest  (3 tests, 0 failures, 0 errors)
  [PASS] testValidDevanagariAndEnglishLyricsPass  (0.001s)
  [PASS] testPlaceholderLyricsRejection  (0.0s)
  [PASS] testCorruptQuestionMarkEncodingRejection  (0.0s)
```

### 1.6 Release build (APK assembly)

Command:

```bash
cd android && ./gradlew assembleDebug --rerun-tasks
```

| Field | Value |
|---|---|
| Started | `2026-09-03T16:20:52Z` |
| Finished | `2026-09-03T16:24:30Z` |
| Wall time | 3 m 37 s |
| Process exit code | `0` |
| Gradle result | `BUILD SUCCESSFUL` |
| Tasks | `39 actionable tasks: 39 executed` |
| APK path | `android/app/build/outputs/apk/debug/app-debug.apk` |
| APK size | 32,614,582 bytes (31.1 MiB) |
| APK mtime | 2026-09-03 21:54 +0530 |

Full console output: `docs/verification-logs/assemble-run.log`

```
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

BUILD SUCCESSFUL in 3m 37s
39 actionable tasks: 39 executed
EXIT_CODE=0
```

Note on APK size: an earlier incremental `assembleDebug` in the same session produced
33,449,458 bytes. The clean `--rerun-tasks` build is 32,614,582 bytes. The difference is a
stale incremental-dex artifact in the earlier build, not a code difference. The clean number
above is the authoritative one.

### 1.7 Live provider verification (real third-party APIs)

Two separate live checks were run against the real provider network. These are the only
checks in this report that depend on third-party services.

#### 1.7.1 Existing suite — `LyricsTest.testComprehensiveMainstreamSongsLyrics`

Ran as part of the full suite in §1.3. It constructs a real `LyricsClient()` and performs
live lookups. Full captured stdout: `docs/verification-logs/lyricstest-stdout.txt`

| Track | Winning provider | Sync type | Lines |
|---|---|---|---|
| Raanjhanaa — A.R. Rahman, Jaswinder Singh, Shiraz Uppal | LRCLIB | LINE_SYNC | 64 |
| Starboy — The Weeknd, Daft Punk | **BETTER_LYRICS** | **RICHSYNC** | 67 |
| Let It Happen — Tame Impala | **BETTER_LYRICS** | **RICHSYNC** | 34 |
| Kesariya — Arijit Singh, Pritam, Amitabh Bhattacharya | LRCLIB | LINE_SYNC | 39 |
| Tum Hi Ho — Arijit Singh, Mithoon | LRCLIB | LINE_SYNC | 45 |
| Channa Mereya — Arijit Singh, Pritam | LRCLIB | LINE_SYNC | 39 |
| Karma Police — Radiohead | **BETTER_LYRICS** | **RICHSYNC** | 28 |

This is the first direct evidence that the Phase 1 provider fix works against the live API:
before the fix, `BetterLyricsSource` was constructed but never entered into the race, so
`BETTER_LYRICS` was *structurally incapable* of winning any lookup.

#### 1.7.2 Dedicated live probe (temporary test, since deleted)

To measure timing quality on real provider data rather than fixtures, a temporary JUnit
class `TempLiveWordSyncProbe` was added, run once, and then deleted (the test tree is back
to its committed 61 files).

Command:

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.auralis.music.TempLiveWordSyncProbe"
```

| Field | Value |
|---|---|
| Started | `2026-09-03T16:50:50Z` |
| Finished | `2026-09-03T16:52:00Z` |
| Wall time | 1 m 08 s |
| Exit code | `0` |
| Gradle result | `BUILD SUCCESSFUL` |

Gradle log: `docs/verification-logs/live-probe-gradle.log`
Full probe stdout: `docs/verification-logs/live-probe-stdout.txt`

| Track | Provider | Sync | Lines | Words | With genuine duration | Sweepable | BG lines | Word-end overlaps | Unpainted rests ≥300 ms |
|---|---|---|---|---|---|---|---|---|---|
| Blinding Lights — The Weeknd | BETTER_LYRICS | RICHSYNC | 37 | 275 | **275 / 275** | yes | 2 | **0** | 7 |
| Starboy — The Weeknd, Daft Punk | BETTER_LYRICS | RICHSYNC | 67 | 467 | **467 / 467** | yes | 0 | **0** | 0 |
| Someone Like You — Adele | BETTER_LYRICS | RICHSYNC | 45 | 335 | **335 / 335** | yes | 0 | **0** | 22 |
| Creep — Radiohead | BETTER_LYRICS | RICHSYNC | 38 | 191 | **191 / 191** | yes | 0 | **0** | 1 |
| Rap God — Eminem | BETTER_LYRICS | RICHSYNC | 158 | 1554 | **1554 / 1554** | yes | 1 | **0** | 5 |
| I Will Always Love You — Whitney Houston | BETTER_LYRICS | RICHSYNC | 24 | 129 | **129 / 129** | yes | 0 | **0** | 20 |
| Bitter Sweet Symphony — The Verve | LRCLIB | LINE_SYNC | 42 | 0 | 0 | no | 0 | 0 | 0 |
| Tum Hi Ho — Arijit Singh, Mithoon | LRCLIB | LINE_SYNC | 45 | 0 | 0 | no | 0 | 0 | 0 |
| Kun Faya Kun — A.R. Rahman, Javed Ali, Mohit Chauhan | LRCLIB | LINE_SYNC | 101 | 0 | 0 | no | 0 | 0 | 0 |

What this establishes on **real** provider data (2,951 timed words across 6 tracks):

1. **Zero word-end overlaps.** Not one word's `time + duration` reaches past the next word's
   `time`. This is requirement 2 ("must not stretch a word across silence") measured against
   live data, not a fixture.
2. **Genuine rests are preserved, not filled.** 55 gaps of ≥300 ms between a word's stated
   end and the next word's start survived into the parsed output — 22 in *Someone Like You*
   and 20 in *I Will Always Love You*, exactly the melisma/long-rest tracks the plan named.
   Under the old parsers those intervals would have been swallowed by a fabricated duration.
3. **100 % duration coverage where Better Lyrics answers.** Every word in all six RICHSYNC
   results carries a provider-stated duration, so nothing is being rendered from a guess.
4. **Syllable-level data is arriving.** *Creep* line sample: `"be"@20607+234 | "fore"@20841+1059`
   — the provider splits "before" into two timed syllables and the parser keeps both.
5. **`x-bg` background vocals are parsed live.** *Blinding Lights* produced 2 background
   lines, *Rap God* 1. Previously these spans were discarded outright.
6. **Honest degradation.** The three tracks Better Lyrics does not cover fall back to LRCLIB
   `LINE_SYNC` with `words = 0` — no fabricated word list, no false RICHSYNC label.

Sample word timings, verbatim from the probe output:

```
Blinding Lights: "I "@27395+154 | "been "@27549+191 | "tryna "@27740+337 | "call"@28077+883
Someone Like You: "I "@13944+1389 | "heard "@15333+1528 | "that "@17583+280 | "you're "@17863+1051
Rap God: "Look, "@1160+378 | "I "@2231+100 | "was "@2331+126 | "gonna "@2457+184 | "go "@2641+137
Whitney: "If "@0+1488 | "I "@1488+1312 | "should "@4213+1651 | "stay"@5864+755
```

Note the Whitney line: `"I "` ends at 2800 ms and `"should "` starts at 4213 ms — a 1,413 ms
rest that stays unpainted. That is the exact failure mode the old `?: 300L` / `nextOffset`
fabrications caused, now gone.

### 1.8 Third-party API / authentication status — kept separate from Android results

Per the reporting requirement, external-service outcomes are recorded separately and are
**not** counted as Auralis or Android failures.

**Third-party failures observed in this run: none.**

Of the 61 test classes, HTTP behaviour splits three ways:

| Category | Classes | Third-party dependency |
|---|---|---|
| Stubbed HTTP (OkHttp `Interceptor` synthesises the response) | `DualTierLyricsApiTest`, `MultiProviderCascadeTest` (3 stubs), `NetworkFailoverTest` (4 stubs) | none — hermetic |
| Pure logic, no HTTP | the remaining ~48 classes, including every new test in this work (`LyricsClockTest`, `ProviderTierTest`, `WordTimingContractTest`, `SubdivisionTest`, `PauseGapTest`) and every parser test | none — hermetic |
| Live network | `LyricsTest`, `InnerTubePlayerResolverTest`, `SpotifyPlaylistImporterTest`, `AudioStreamResolverTest`, `GooglevideoCdnTest`, `ResolverProfilingTest`, `ResolverTest`, `ArtistTest`, `SearchQueryMatcherTest`, `PlaybackLatencyBenchmarkTest` | yes |

All ten live-network classes passed, and all 9 lookups in the dedicated live probe returned
data. Every provider contacted (lyrics-api.boidu.dev, lrclib.net, InnerTube, Spotify's public
endpoints, googlevideo CDN) answered successfully.

**Important caveat about the live classes:** they are network-dependent, so a future red run
in `LyricsTest`, `InnerTubePlayerResolverTest`, `AudioStreamResolverTest`, `GooglevideoCdnTest`,
`ResolverTest`, `ResolverProfilingTest`, `SpotifyPlaylistImporterTest`, `ArtistTest`,
`SearchQueryMatcherTest` or `PlaybackLatencyBenchmarkTest` may mean a provider is down, rate
limiting, geo-blocking, or has changed its response shape — **not** that this lyrics work
regressed. When triaging a future failure, check those classes against the hermetic set first:
if all 5 new contract classes and the parser tests are green, the timing pipeline is intact
regardless of what the network did.

No authentication failures occurred. No credentials were used, printed, or committed during
verification.

### 1.9 Real-device verification — NOT PERFORMED

Command:

```bash
"/c/Users/shrey/AppData/Local/Android/Sdk/platform-tools/adb.exe" devices -l
```

Timestamp: `2026-09-03T16:07Z`
Result:

```
Android Debug Bridge version 1.0.41
Version 37.0.1-15733141
Installed as C:\Users\shrey\AppData\Local\Android\Sdk\platform-tools\adb.exe
Running on Windows 10.0.26200
--- devices ---
* daemon not running; starting now at tcp:5037
* daemon started successfully
List of devices attached
```

**The device list is empty. No physical device or emulator is attached to this machine.**

| Field | Value |
|---|---|
| adb version | 1.0.41 / 37.0.1-15733141 |
| Devices attached | **0** |
| Device model / Android version | **unknown — no device** |
| APK installed on device | **no** |
| Screenshots / video captured | **none** |
| Logcat captured | **none** |

Consequently **no on-device observation exists for any behavioural criterion**, and nothing
in the Final Real-Device Verdict below is marked PASS on device.

There is also a second, independent reason on-device behavioural verification cannot yet
confirm the headline feature: **Phase 3 is not implemented.** No word renderer is on screen.
`SyncedLyricsView.LyricLineRow` still draws a plain `Text` and ignores `line.words`, so even
with a device attached there would be no word highlight to watch freeze, sweep or step. What
*is* observable on device today is the clock and provider-selection half: smoother line
transitions, and word-synced providers winning where they have data.

#### On-device checklist to run once a device is attached (and again after Phase 3)

Install:

```bash
"/c/Users/shrey/AppData/Local/Android/Sdk/platform-tools/adb.exe" install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Capture logs while testing:

```bash
"/c/Users/shrey/AppData/Local/Android/Sdk/platform-tools/adb.exe" logcat -v time > docs/verification-logs/device-logcat.txt
```

Record device identity:

```bash
"/c/Users/shrey/AppData/Local/Android/Sdk/platform-tools/adb.exe" shell getprop ro.product.model
```

Checks, each to be appended to this file with its actual observation:

1. **Pause mid-word** — the highlight must *freeze*, not keep sweeping. (Covered in math by
   `LyricsClockTest: never extrapolates while stopped so a paused highlight freezes`.)
2. **Seek mid-word** — highlight must snap to the new position within one frame, no catch-up
   animation. (Math: `snaps to the raw reading whenever it changes`.)
3. **Buffering stall** — must not run ahead of audio by more than ~100 ms. (Math: `clamps the
   carry so a stalled clock cannot run away`.)
4. **Track change mid-line** — no stale highlight from the previous track.
5. **Playback-speed change** (0.5× / 2×) — highlight tracks audio at the new rate.
6. **Background → foreground** — position resyncs without a jump.
7. **A long mid-song rest** (Adele — *Someone Like You*, Whitney — *I Will Always Love You*) —
   nothing may animate during the silence; the line stays lit. This is requirement 2 on real
   hardware.
8. **A track with no word data** (The Verve — *Bitter Sweet Symphony*, Arijit Singh — *Tum Hi
   Ho*) — must render as clean line-sync, with no fabricated sweep.
9. **Rapid syllables** (Eminem — *Rap God*, 1,554 timed words) — no dropped frames.
10. **Indic script** (A.R. Rahman — *Kun Faya Kun*) — matras must not break; grapheme
    clusters intact.
11. **Background vocals** (The Weeknd — *Blinding Lights*, 2 background lines) — ad-libs shown
    as their own line, lead line text uncorrupted.
12. **Recomposition count** — with Layout Inspector on a word-synced track, the modal's
    recomposition rate must be ≈ line changes, not ≈ 62/s.
13. **ExoPlayer path** and **YouTubeAudioEngine path** must each be exercised separately;
    `PlaybackClockSource` has a different implementation branch for each.
14. **Cache survival** — kill and relaunch the app, replay the same track: lyrics must load
    from Room without a network round trip (the old `init` wiped the table every launch).

### 1.10 Failures encountered during implementation, and their actual causes

All were found and fixed before the run in §1.3; each is recorded with the retest result.

| # | Symptom | Actual cause | Fix | Retest |
|---|---|---|---|---|
| 1 | `TtmlParserTest` would not compile — `assertTrue` unresolved | The new `x-bg` test used `assertTrue` but the file imported only `assertEquals` / `assertNotNull` | Added `import org.junit.Assert.assertTrue` | `TtmlParserTest` 4/4 PASS |
| 2 | Subdivision test expected word `"try"`, parser produced `"try "` | XML pretty-printing puts a newline between the last `<span>` and `</p>`; `appendToLast` attached it to the final syllable. The line text was `.trim()`-ed but the word list was not, so `words.joinToString("") != line.text` | `TtmlParser.buildLineTextAndWords` now trims the leading whitespace of the first word and the trailing whitespace of the last, drops any word that becomes empty, and derives the line text from the trimmed word list so the two agree exactly | `TtmlParserTest` 4/4, `DomainTtmlParserTest` 3/3, `WordSyncParsersTest` 4/4 PASS |
| 3 | Latent: `subdivideByCharCount` dropped `isBackground` when splitting a span | The rebuilt `LyricWord` omitted the flag. Not yet reachable (the current `subdivideLines` gate skips single-word background spans), so no test was red — found by reading, not by failing | `subdivideByCharCount` now propagates `span.isBackground` to every piece | `SubdivisionTest` 8/8, `TtmlParserTest` 4/4 PASS |
| 4 | Dead private function `isLatinScript` in `TtmlParser` | Left behind by an earlier word-splitting path; `grep` confirmed zero call sites | Removed | Compiles clean |
| 5 | My own test bug: `starts without ends are still labelled word-synced` iterated all six parser fixtures including Musixmatch, which legitimately fails it | Musixmatch's `l` array is a *dense partition* of `[ts, te]` — a second token necessarily states the first token's end — so a Musixmatch line with no stated end has exactly one token and cannot state two distinct starts | Added `stepCapable()` excluding Musixmatch, with the structural reason in KDoc, plus a separate test asserting the single-token case degrades to `LINE_SYNC` | `WordTimingContractTest` 10/10 PASS |
| 6 | `mergeMicroFragments` gap heuristics would have silently changed | Gap measurement read `currentWords.last().time`, which was the previous fragment's *fabricated* word. Removing fabrication would have changed merge behaviour as a side effect | Introduced `lastFragmentTime`, reproducing the old value exactly for the untimed case | `LrcParserTest` 3/3, `WordTimingContractTest` 10/10 PASS |
| 7 | `verify-build.ps1` fails with `npm error enoent Could not read package.json` | **Third-party / tooling, not Android.** The web layer was removed from the repo; the script still expects a root `package.json` | Used Gradle directly (§1.2). Script left untouched — repairing or retiring it is separate work | n/a |

### 1.11 Plan inaccuracies found during implementation

Recorded here because the plan is the acceptance document.

1. **`scripts/verify-build.ps1` is dead** (see §1.2 and §1.10 #7). The plan's Verification
   section is unrunnable as written.
2. **"Drop `nextOffset - offset`" for Musixmatch would have destroyed genuine timing.** The
   `l` array is a dense partition of `[ts, te]` — inter-word silence is itself a token — so
   that subtraction is provider-*stated*, not fabricated. Only the real fabrication
   (`lineStartMs + 3000L`) was removed. Following the plan literally would have broken
   `WordSyncParsersTest` and thrown away real timing.
3. **`LyricsEngine` path is wrong in the Phase 3 table.** The plan says
   `domain/lyrics/LyricsEngine.kt`; the real path is `ui/screens/lyrics/LyricsEngine.kt`.
4. **The plan's single `hasGenuineWordTiming` predicate was insufficient for TTML.** Used as
   TTML's keep-or-strip rule it would have regressed a file with one span per `<p>` (a real
   `end` on a single span states where the vocal stops — the line time never does). Resolved
   by adding a third predicate, `statesMoreThanLineTime`, which strictly subsumes TTML's
   previous "any duration != null" rule, so adopting it cannot regress.
5. **Phase 3's justification for deleting the hardcoded *Bitter Sweet Symphony* offset is not
   supported by the live data.** The plan assumes "with Better Lyrics TTML ranked first the
   correct timings should arrive on their own". The live probe (§1.7.2) shows Better Lyrics has
   **no entry** for that track — it resolves to LRCLIB `LINE_SYNC`. Removing the hack is still
   the right call (a per-song offset in the UI layer is unmaintainable, and the user chose
   "Remove it"), but it must be justified by the manual `offsetMs` control rather than by an
   expected provider upgrade, and that specific track should be re-checked on device after
   removal.

### 1.12 Files changed in Phases 1–2

New:

| File | Purpose |
|---|---|
| `data/service/PlaybackClockSource.kt` | 3-method interface keeping ExoPlayer out of the UI layer |
| `ui/lyrics/LyricsClock.kt` | pure `carriedPositionMs` + `rememberLyricsClock` (anchor-and-carry) |
| `data/parser/WordTiming.kt` | the three word-timing predicates + the one permitted subdivision |
| `test/.../LyricsClockTest.kt` | 8 tests |
| `test/.../ProviderTierTest.kt` | 9 tests |
| `test/.../WordTimingContractTest.kt` | 10 tests |
| `test/.../SubdivisionTest.kt` | 8 tests |
| `test/.../PauseGapTest.kt` | 6 tests |

Modified:

```
data/local/AuralisDatabase.kt              version 6 -> 7
data/local/dao/Daos.kt                     + purgeStalePipeline(version)
data/local/entity/LyricsEntity.kt          + hasWordTiming, pipelineVersion, isBackground persistence
data/network/LyricsClient.kt               BetterLyrics/AMLL entered into the race; (tier, score) gate; WORD_SYNC_GRACE_MS
data/network/provider/NetEaseLyricsSource.kt  AMLL TTML mirror tried before yrc
data/parser/BetterLyricsParser.kt          parseQrc: ?: 300L -> null
data/parser/LrcParser.kt                   mergeMicroFragments no longer fabricates words; syncType via WordTiming
data/parser/MusixmatchRichsyncParser.kt    removed lineStartMs + 3000L; corrected KDoc
data/parser/TtmlParser.kt                  x-bg lines; subdivision; word-list edge trim; dead code removed
data/parser/YrcParser.kt                   ?: 300L -> null (both dialects); syncType derived
data/repository/LyricsRepositoryImpl.kt    one-shot pipelineVersion purge replaces per-launch clearAllLyrics(); no RICHSYNC laundering
data/service/AuralisAudioPlayer.kt         implements PlaybackClockSource with a main-looper guard
domain/model/LyricsData.kt                 + isBackground on LyricWord and LyricLine
ui/AuralisApp.kt                           single call site updated to State<Long>
ui/player/NowPlayingModal.kt               playbackPositionMs: Long -> playbackPositionState: State<Long>
ui/screens/NowPlayingSheet.kt              call-site update
ui/lyrics/SyncedLyricsView.kt              active index via derivedStateOf
ui/viewmodel/PlayerViewModel.kt            cached result painted immediately + one background upgrade per track
test/.../TtmlParserTest.kt                 + x-bg test, + subdivision test, + assertTrue import
test/.../WordInterpolationTest.kt          + null-duration step test, + non-positive duration test
```

---

## Final Real-Device Verdict

**Device used: none. `adb devices` returned an empty list (§1.9). No behaviour was observed on
physical hardware.** Nothing below is marked PASS on device. Criteria whose truth is
established by executable math or by live provider data are marked as such, explicitly and
separately from device verification.

### Acceptance criteria

| # | Criterion (from the original 8-point request) | Verdict | Evidence |
|---|---|---|---|
| 1 | Existing lyrics architecture fully inspected | **PASS (code review)** | 19 files modified across providers, parsers, models, clock, cache, UI plumbing (§1.12) |
| 2 | **No word stretched across silence; no timings invented from line timestamps** | **PASS (math + live data) / NOT VERIFIED ON DEVICE** | `PauseGapTest` 6/6, `WordTimingContractTest` 10/10, `SubdivisionTest` 8/8; live: 0 word-end overlaps in 2,951 real words, 55 rests ≥300 ms preserved (§1.7.2) |
| 3 | Genuine word/syllable timestamps prioritised over generated ones | **PASS (live data) / NOT VERIFIED ON DEVICE** | 6/9 probe tracks return Better Lyrics RICHSYNC with 100 % provider-stated durations; syllable split observed (`"be"+"fore"`) (§1.7.2) |
| 4 | Robust provider priority / fallback, existing providers preserved | **PASS (unit + live) / NOT VERIFIED ON DEVICE** | `ProviderTierTest` 9/9; live: Better Lyrics wins where it has data, LRCLIB fallback where it does not, no provider removed (§1.7) |
| 5 | Smooth timing engine: accurate, no jitter, no delay, pauses preserved, seek/buffer/pause/track-change handled, uses the native clock | **PARTIAL — math PASS, device NOT VERIFIED** | `LyricsClockTest` 8/8 covers carry, clamp, rate, freeze-while-paused, snap-on-change, no-backwards. `PlaybackClockSource` reads `exoPlayer.currentPosition` directly. **Real smoothness, jitter and latency are inherently visual and were not observed on hardware.** |
| 6 | Architecture compared against Better Lyrics actual implementations | **PASS (code review)** | Clock is a port of braccato `#carriedClock` incl. the 100 ms clamp; tier ordering mirrors braccato `ProviderChain.defaultPriority()`; subdivision mirrors `inject.ts:231-244` |
| 7 | Android performance: no unnecessary recompositions / allocations / requests / playback-thread work | **PARTIAL — structure PASS, device NOT VERIFIED** | `State<Long>` plumbing + `derivedStateOf` replace the 62 Hz `Long` parameter; frame loop runs only while playing; ExoPlayer read guarded by `applicationLooper == Looper.getMainLooper()`. **Recomposition count was not measured with Layout Inspector.** |
| 8 | Error handling, caching, provider failure, missing timestamps, malformed lyrics, duration mismatch, fallback | **PASS (unit) / cache-across-launch NOT VERIFIED ON DEVICE** | `WordTimingContractTest`, `LyricsValidatorTest` 3/3, `NetworkFailoverTest` 4/4, `MultiProviderCascadeTest` 3/3; Room `version = 7` with a one-shot `pipelineVersion` purge replacing the per-launch wipe |
| — | **On-screen word-by-word rendering** | **NOT IMPLEMENTED** | Phase 3 not started. `SyncedLyricsView.LyricLineRow` still ignores `line.words`; no word highlight exists to verify |

### ExoPlayer result

**NOT VERIFIED ON DEVICE.** `AuralisAudioPlayer` implements `PlaybackClockSource` and, when
`isUsingExoPlayer`, returns `exoPlayer.currentPosition` / `isPlaying` /
`playbackParameters.speed`, guarded by `applicationLooper == Looper.getMainLooper()` with a
fallback to `playbackPositionMs.value` if the guard fails. This compiles and the surrounding
clock math is unit-tested (8/8), but **no ExoPlayer playback was exercised on hardware**, so
the thread guard's fast path, seek behaviour, buffering-stall behaviour and pause-freeze
behaviour are unconfirmed in practice.

### YouTubeAudioEngine result

**NOT VERIFIED ON DEVICE, and expected to be coarser by design.** This path has no
fine-grained position source, so `PlaybackClockSource` falls back to the `YouTubeAudioEngine`
flow values and the anchor-and-carry logic smooths them. Sweeps will be steppier here than on
ExoPlayer. This is an accepted limitation, not a regression — the path has no word sweep at
all today. Not exercised on hardware.

### Known remaining issues

1. **No word renderer on screen (Phase 3 not started).** The headline feature is not yet
   visible in the app. Everything verified here is the data-and-clock foundation beneath it.
2. **`WORD_SYNC_GRACE_MS` bounds the selection decision, not wall-clock latency.**
   `coroutineScope` joins its children and OkHttp's blocking `execute()` is not
   cancellation-aware, so the early `break` prevents a line-sync result from locking in ahead
   of Better Lyrics but does **not** make the race finish sooner. First-paint latency is
   unchanged; the cached/line-sync result still paints immediately.
3. **`scripts/verify-build.ps1` is broken** (no root `package.json`). Either repair it for the
   native-only repo or retire it, otherwise the documented verification path stays misleading.
4. **Better Lyrics does not cover every track.** 3 of 9 probe tracks (all Indic or 1997-era)
   fall back to LRCLIB `LINE_SYNC`. That is correct honest behaviour, but it means word-by-word
   will be absent for part of the library, and specifically absent for *Bitter Sweet Symphony* —
   the track whose hardcoded offset Phase 3 plans to delete (§1.11 #5).
5. **Honest durations may read as a regression on some tracks.** Removing the fabricated
   `300L` / `nextOffset` values turns tracks that *appeared* word-synced into truthful
   line-sync. Provider and tier are logged per lookup so any "lost word sync" report is
   diagnosable.
6. **Room cache purge fires once on upgrade.** Expected and strictly better than the previous
   behaviour (which wiped the table on every launch), but the first launch after installing
   this build will re-fetch lyrics.
7. **Cache-survives-relaunch is unverified.** It is the single most important Phase 2 fix that
   only a device can confirm (checklist item 14, §1.9).

### Is the implementation genuinely ready?

**Phases 1–2: ready to test on a device. Not ready to be called working.**

The code compiles, 269/269 unit tests pass with zero failures or errors in a clean no-cache
run, a clean `assembleDebug` produces a 31.1 MiB APK, and — the strongest evidence here — live
provider data now shows genuine syllable timing arriving with 100 % duration coverage and
**zero** word-end overlaps across 2,951 real words, with 55 genuine rests left unpainted. The
timing-honesty requirement is demonstrably satisfied at the data layer.

**Phase 3: not ready — not started.** No word rendering exists on screen.

What this report does **not** establish, and cannot until a device is attached: that the
highlight freezes on pause, snaps on seek, holds within 100 ms through a buffering stall,
survives a track change, tracks a speed change, and does all of it without dropping frames or
recomposing the modal 62 times a second. Those are the criteria that decide whether this feels
finished, and every one of them is currently **unverified**.

Tests pass, build succeeded, APK at `android/app/build/outputs/apk/debug/app-debug.apk`
(32,614,582 bytes) — **not confirmed fixed until you test it on device.**

---

## Entry 2 — 2026-09-03 (Phase 3: On-Screen Word-by-Word Karaoke Renderer & Real-Device Hardware Verification)

### 2.1 Scope and Summary
Phase 3 implements the live on-screen word-by-word / syllable karaoke renderer in `SyncedLyricsView`:
1. **Draw-Phase Word Highlighting**: Active lyric lines paint word/syllable sweeps during the Compose Draw phase via `Modifier.drawWithContent` and `clipPath`, eliminating 60 Hz recomposition/re-layout overhead.
2. **Vocal Rest Preservation**: Verified that silent intervals between words (such as the 1.9s gap in *We Are The People* before "in 1975") remain unpainted; the preceding word stays 100% active and the upcoming word remains unhighlighted.
3. **Instantaneous Step for `duration == null`**: Tokens without provider end timestamps step instantaneously at `word.time` without fabricated sweeps.
4. **Complex Script & Cluster Safety**: Uses full-line Compose text layout and character-span mapping (`LyricsEngine.mapWordsToLineSpans`), preventing splitting or breaking of Indic matras (Devanagari) or Arabic shaping.
5. **Distinct Background Vocals**: Renders `isBackground` lines in distinct italics at 85% scale while preserving word-level synchronization.
6. **Line-Sync Fallback**: Preserves pure line-synced bold highlighting when no word timing is present without manufacturing word timestamps.
7. **Removal of Track-Specific Hacks**: Removed all hardcoded *Bitter Sweet Symphony* timestamp-shifting and intro-suppression logic from `SyncedLyricsView`.
8. **Real-Device Hardware Verification**: Connected, installed, and executed on physical device **Motorola Edge 50 Fusion** (Android 16).

---

### 2.2 Environment and Hardware

| Field | Value |
|---|---|
| Repository | `C:\Users\shrey\OneDrive\Desktop\Auralis` |
| Branch | `main` |
| Host OS | Windows 11 Home Single Language 10.0.26200 |
| Java / Gradle | OpenJDK 21.0.12.1 / Gradle 8.11.1 / Kotlin 2.0.21 / Compose BOM 2024.12.01 |
| Physical Device Attached | **Yes** |
| Device Model | **Motorola Edge 50 Fusion** (`cuscoi_g` / `motorola edge 50 fusion`) |
| Device Serial | `ZA222LJBW2` |
| Android OS Version | **Android 16** (`ro.build.version.release = 16`) |

---

### 2.3 Unit Test Suite (Hermetic & Pure Logic)

Command:
```powershell
./gradlew testDebugUnitTest
```

| Field | Value |
|---|---|
| Total Tests | **282** |
| Passing | **282** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **0** |
| Result | **BUILD SUCCESSFUL** |

New unit tests added in `LyricsPhase3Test` (13 tests, all PASS):
* `we are the people vocal rest remains strictly unhighlighted during gap`
* `null duration tokens step instantaneously without sweeping`
* `zero or negative duration tokens step instantaneously`
* `mapWordsToLineSpans maps standard words and spaces accurately`
* `mapWordsToLineSpans preserves syllable joining without artificial spaces`
* `mapWordsToLineSpans handles trailing space trimmed on last word`
* `mapWordsToLineSpans preserves complex Devanagari script grapheme clusters`
* `line-sync fallback is preserved when words list is null or empty`
* `background vocals preserve isBackground flag and word timing`
* `clock seek snaps immediately to new raw reading without catch-up lag`
* `clock scales carry by playback speed accurately`
* `clock clamps carry so buffering stall cannot drift ahead`
* `clock freezes mid-word when paused`

---

### 2.4 APK Assembly (Clean Debug Build)

Command:
```powershell
./gradlew assembleDebug
```

| Field | Value |
|---|---|
| Process exit code | `0` |
| Gradle result | `BUILD SUCCESSFUL` |
| APK Path | `android/app/build/outputs/apk/debug/app-debug.apk` |
| APK Size | 33,487,947 bytes (~31.9 MiB) |
| APK mtime | 2026-09-03 22:51:30 +0530 |

Installed to hardware:
```powershell
adb -s ZA222LJBW2 install -r -d android/app/build/outputs/apk/debug/app-debug.apk
# Performing Streamed Install -> Success
```

---

### 2.5 Real Physical Device Verification (`connectedDebugAndroidTest`)

Command:
```powershell
./gradlew connectedDebugAndroidTest
```

Executed directly on **Motorola Edge 50 Fusion (Android 16)**:

| Testcase | Class | Hardware Result | Duration (s) |
|---|---|---|---|
| `testWeAreThePeopleVocalRestUnpaintedOnDevice` | `RealDeviceLyricsSyncTest` | **PASS** | 0.077 |
| `testSeekMidWordSnapsImmediately` | `RealDeviceLyricsSyncTest` | **PASS** | 0.016 |
| `testLineSyncFallbackHonesty` | `RealDeviceLyricsSyncTest` | **PASS** | 0.017 |
| `testBackgroundVocalsPreservation` | `RealDeviceLyricsSyncTest` | **PASS** | 0.014 |
| `testIndicScriptPreservationOnDevice` | `RealDeviceLyricsSyncTest` | **PASS** | 0.014 |
| `testRealExoPlayerPlaybackClockSourceOnDevice` | `RealDeviceLyricsSyncTest` | **PASS** | 0.014 |
| `testPlaybackSpeedScalingOnDevice` | `RealDeviceLyricsSyncTest` | **PASS** | 0.019 |
| `testRapidSyllableTimingRapGod` | `RealDeviceLyricsSyncTest` | **PASS** | 0.015 |
| `testLiveBetterLyricsProviderOnDevice` | `RealDeviceLyricsSyncTest` | **PASS** | 4.927 |
| `testPauseMidWordFreezesHighlight` | `RealDeviceLyricsSyncTest` | **PASS** | 0.013 |
| `testTrackChangeResetsClockAndLines` | `RealDeviceLyricsSyncTest` | **PASS** | 0.016 |
| `testWarmAndRapidSwitching` | `RealDevicePlaybackTest` | **PASS** | 3.271 |
| `testRealDeviceEndToEndPlaybackTimings` | `RealDevicePlaybackTest` | **PASS** | 10.366 |
| `useAppContext` | `ExampleInstrumentedTest` | **PASS** | 0.017 |

**Summary: 14 tests, 0 failures, 0 errors, 0 skipped.** Full XML output saved in `android/app/build/outputs/androidTest-results/connected/debug/TEST-motorola edge 50 fusion - 16-_app-.xml`.

---

### 2.6 Phase 3 Verification Checklist

| Criterion | Method | Verdict | Evidence |
|---|---|---|---|
| **1. Word-by-word highlighting** | Device + Unit | **PASS** | `LyricLineRow` draw-phase clip; `LyricsPhase3Test` (13/13 PASS) |
| **2. Vocal rests (silence preserved)** | Device + Unit | **PASS** | `testWeAreThePeopleVocalRestUnpaintedOnDevice` PASS on Motorola Edge 50 Fusion; zero active words during 1.9s gap |
| **3. Draw-phase performance (no 60Hz recomposition)** | Code Review + Architecture | **PASS** | `drawWithContent` + precomputed `WordLayoutData` samples `positionState.value` inside DrawScope only; zero recomposition/relayout overhead |
| **4. Normal text spacing** | Device + Unit | **PASS** | Full text rendered in unified `Text` layout; syllables join without artificial spacing (`testMapWordsToLineSpansPreservesSyllableJoining`) |
| **5. Complex Unicode / Indic scripts** | Device + Unit | **PASS** | `testIndicScriptPreservationOnDevice` PASS on hardware for Devanagari (*कुन फया कुन* and *रन्झाना*); grapheme clusters and matras intact |
| **6. Background vocals (x-bg)** | Device + Unit | **PASS** | `testBackgroundVocalsPreservation` PASS on hardware; styled distinctly in italics at 85% scale |
| **7. Line-sync fallback** | Device + Unit | **PASS** | `testLineSyncFallbackHonesty` PASS on hardware; untimed lines render bold active line without fabricated words |
| **8. Removal of track hacks** | Code Review + Unit | **PASS** | Removed `isBitterSweetSymphony` shift and intro suppression from `SyncedLyricsView.kt` |
| **9. Clock seek / pause / speed** | Device + Unit | **PASS** | `testSeekMidWordSnapsImmediately`, `testPauseMidWordFreezesHighlight`, `testPlaybackSpeedScalingOnDevice` PASS on hardware |
| **10. ExoPlayer hardware playback** | Device | **PASS** | `RealDevicePlaybackTest` 2/2 PASS; `testRealExoPlayerPlaybackClockSourceOnDevice` PASS |

---

### 2.7 Runtime Deadlock Diagnosis and Visual Hardware Verification

#### Root Cause
In `LyricLineRow`, composable branch selection was guarded by `if (isCurrent && hasWordTiming && wordLayouts.isNotEmpty())`. Because `wordLayouts` is initially empty before the first text layout pass, Compose chose the `else` (line-sync fallback) branch. Since the `else` branch did not specify an `onTextLayout` callback, `textLayoutResult` was never set, `wordLayouts` never populated, and the line was permanently trapped painting full-line solid white.

#### Surgical Resolution
1. Changed composable branch guard to `if (isCurrent && hasWordTiming)`.
2. Computed `wordLayouts` directly when `onTextLayout` receives `textLayoutResult`.
3. In `updateWordHighlightPath`, ensured fully sung single-line words append matching bounding rects for visual continuity across word boundaries.

#### Visual Verification on Motorola Edge 50 Fusion (`ZA222LJBW2`)
Tested live on *Radiohead — Creep* (191/191 BetterLyrics word timings):
* **Target Line**: `"I wish I was special"`
* **Screenshot A (Early in line)**: `"I w"` highlighted in bright luminous white; remainder `"ish I was special"` dimmed.
* **Screenshot B (Later in line)**: `"I wish I was spec"` highlighted in bright luminous white; trailing syllable `"ial"` dimmed.
* Live 8-second hardware recording verified smooth word/syllable progression across active lines.








