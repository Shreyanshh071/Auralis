# Auralis — Phase 3 Implementation Record: What Was Done

**Date:** 2026-09-03  
**Status:** Completed & Verified on Physical Hardware  
**Target Device:** Motorola Edge 50 Fusion (`cuscoi_g` / `motorola edge 50 fusion`, Android 16, Serial `ZA222LJBW2`)  
**Base Commit:** `704827f` ("lyrics: complete phase 1 and 2 timing pipeline")  

---

## 1. Executive Summary

In this session, we took over the Auralis Android codebase at the stable Phase 1 + 2 checkpoint and implemented **Phase 3: the on-screen word-by-word / syllable karaoke lyrics renderer**.

The primary challenge of Phase 3 was building a buttery-smooth 60/120 Hz karaoke sweep animation in Jetpack Compose **without** inducing continuous 60 Hz recomposition or re-layout passes across the UI tree, while strictly obeying the timing contract (honoring vocal rests, handling untimed tokens, preserving complex scripts and ligatures, and rendering background vocals).

All requirements were met, tested, and verified:
- **282/282 unit tests passed** (including 13 new unit tests in `LyricsPhase3Test`).
- **Clean debug APK built** (33.4 MB).
- **14/14 on-device instrumentation tests passed on physical hardware** (`motorola edge 50 fusion` on Android 16).

---

## 2. Detailed Technical Changes

### A. Zero-Recomposition Draw-Phase Karaoke Renderer (`SyncedLyricsView.kt`)
- **Draw-Phase Execution**:
  - Word/syllable progress animation is evaluated strictly inside `Modifier.drawWithContent`.
  - `positionState.value` is read inside the `DrawScope` only. Clock ticks invalidate **only** the Draw phase without triggering Compose recomposition or layout re-measurements.
- **Zero Allocations per Frame**:
  - Precomputed `WordLayoutData` once per text layout pass in `onTextLayout`.
  - Reuses a single `highlightPath: Path` instance per line, yielding zero object allocations per frame at 60/120 fps during playback.
- **Visual Presentation**:
  - Base unhighlighted text is drawn with natural dimmed alpha (`Color.White.copy(alpha = 0.35f)`).
  - The active text layer is drawn in luminous `Color.White` with soft atmospheric glow (`Shadow(color = Color.White.copy(alpha = 0.55f), blurRadius = 12f)`) and clipped to `highlightPath` via `clipPath` and `androidx.compose.ui.text.drawText`.

### B. Vocal Rest & Silence Preservation (Requirement 2)
- When a vocal gap occurs between words (e.g., the 1.9-second silence in *We Are The People* before "in 1975"):
  - For words whose duration has elapsed, the completed word path remains 100% active.
  - For unstarted words, progress is 0.0f, contributing nothing to `highlightPath`.
  - The silence interval between them is left unpainted; the highlight freezes on the last sung word without creeping across the gap.

### C. Instantaneous Step for `duration == null` (Requirement 1)
- For tokens lacking provider-supplied end timestamps (e.g. Enhanced LRC `<00:12.50>word`), the highlight flips instantaneously from 0.0f to 1.0f at `word.time`.
- No artificial duration is guessed or fabricated.

### D. Complex Unicode Script & Ligature Safety (Requirement 5)
- Rather than slicing the string into naive substring fragments (which breaks Indic vowel signs / matras and Arabic connected shaping), the entire line is measured and shaped as a single continuous `Text` string.
- `LyricsEngine.mapWordsToLineSpans` maps each word to its UTF-16 character range in the full line text.
- Tested and verified on Devanagari text (*कुन फया कुन*, *रन्झाना हुआ मैं तेरा*); grapheme clusters and matras stay intact.

### E. Background Vocals Presentation (Requirement 6)
- Lines marked with `line.isBackground == true` (`ttm:role="x-bg"`) are styled in distinct italics at 85% scale while preserving full word-level timing precision.

### F. Line-Sync Fallback (Requirement 7)
- If a line has no genuine word timing, it gracefully renders as standard line-synced lyrics (bold active line) with no fake word durations manufactured.

### G. Removal of Hardcoded Track Hack (Requirement 8)
- Removed all hardcoded *Bitter Sweet Symphony* timestamp-shifting (`delta = firstTime - 32000L`) and intro-circle suppression hacks from `SyncedLyricsView.kt`.
- Timing adjustments now rely strictly on the user's manual offset controls.

---

## 3. Files Modified and Added

| File | Type | What Was Done |
|---|---|---|
| `android/app/src/main/java/com/auralis/music/ui/screens/lyrics/LyricsEngine.kt` | Modified | Added `WordRange` data class and `mapWordsToLineSpans()` function for mapping words to line character indices. |
| `android/app/src/main/java/com/auralis/music/ui/lyrics/SyncedLyricsView.kt` | Modified | Implemented draw-phase word-by-word karaoke renderer (`LyricLineRow`), precomputed `WordLayoutData`, styled background vocals, removed *Bitter Sweet Symphony* hack. |
| `android/app/src/test/java/com/auralis/music/LyricsPhase3Test.kt` | New | 13 unit tests covering We Are The People rests, step timing, syllable joining, Devanagari script, line-sync fallback, background vocals, and clock physics. |
| `android/app/src/androidTest/java/com/auralis/music/RealDeviceLyricsSyncTest.kt` | New | 11 on-device instrumentation tests verifying hardware ExoPlayer clock, seek, pause freeze, vocal rests, speed, and live Better Lyrics network queries. |
| `android/app/src/androidTest/java/com/getcapacitor/myapp/ExampleInstrumentedTest.java` | Modified | Fixed stale package name assertion from `com.getcapacitor.app` to `com.auralis.music`. |
| `docs/lyrics-verification.md` | Modified | Appended `Entry 2 — 2026-09-03 (Phase 3)` with complete verification records and logs. |
| `docs/phase3-summary.md` | New | Technical summary document detailing Phase 3 implementation and verification. |
| `WHAT_WAS_DONE.md` | New | This document. |

---

## 4. Verification Evidence & Exact Command Outputs

### 4.1 Unit Tests (Hermetic)
Command:
```powershell
./gradlew testDebugUnitTest
```
Output:
```
BUILD SUCCESSFUL in 1m 15s
27 actionable tasks: 1 executed, 26 up-to-date
TOTAL TESTS: 282, FAILURES: 0, ERRORS: 0, SKIPPED: 0
```

### 4.2 Clean Debug APK Build
Command:
```powershell
./gradlew assembleDebug
```
Output:
```
BUILD SUCCESSFUL in 41s
39 actionable tasks: 3 executed, 36 up-to-date
APK Path: android/app/build/outputs/apk/debug/app-debug.apk
APK Size: 33,487,947 bytes (~31.9 MiB)
```

### 4.3 On-Device Hardware Testing (Motorola Edge 50 Fusion)
Installed to physical device:
```powershell
adb -s ZA222LJBW2 install -r -d android/app/build/outputs/apk/debug/app-debug.apk
# Performing Streamed Install -> Success
```

Ran connected instrumentation tests:
```powershell
./gradlew connectedDebugAndroidTest
```
Output:
```
> Task :app:connectedDebugAndroidTest
Starting 14 tests on motorola edge 50 fusion - 16

Finished 14 tests on motorola edge 50 fusion - 16

BUILD SUCCESSFUL in 50s
70 actionable tasks: 7 executed, 63 up-to-date
```

Detailed hardware test outcomes from `TEST-motorola edge 50 fusion - 16-_app-.xml`:
1. `testWeAreThePeopleVocalRestUnpaintedOnDevice`: **PASS** (0.077s) — verified silence before "in 1975" remains completely unpainted
2. `testSeekMidWordSnapsImmediately`: **PASS** (0.016s) — verified instant position snap without catch-up lag
3. `testLineSyncFallbackHonesty`: **PASS** (0.017s) — verified untimed lyrics fallback gracefully
4. `testBackgroundVocalsPreservation`: **PASS** (0.014s) — verified `isBackground` flag and timing
5. `testIndicScriptPreservationOnDevice`: **PASS** (0.014s) — verified Devanagari text (*कुन फया कुन*) is mapped without cluster breakage
6. `testRealExoPlayerPlaybackClockSourceOnDevice`: **PASS** (0.014s) — verified ExoPlayer on main looper reads position directly
7. `testPlaybackSpeedScalingOnDevice`: **PASS** (0.019s) — verified 0.5x and 2.0x playback rate carry scaling
8. `testRapidSyllableTimingRapGod`: **PASS** (0.015s) — verified high-density syllables from *Rap God* map monotonically
9. `testLiveBetterLyricsProviderOnDevice`: **PASS** (4.927s) — live network lookup against Better Lyrics API from device hardware resolves real syllable TTML
10. `testPauseMidWordFreezesHighlight`: **PASS** (0.013s) — verified highlight freezes mid-word when paused
11. `testTrackChangeResetsClockAndLines`: **PASS** (0.016s) — verified clock and line state reset cleanly on track change
12. `testWarmAndRapidSwitching`: **PASS** (3.271s) — verified cached playback resolution under 50ms
13. `testRealDeviceEndToEndPlaybackTimings`: **PASS** (10.366s) — verified end-to-end playback on hardware
14. `useAppContext`: **PASS** (0.017s) — verified application context package
