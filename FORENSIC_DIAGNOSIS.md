# Forensic Diagnosis: Track-Change Motion / VIVI Parity

## 1. EXACT ROOT CAUSE — Button Transition Lag

The button path has a **fundamental timing inversion** compared to the swipe path. Here is the exact causal chain:

### Button Click Frame-by-Frame

```
Frame 0: User taps Next button
  → handleNext executes SYNCHRONOUSLY:
    1. pendingTargetIndex = targetIndex           ← IMMEDIATE state write
    2. coroutineScope.launch { animateScrollToPage(...) }  ← animation scheduled
    3. skipPlaybackJob = coroutineScope.launch { yield(); onNextClick() }
```

The critical problem: **`pendingTargetIndex` is set on Frame 0**, which immediately triggers a cascade:

```
Frame 0 recomposition:
  activeTrack = deriveActiveTrack(currentTab, pendingTargetIndex=1, currentTrackIndex=0, queue, track)
  → returns queue[1]  (the NEW track)
  → activeTrack.id changes from "track_A" to "track_B"
```

This causes **everything downstream to recompose with Track B's identity on Frame 0** — *before* the pager has moved a single pixel:

| Component | Frame 0 State | Problem |
|-----------|--------------|---------|
| `activeTrackPalette` | `remember(activeTrack.id)` re-initializes → calls `getOrCreatePalette` | Returns placeholder since async extraction hasn't run yet |
| `extractedColors` | Recomputed from new `activeTrackPalette` | Can briefly show placeholder/hash color |
| `dynamicBgData` | Recomputed with new `activeTrack.thumbnail` | Background identity changes |
| `AnimatedContent(targetState = activeTrack)` | Starts title/artist slide animation | Title changes before pager moves |
| HorizontalPager `key(pageTrack.id)` | Line 1322: `key(pageTrack.id)` wraps `ArtworkCard` | See Root Cause #2 below |

**The swipe path does NOT have this problem** because:
- During a swipe, `pendingTargetIndex` remains `null` until the pager **settles** (line 504-505)
- `deriveActiveTrack` returns `playingTrack` (the current playing song) throughout the entire gesture
- Background, palette, title — everything stays stable on the current song while the cards physically scroll

### The `yield()` Problem

The `yield()` before `onNextClick()` was intended to let the animation start first, but it's actually counterproductive:

1. `pendingTargetIndex = targetIndex` already fires on Frame 0 (before `yield`), triggering the full recomposition cascade
2. The `yield()` only defers `onNextClick()` — which calls `PlayerViewModel.next()` → `audioPlayer.next()` → `audioPlayer.play()` → `_currentTrack.value = newTrack`
3. This creates a **two-step identity change**: first `activeTrack` changes via `pendingTargetIndex` (Frame 0), then `currentTrack`/`currentTrackIndex` change via the flow collection (Frame N)
4. The LaunchedEffect at line 404 (`currentTrackIndex, track.id, currentTab`) fires when playback catches up. Since `pendingTargetIndex == currentTrackIndex`, it enters the early-return path. But if the timing is wrong during rapid skips, stale intermediate flow emissions can fight with the optimistic target.

**Net effect of `yield()`**: It does NOT prevent the visual cascade (that's caused by `pendingTargetIndex`), but it DOES create a temporal gap where the visual identity (via `pendingTargetIndex`) and the playback identity (via `currentTrack`/`currentTrackIndex`) are intentionally desynchronized. This gap is the source of artwork disappearance during rapid changes.

---

## 2. EXACT ROOT CAUSE — Black/Empty Artwork During Button Changes

The artwork disappearance is caused by **Coil request cancellation and re-initiation** due to `key()` recomposition in the HorizontalPager.

### The Mechanism

At `NowPlayingModal.kt:1322`:

```kotlin
key(pageTrack.id) {
    Box(...) {
        ArtworkCard(
            url = pageTrack.thumbnail,
            fallbackTrack = pageTrack,
            ...
        )
    }
}
```

And at `NowPlayingModal.kt:1266-1268`:

```kotlin
val pageTrack = if (queue.isNotEmpty() && page in queue.indices) {
    val qTrack = queue[page]
    if (qTrack.id == activeTrack.id) activeTrack else qTrack
} else {
    activeTrack
}
```

When `pendingTargetIndex` is set to page 1, `activeTrack` becomes `queue[1]`. For the page at index 1, `qTrack.id == activeTrack.id` is true, so it uses `activeTrack`. But the `key(pageTrack.id)` at line 1322 hasn't changed (it was already `queue[1].id`).

**The real issue manifests during rapid A → B → C → D**:

1. User is on page 0 (Track A). Pages 0 and 1 are composed by the pager (`beyondViewportPageCount = 1`).
2. Button tap: `pendingTargetIndex = 1`, animation starts toward page 1.
3. Rapid second tap: `pendingTargetIndex = 2`, animation retargets toward page 2.
4. As the pager animates past page 1 toward page 2, **page 2 enters the viewport for the first time**.
5. Page 2's `ArtworkCard` begins a **fresh Coil network request** for Track C's artwork.
6. Until Coil resolves, the `ArtworkCard` shows `Color(0xFF141414)` background (line 289) — this is the **black flash**.
7. If the user taps again before page 2's artwork loads, page 3 enters and the cycle repeats.

The pre-caching at line 523-555 only pre-caches **adjacent** pages to `pagerState.currentPage`. During rapid skips, `pagerState.currentPage` lags behind `pendingTargetIndex`, so pages 2 and 3 haven't been pre-cached when they enter the viewport.

### Why Swipe Doesn't Have This Problem

During swipe, the user physically drags through each page sequentially. Page N+1 is **always** the immediately adjacent page, which was pre-cached by the `LaunchedEffect(pagerState.currentPage)` precaching logic. The pager never jumps multiple pages ahead, so the precache hits.

---

## 3. EXACT ROOT CAUSE — Background Color Stutter During Swipe

The background color transition stutters during swipe because `dynamicBgData.swipeFraction` is **hardcoded to `0f`** and `dynamicBgData.secondaryArtworkUrl` is **hardcoded to `null`**:

```kotlin
// Line 657-667
val dynamicBgData = remember(activeTrack, extractedColors) {
    DynamicBackgroundData(
        primaryArtworkUrl = activeTrack.thumbnail,
        secondaryArtworkUrl = null,           // ← ALWAYS null
        swipeFraction = 0f,                   // ← ALWAYS 0
        palette = extractedColors
    )
}
```

This means `PlayerBackground` receives `swipeFraction = 0f` at all times, so in `animateArtworkPalette`:

```kotlin
val effectivePalette = if (isSwiping) {  // isSwiping = swipeFraction > 0.005f && secondary != null
    extractedColors                       // ← NEVER taken
} else {
    animateArtworkPalette(extractedColors) // ← ALWAYS taken
}
```

The background color is driven entirely by `animateArtworkPalette`, which uses a 240ms `tween` animation keyed on `extractedColors` changes. Here's the stutter mechanism:

1. User swipes from page 0 to page 1.
2. Pager settles on page 1 at time T₀.
3. `snapshotFlow` detects settle → `pendingTargetIndex = settledPage` → `onSelectQueueTrack(settledPage)`
4. `activeTrack` changes to Track B → `activeTrackPalette` recomputed via `getOrCreatePalette`
5. If the palette for Track B is already cached: `extractedColors` changes **discretely** in one frame
6. `animateArtworkPalette` then takes 240ms to interpolate from old → new colors

The stutter occurs because the `extractedColors` change is a **discrete jump** (one frame), and the 240ms animation specification may be too fast or the `animProgress.snapTo(0f)` at line 365 causes a visible flash before the tween begins. Additionally, the `paletteDurationMillis` at 240ms with an accelerated 156ms mode (when `progress < 0.85f`) makes the transition feel abrupt rather than smooth.

But the deeper problem for SWIPE is: **the background palette doesn't interpolate continuously with the swipe gesture**. The `swipeFraction` and `secondaryArtworkUrl` are always 0/null, so there's no per-frame color blending during the swipe. The background holds completely still on Song A's colors until the swipe commits, then jumps via the 240ms animation. This is inherently non-smooth — it's a deferred discrete transition, not a continuous one.

---

## 4. Why Swipe Works While Buttons Fail

| Aspect | Swipe Path | Button Path |
|--------|-----------|-------------|
| **When `activeTrack` changes** | After pager settles (line 504-505) | Immediately on click (line 356) |
| **When playback starts** | After pager settles (line 508) | ~1 frame after click (via `yield()`) |
| **Pager visual state at identity change** | Fully settled on target page | Haven't moved yet (Frame 0) |
| **Artwork pre-cache** | Adjacent page already cached | Target page may be 2+ pages away |
| **Background transition** | Discrete 240ms after settle | Discrete 240ms on Frame 0 of animation |
| **Title animation** | Starts after pager arrival | Starts before pager moves |

The swipe path's key advantage: **visual identity changes happen AFTER visual arrival**. The button path's fatal flaw: **visual identity changes happen BEFORE visual departure**.

---

## 5. Whether `yield()` Helps or Creates Problems

**The `yield()` is actively harmful.** It creates two distinct problems:

### Problem A: False Ordering Guarantee
`yield()` suspends until the next dispatch cycle, but `pendingTargetIndex` was already set synchronously. The recomposition cascade from `pendingTargetIndex` is **not delayed by `yield()`** — it happens in the same or next frame regardless. The `yield()` only delays `onNextClick()` (playback), creating a window where visual identity ≠ playback identity.

### Problem B: Rapid Skip Race Condition
During rapid A → B → C → D:
1. Tap 1: `skipPlaybackJob` launches `onNextClick()` for B (after yield)
2. Tap 2 (before yield returns): `skipPlaybackJob?.cancel()` cancels B's playback, launches C's
3. Tap 3: cancels C, launches D

This correctly ensures only D's playback executes. But the `pendingTargetIndex` has been through 1 → 2 → 3, causing the pager to retarget its animation three times. Meanwhile, `activeTrack` changed three times, each time triggering:
- `activeTrackPalette` re-initialization (line 607)
- `LaunchedEffect(activeTrack.id)` for palette extraction (line 613)
- `AnimatedContent(targetState = activeTrack)` title animation (line 1362)

Each of these intermediate identity changes causes visual work that's immediately discarded when the next tap arrives.

---

## 6. Smallest Safe Architecture/Fix Plan

The core fix is: **make button clicks use the same "commit after settle" pattern as swipe**, keeping `pendingTargetIndex` as a pager-only concept that doesn't change `activeTrack` until the pager arrives.

### Fix Architecture

1. **Split `pendingTargetIndex` into two roles**:
   - `pagerTargetIndex`: drives pager animation only (already exists as `pendingTargetIndex`)
   - `committedTargetIndex`: drives `activeTrack` / background / palette / title — only set when pager settles or playback catches up

2. **In `handleNext`/`handlePrevious`**:
   - Set `pagerTargetIndex` immediately (for pager animation)
   - Call `onNextClick()` synchronously (no `yield()`) — playback should start immediately
   - Do NOT set `committedTargetIndex` yet

3. **In the snapshotFlow settle handler**:
   - When pager settles AND `pagerTargetIndex` is set AND came from a button click:
   - Set `committedTargetIndex = settledPage`
   - Clear `pagerTargetIndex`

4. **In `deriveActiveTrack`**:
   - Use `committedTargetIndex` instead of `pendingTargetIndex`
   - This means `activeTrack` stays on the current playing track until the pager animation finishes

5. **Pre-cache by `pagerTargetIndex`** rather than only by `pagerState.currentPage`:
   - When `pagerTargetIndex` is set, immediately pre-cache artwork for `queue[pagerTargetIndex]`
   - This eliminates the black flash because artwork loads during the 500ms animation

6. **Restore `onNextClick()` to synchronous** (remove `yield()` and `skipPlaybackJob`):
   - Playback can start immediately; it doesn't interfere with the pager animation because `activeTrack` won't change until settle
   - The flow `_currentTrack` → ViewModel → `uiState.currentTrack` → `currentTrackIndex` update will arrive eventually, and the LaunchedEffect reconciliation will be a no-op since the pager is already at/targeting that page

7. **For background color during swipe**: wire `swipeFraction` from `pagerState.currentPageOffsetFraction` and interpolate palettes between adjacent tracks in real-time.

---

## 7. Exact Files/Functions/State Transitions to Change

### `NowPlayingModal.kt`

| Line(s) | Current | Change |
|---------|---------|--------|
| 335 | `pendingTargetIndex` single variable | Split into `pagerTargetIndex` (pager-only) and `committedTargetIndex` (identity-controlling) |
| 345 | `deriveActiveTrack(... pendingTargetIndex ...)` | Use `committedTargetIndex` instead |
| 349-374 | `skipPlaybackJob` + `yield()` in handleNext | Remove `skipPlaybackJob`. Call `onNextClick()` synchronously. Only set `pagerTargetIndex`. |
| 377-401 | Same for handlePrevious | Same treatment |
| 404-458 | LaunchedEffect reconciliation | Guard with `pagerTargetIndex` instead of `pendingTargetIndex` |
| 496-519 | snapshotFlow settle handler | When settling from a button-initiated animation, set `committedTargetIndex = settledPage` |
| 523-555 | Precache by `pagerState.currentPage` | Also precache by `pagerTargetIndex` when set |
| 657-667 | `dynamicBgData` with `swipeFraction = 0f` | Wire real swipe fraction for continuous color blending during gesture |
| 1321 | `isSharedArtworkActive` | Update to use `committedTargetIndex` |

### `PlayerBackgroundRenderer.kt`

No structural changes needed if `NowPlayingModal` correctly wires `swipeFraction`. The current single-`SingleGradientLayer` approach is correct for the committed palette path. The `animateArtworkPalette` handles committed transitions correctly.

### `ArtworkPaletteCache.kt`

The `getCachedOrFastExtract` change (returning null and offloading to background) is correct and should be kept. No changes needed.

### `AuralisAudioPlayer.kt`

The `startMediaService` offload and `persistJob` debouncing are correct. No changes needed.

---

## 8. What Must NOT Be Touched

- **HorizontalPager key strategy** (`queue.getOrNull(page)?.id ?: page`) — this is correct and ensures page stability
- **Speed Dial** pinning
- **MediaSession** / foreground service lifecycle
- **Downloads / offline** artwork resolution
- **Wake mode** / audio focus
- **Queue drag/drop** reordering
- **Navigation / Settings** UI
- **`deriveActiveTrack` function signature** — keep it, just change what's passed as `pendingTargetIndex`
- **`animateArtworkPalette`** — the 240ms interruptible animation is well-designed
- **`ArtworkPaletteCache` extraction pipeline** — the async offload is correct
- **Swipe settle → track change** flow (lines 496-519) — this is the known-good path to model after
- **MiniPlayer** integration
- **`PlayerBackground` unified renderer** architecture
- **Existing test assertions** for pager reconciliation

---

## 9. Focused Tests to Add/Update

### New Tests

1. **`test button click does NOT change activeTrack before pager settles`**
   - Simulate `handleNext` → verify `committedTargetIndex` is null → verify `activeTrack` is still `playingTrack`
   - Then simulate pager settle → verify `committedTargetIndex` is set → verify `activeTrack` is `queue[targetIndex]`

2. **`test rapid button clicks only commit final target on settle`**
   - Simulate `handleNext` x3 → `pagerTargetIndex` = 3, `committedTargetIndex` = null
   - Pager settles on 3 → `committedTargetIndex` = 3

3. **`test button-initiated animation settling updates committedTargetIndex`**
   - Verify the snapshotFlow settle handler correctly distinguishes button-initiated vs swipe-initiated settles

4. **`test precaching targets pagerTargetIndex not just currentPage`**
   - When `pagerTargetIndex` = 3, verify precache is triggered for `queue[3]` and `queue[4]`

5. **`test playback starts immediately without yield on button click`**
   - Verify `onNextClick()` is invoked synchronously in `handleNext`

### Updated Tests

6. **Update existing `PagerReconciliationSimulator` tests** to use `pagerTargetIndex` / `committedTargetIndex` split
7. **Update `deriveActiveTrack` tests** to pass `committedTargetIndex` instead of `pendingTargetIndex`

---

## 10. Step-by-Step Implementation Plan

### Step 1: Split `pendingTargetIndex`
- In `NowPlayingModal.kt`, rename `pendingTargetIndex` → `pagerTargetIndex`
- Add new `var committedTargetIndex by remember { mutableStateOf<Int?>(null) }`
- `pagerTargetIndex` = where the pager is heading (set immediately on button click)
- `committedTargetIndex` = where UI identity is committed (set only on pager settle)

### Step 2: Update `deriveActiveTrack` call
- Change line 345 from `deriveActiveTrack(... pendingTargetIndex ...)` to `deriveActiveTrack(... committedTargetIndex ...)`
- This is the single most important change. It prevents `activeTrack` from flipping before the pager arrives.

### Step 3: Fix `handleNext` / `handlePrevious`
- Set `pagerTargetIndex` immediately (for pager animation)
- Remove `skipPlaybackJob`, `yield()`, and the `coroutineScope.launch` wrapper
- Call `onNextClick()` / `onPreviousClick()` synchronously

### Step 4: Update snapshotFlow settle handler
- When settle detects `pagerTargetIndex != null` and `!userSwipedPager`:
  - `committedTargetIndex = settledPage`
  - Call `updateForTrack()` and `onSelectQueueTrack()` 
  - `pagerTargetIndex = null`
- The swipe path already sets its own `committedTargetIndex` via the existing `pendingTargetIndex = settledPage` on user swipe settle (line 505)

### Step 5: Update LaunchedEffect reconciliation
- Replace all references to `pendingTargetIndex` with `pagerTargetIndex` for pager guard logic
- Reference `committedTargetIndex` only for identity decisions

### Step 6: Precache by `pagerTargetIndex`
- Add a LaunchedEffect keyed on `pagerTargetIndex`:
  - When set, immediately enqueue Coil requests for `queue[pagerTargetIndex]` artwork
  - This runs during the 500ms animation, so artwork is ready before settle

### Step 7: Wire real `swipeFraction` for continuous swipe color blending
- In the `dynamicBgData` construction, derive `swipeFraction` from `pagerState.currentPageOffsetFraction` when `userSwipedPager` is true
- Set `secondaryArtworkUrl` from the adjacent track's thumbnail
- This enables per-frame color interpolation during gesture, matching VIVI

### Step 8: Update `isSharedArtworkActive`
- Use `committedTargetIndex` instead of `pendingTargetIndex`

### Step 9: Update tests
- Rename `pendingTargetIndex` references in `PagerReconciliationSimulator`
- Add the 5 new tests from section 9

### Step 10: Verify
- Run all `TrackChangeParityTest`, `MainPlayerBackgroundTransitionTest`, `PlayerNextPreviousInteractionTest`
- Build debug APK
- Manual test: single button tap, rapid A→B→C→D, swipe, mixed swipe+button, partial swipe cancel
