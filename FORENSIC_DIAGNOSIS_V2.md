# Revised Forensic Diagnosis v2: Track-Change Motion / VIVI Parity

## 0. Corrections to Previous Diagnosis

The previous diagnosis correctly identified the timing inversion (`pendingTargetIndex` commits visual identity before pager moves) and the `swipeFraction = 0f` problem. However it was **incomplete or incorrect** in three areas:

### Correction A: `committedTargetIndex` Alone Is Insufficient

The previous proposal:
- `pagerTargetIndex` = visual destination
- `committedTargetIndex` = visual identity
- Call `onNextClick()` synchronously
- `deriveActiveTrack` uses `committedTargetIndex`

**The flaw**: `deriveActiveTrack` (line 220-225) has a fallback:

```kotlin
fun deriveActiveTrack(..., pendingTargetIndex: Int?, ..., playingTrack: Track): Track {
    if (currentTab == PLAYER && pendingTargetIndex != null && ...) {
        return queue[pendingTargetIndex]   // ← primary path
    }
    return playingTrack                    // ← FALLBACK
}
```

If we pass `committedTargetIndex = null` while a button animation is in progress, **and** `onNextClick()` has been called synchronously, the ViewModel flow pipeline delivers `track = Track B` within 1-2 frames. The fallback path `return playingTrack` returns **Track B** — the visual identity flips to B before the pager arrives. The entire split is defeated.

**Conclusion**: We need a **third state** — `displayedTrackIndex` — that is explicitly frozen and never falls through to the playback track during an animation.

### Correction B: `key(pageTrack.id)` Is NOT the Root Cause of Black Frames

The previous diagnosis attributed the black/empty artwork flash to `key(pageTrack.id)` causing Coil cancellation/restart. This was **speculative and incorrect**. The actual mechanism is different — see Section 4 below for the source-proven root cause.

### Correction C: Background Stutter Was Correctly Identified But Solution Was Underspecified

The `swipeFraction = 0f` finding was correct. The revised diagnosis below provides the exact safe architecture for continuous blending without rewriting `PlayerBackground`.

---

## 1. Exact Three-Way State Model

Three independent state variables, each with a distinct role:

| State | Type | Set When | Purpose |
|-------|------|----------|---------|
| **Playback Track** | `track: Track` (from ViewModel flow via `uiState`) | ExoPlayer advances, `onNextClick()` / `onPreviousClick()` dispatches, queue tap | What audio is actually playing. Drives MediaSession, notification, audio focus. |
| **Pager Target** | `pagerTargetIndex: Int?` | Button click (immediately), cleared on pager settle | Where the pager is animating toward. Drives `pagerState.animateScrollToPage()` and pre-caching. Has **zero** influence on visual identity. |
| **Displayed Track** | `displayedTrackIndex: Int` | (a) Pager settle after button animation, (b) Pager settle after swipe, (c) External track change when no animation pending | What artwork / title / background / palette the player UI is presenting. **The single source of truth for visual identity.** |

### Why Three States, Not Two

The critical case the previous diagnosis missed:

```
Frame 0:  User taps Next
          pagerTargetIndex = 1
          onNextClick() dispatched synchronously
          displayedTrackIndex = 0  ← FROZEN

Frame 1-2: ViewModel flow delivers track = B, currentTrackIndex = 1
           displayedTrackIndex = 0  ← STILL FROZEN (pagerTargetIndex != null)
           activeTrack = queue[0] = Track A  ← CORRECT

Frame N:   Pager settles on page 1
           displayedTrackIndex = 1  ← NOW committed
           activeTrack = queue[1] = Track B  ← CORRECT
```

If we only had two states (`pagerTargetIndex` + fallback to `playingTrack`), Frame 1-2 would leak Track B into the visual identity.

### New `deriveActiveTrack` Logic

```kotlin
fun deriveActiveTrack(
    currentTab: NowPlayingTab,
    displayedTrackIndex: Int,  // ← replaces pendingTargetIndex
    queue: List<Track>,
    playingTrack: Track
): Track {
    if (currentTab == NowPlayingTab.PLAYER && queue.isNotEmpty()) {
        if (displayedTrackIndex in queue.indices) {
            return queue[displayedTrackIndex]
        }
    }
    return playingTrack
}
```

The function signature changes: `pendingTargetIndex: Int?` → `displayedTrackIndex: Int`. The `currentTrackIndex` parameter is removed from the identity decision. `displayedTrackIndex` is always an `Int` (never null), initialized to `currentTrackIndex` at composition time.

### Update Rules for `displayedTrackIndex`

| Trigger | Condition | Action |
|---------|-----------|--------|
| Button click (handleNext/handlePrevious) | Always | Set `pagerTargetIndex` only. Do NOT touch `displayedTrackIndex`. |
| Pager settles after button animation | `pagerTargetIndex != null && !userSwipedPager && !isScrolling` | `displayedTrackIndex = settledPage`, `pagerTargetIndex = null` |
| Pager settles after user swipe | `userSwipedPager && !isProgrammaticScroll && settledPage != displayedTrackIndex` | `displayedTrackIndex = settledPage` |
| External track change (song end, queue tap) | `pagerTargetIndex == null && !pagerState.isScrollInProgress` | `displayedTrackIndex = newCurrentTrackIndex` |
| External track change during animation | `pagerTargetIndex != null` | Do NOT update `displayedTrackIndex` — let the animation complete first |

---

## 2. Exact Button Sequence After Fix (Frame by Frame)

### Single Next Button Tap: A → B

```
Frame 0: User taps Next
  STATE:
    displayedTrackIndex = 0  (Track A) — UNCHANGED
    pagerTargetIndex = 1     — SET
    playback: onNextClick() dispatched (synchronously or via launch)

  VISUAL:
    activeTrack = queue[0] = Track A     ← derived from displayedTrackIndex
    Title/Artist: "Track A"
    Background palette: Track A colors
    Pager: animateScrollToPage(1) launched

Frame 1: Pager begins animating toward page 1
  STATE: same
  VISUAL:
    Pager offset: ~0.05 (just started moving)
    Album cards: A slides out, B slides in
    Title/Artist: still "Track A"
    Background: still Track A palette

Frame 1-2: ViewModel flow delivers track = B, currentTrackIndex = 1
  STATE:
    displayedTrackIndex = 0  ← STILL FROZEN
    pagerTargetIndex = 1     ← guards against external update
  VISUAL: completely unchanged — Track A identity maintained

Frame N (~500ms): Pager settles on page 1 (isScrollInProgress = false)
  snapshotFlow fires:
    displayedTrackIndex = 1  ← NOW committed
    pagerTargetIndex = null  ← cleared
    ArtworkPaletteCache.updateForTrack(context, queue[1])

  VISUAL:
    activeTrack = queue[1] = Track B     ← derived from displayedTrackIndex
    Title/Artist: animates to "Track B" (AnimatedContent 500ms slide)
    Background palette: animateArtworkPalette transitions A→B (240ms tween)
    Result: title + background change synchronized with pager arrival
```

### Rapid A → B → C → D (three quick taps)

```
Frame 0: Tap 1 (Next)
  displayedTrackIndex = 0, pagerTargetIndex = 1
  Pager: animateScrollToPage(1)
  onNextClick() dispatched

Frame ~100ms: Tap 2 (Next) — before pager settles
  pagerTargetIndex = 2  ← retargeted
  Pager: animateScrollToPage(2) — retargets animation
  onNextClick() dispatched again
  displayedTrackIndex = 0  ← STILL FROZEN on Track A

Frame ~200ms: Tap 3 (Next) — before pager settles
  pagerTargetIndex = 3  ← retargeted
  Pager: animateScrollToPage(3)
  onNextClick() dispatched again
  displayedTrackIndex = 0  ← STILL FROZEN on Track A

Frames 200-800ms: Pager animates through pages 1, 2, toward 3
  VISUAL:
    Title/Artist: "Track A" throughout — no intermediate flicker
    Background: Track A palette — no intermediate jumps
    Album cards: pages scroll through physically
    Pre-caching: LaunchedEffect(pagerTargetIndex=3) enqueues Coil requests

Frame ~800ms: Pager settles on page 3
  displayedTrackIndex = 3  ← committed
  pagerTargetIndex = null  ← cleared
  VISUAL:
    activeTrack = queue[3] = Track D
    Title/Artist: single clean A→D transition
    Background: single clean palette morph A→D
```

**Key improvement**: Zero intermediate identity changes. No wasted recompositions for B and C.

---

## 3. Exact Swipe Sequence After Fix (Frame by Frame)

### Full Swipe: A → B

```
Frame 0: User touches and begins dragging
  DragInteraction.Start → userSwipedPager = true
  displayedTrackIndex = 0  ← UNCHANGED
  pagerTargetIndex = null  ← swipes don't set this

Frame 1..N: User drags finger
  VISUAL:
    Pager: cards physically move with finger
    Title/Artist: "Track A" — stable
    Background: Track A palette — stable
    (With continuous blending fix: background interpolates A↔B colors
     using pagerState.currentPageOffsetFraction)

Frame N+1: User lifts finger, pager flings/settles on page 1
  snapshotFlow detects: isScrolling=false, settledPage=1
  userSwipedPager = true && !isProgrammaticScroll → swipe settle path
  displayedTrackIndex = 1  ← committed
  onSelectQueueTrack(1) dispatched → playback starts Track B

  VISUAL:
    activeTrack = queue[1] = Track B
    Title: animates A→B
    Background: palette transition A→B (240ms or already interpolated if continuous)
```

### Partial Swipe Cancel: A → (half B) → snap back to A

```
Frame 0..N: User drags partway toward B
  displayedTrackIndex = 0  ← stable
  Background: (with continuous fix) partially blended toward B

Frame N+1: User releases, pager snaps back to page 0
  snapshotFlow: settledPage=0, settledPage == displayedTrackIndex
  userSwipedPager = false  ← reset
  displayedTrackIndex = 0  ← unchanged
  No track change triggered

  VISUAL:
    Title/Artist: "Track A" — never changed
    Background: (with continuous fix) snaps back to A palette
```

---

## 4. Exact Root Cause of Black/Empty Artwork — Source-Proven

### The Actual Mechanism (NOT `key()` disposal)

The black frame is **NOT** caused by `key(pageTrack.id)` disposing and recomposing the `ArtworkCard`. Inspecting the source proves a different mechanism:

#### Step 1: What `beyondViewportPageCount = 1` Means

At [NowPlayingModal.kt:1250](file:///c:/Users/shrey/OneDrive/Desktop/Auralis/android/app/src/main/java/com/auralis/music/ui/player/NowPlayingModal.kt#L1250):
```kotlin
beyondViewportPageCount = 1
```

The HorizontalPager composes: `[currentPage - 1, currentPage, currentPage + 1]`. Only 3 pages are alive at any time.

#### Step 2: What Happens During Rapid A→B→C→D

Starting at page 0 with `beyondViewportPageCount = 1`:
- **Composed pages**: 0, 1

When `pagerTargetIndex = 3` and `animateScrollToPage(3)`:
- The pager animates physically through pages 1, 2, 3
- As page 2 enters the viewport, it is **composed for the first time**
- As page 3 enters, it too is **composed for the first time**

#### Step 3: What ArtworkCard Does on First Composition

At [ArtworkCard.kt:162-309](file:///c:/Users/shrey/OneDrive/Desktop/Auralis/android/app/src/main/java/com/auralis/music/ui/components/ArtworkCard.kt#L162-L309):

1. `resolvedUrl` is computed via `getHighResArtworkUrl(url)` (line 178-182, `highRes = true` for player)
2. `fallbackUrl` checks local download, ArtworkResolver, matched video ID (lines 184-213)
3. `activeUrl` is selected (lines 227-233): prefers `resolvedUrl`, then `fallbackUrl`, then `asyncResolvedUrl`
4. `imageRequest` is built with `activeUrl` (lines 238-271)
5. The `Box` at line 285-308:

```kotlin
Box(
    modifier = modifier
        .clip(shape)
        .background(Color(0xFF141414)),  // ← THIS IS THE BLACK FRAME
    contentAlignment = Alignment.Center
) {
    if (!activeUrl.isNullOrBlank() && !isError) {
        AsyncImage(
            model = imageRequest ?: activeUrl,
            ...
        )
    } else {
        Icon(Icons.Default.MusicNote, ...)  // music note fallback
    }
}
```

The `Color(0xFF141414)` is the **Box background**, visible whenever `AsyncImage` hasn't painted a frame yet.

#### Step 4: Why AsyncImage Shows Black

When page 2's `ArtworkCard` is composed for the first time:

1. `activeUrl` = high-res URL for Track C's artwork (e.g. `=w1200-h1200-l90-rj`)
2. `imageRequest` is created (line 238-271)
3. Coil checks its memory cache at line 240-243:
   ```kotlin
   val isCachedInMemory = try {
       coil.Coil.imageLoader(context).memoryCache?.get(
           coil.memory.MemoryCache.Key(activeUrl)
       ) != null
   } catch (_: Throwable) { false }
   ```
4. **The pre-caching at lines 523-555 uses `getHighResArtworkUrl()` with `size(600, 600)`**. The `imageRequest` inside `ArtworkCard` also uses `size(600, 600)` when `highRes = true` (line 261). **BUT the memory cache key includes the request parameters (URL + size)**. If the pre-cached URL exactly matches, the memory cache hits. If it doesn't match (different URL variant, or page was never pre-cached), the cache misses.

5. **Pre-caching only covers `pagerState.currentPage ± 1`** (lines 525-526):
   ```kotlin
   val nextTrack = queue.getOrNull(pagerState.currentPage + 1)
   val prevTrack = queue.getOrNull(pagerState.currentPage - 1)
   ```
   During rapid skips from page 0, `pagerState.currentPage` is still 0 (or slowly catching up). Pages 2 and 3 are NOT pre-cached.

6. When Coil memory cache misses: `AsyncImage` initially renders **nothing** (transparent/empty content area). The `Box` background `Color(0xFF141414)` shows through. Coil then hits disk cache or network. On disk cache hit, the image appears in 1-2 frames. On network fetch, it can take 100-500ms.

#### Step 5: The Full Black-Frame Causal Chain

```
Rapid button taps: pagerTargetIndex jumps from 0 to 3
  → pager animates through pages 1, 2, 3
  → page 2 enters viewport for first time
  → ArtworkCard composed with queue[2].thumbnail
  → high-res URL computed: "...=w1200-h1200-l90-rj"
  → Coil memory cache MISS (never pre-cached — precache only covered page 0±1)
  → AsyncImage renders empty while fetching
  → Box background Color(0xFF141414) visible = BLACK FLASH
  → Coil disk cache hit: image appears in ~1-2 frames (fast but visible)
  → OR Coil network fetch: image appears in 100-500ms (very noticeable)
```

**The `key(pageTrack.id)` wrapper at line 1322 is NOT the cause.** The key is stable for each page position — `queue[2].id` doesn't change. The issue is purely that **the page was never composed before and its artwork was never pre-cached**.

#### Why Swipe Never Shows Black

During swipe, the user physically drags from page 0 to page 1. Page 1 was **already composed** (within `beyondViewportPageCount = 1`). Its artwork was already loaded by `AsyncImage` and pre-cached by the `LaunchedEffect(pagerState.currentPage)`. The user cannot physically skip pages — they must traverse sequentially.

---

## 5. Exact Root Cause of Background Stutter and Continuous Blending Architecture

### Root Cause (Confirmed from Source)

At [NowPlayingModal.kt:657-667](file:///c:/Users/shrey/OneDrive/Desktop/Auralis/android/app/src/main/java/com/auralis/music/ui/player/NowPlayingModal.kt#L657-L667):

```kotlin
val dynamicBgData = remember(activeTrack, extractedColors) {
    DynamicBackgroundData(
        primaryArtworkUrl = activeTrack.thumbnail,
        secondaryArtworkUrl = null,           // ← HARDCODED null
        swipeFraction = 0f,                   // ← HARDCODED 0
        palette = extractedColors
    )
}
```

At [PlayerBackgroundRenderer.kt:403-410](file:///c:/Users/shrey/OneDrive/Desktop/Auralis/android/app/src/main/java/com/auralis/music/ui/player/PlayerBackgroundRenderer.kt#L403-L410):

```kotlin
val isSwiping = swipeFraction > 0.005f && secondaryArtworkUrl != null  // ← ALWAYS false
val effectivePalette = if (isSwiping) {
    extractedColors          // ← NEVER taken
} else {
    animateArtworkPalette(extractedColors)  // ← ALWAYS taken
}
```

The result: **ALL palette transitions go through `animateArtworkPalette`**, which uses a 240ms `tween` (accelerated to 156ms during rapid skips per `PlayerTransitionMotion.paletteSpec`). There is zero per-frame interpolation tied to the physical gesture.

### Current Transition Timeline (Swipe)

```
T₀:        User swipes, pager cards physically move
T₀..T₁:    Background holds on Track A colors (swipeFraction = 0)
T₁:        Pager settles → displayedTrackIndex changes → activeTrack = B
T₁:        extractedColors changes discretely in one frame
T₁..T₁+240ms: animateArtworkPalette interpolates A→B (240ms tween)
```

The user sees: cards move smoothly, then background "jumps" to start a 240ms morph after settle. Not continuous.

### Current Transition Timeline (Button)

With the `displayedTrackIndex` fix applied:
```
T₀:        User taps Next, pager animates
T₀..T₁:    Background holds on Track A (displayedTrackIndex frozen)
T₁:        Pager settles → displayedTrackIndex = 1 → activeTrack = B
T₁:        extractedColors changes → animateArtworkPalette 240ms morph
```

This is already smoother than current behavior (no premature identity flip), but still a discrete 240ms morph after settle rather than continuous blending during animation.

### Desired Behavior: Continuous Pager-Driven Interpolation

#### For Swipe

The background should morph **continuously with the finger position**:
- At offset 0.0: 100% Track A palette
- At offset 0.5: 50% A + 50% B blended
- At offset 1.0: 100% Track B palette

This requires:
1. Knowing the **adjacent track's palette** (Track B) during the swipe
2. Reading `pagerState.currentPageOffsetFraction` per frame
3. Computing `lerpArtworkPalette(paletteA, paletteB, fraction)` per frame
4. Passing this interpolated palette to `PlayerBackground`

#### For Button Animation

The background should morph **continuously with the pager animation progress**:
- At animation progress 0.0: 100% Track A palette
- At animation progress 0.5: 50% A + 50% B blended
- At animation progress 1.0: 100% Track B palette

This uses the same mechanism — `pagerState.currentPageOffsetFraction` — since `animateScrollToPage` drives the same offset fraction.

#### Safest Architecture (Preserves PlayerBackground)

The existing `PlayerBackground` already supports `swipeFraction` and `secondaryArtworkUrl` parameters. The `SeamlessGradientLayer` already handles `swipeFraction` at line 812:

```kotlin
alpha = { if (swipeFraction > 0.005f && secondaryArtworkUrl != null) 1f - swipeFraction else 1f }
```

However, this only fades the primary gradient layer. For true continuous blending, we should NOT use this mechanism. Instead:

**Recommended approach**: Compute the interpolated palette **in NowPlayingModal** and pass it as `extractedColors` via `dynamicBgData.palette`. This way `PlayerBackground` receives a **pre-blended palette** and `animateArtworkPalette` smoothly handles it.

Concrete implementation:

```kotlin
// In NowPlayingModal, replace the dynamicBgData construction:

// 1. Get the adjacent track's palette (for continuous blending)
val adjacentPalette = remember(displayedTrackIndex, queue, pagerState.currentPage) {
    val direction = pagerState.currentPageOffsetFraction
    val adjacentIdx = if (direction >= 0) displayedTrackIndex + 1 else displayedTrackIndex - 1
    if (adjacentIdx in queue.indices) {
        ArtworkPaletteCache.getOrCreatePalette(context, queue[adjacentIdx])
    } else null
}

// 2. Compute continuous blend fraction from pager offset
val blendFraction by remember {
    derivedStateOf {
        abs(pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
    }
}

// 3. Blend palettes when swiping or during button animation
val blendedPalette = remember(extractedColors, adjacentPalette, blendFraction) {
    if (adjacentPalette != null && blendFraction > 0.005f) {
        lerpArtworkPalette(extractedColors, adjacentPalette, blendFraction)
    } else {
        extractedColors
    }
}

// 4. Pass blended palette to dynamicBgData
val dynamicBgData = remember(activeTrack, blendedPalette) {
    DynamicBackgroundData(
        primaryArtworkUrl = activeTrack.thumbnail,
        secondaryArtworkUrl = null,  // ← keep null, blending is handled via palette
        swipeFraction = 0f,          // ← keep 0, blending is handled via palette
        palette = blendedPalette
    )
}
```

**Why this is safe**:
- `PlayerBackground` continues to receive a single palette and uses `animateArtworkPalette` for committed transitions
- During active gesture/animation: `blendFraction > 0` → palette is pre-interpolated per frame → `animateArtworkPalette` sees continuous input, producing smooth output
- During idle: `blendFraction = 0` → `blendedPalette = extractedColors` → no change in behavior
- No structural changes to `PlayerBackgroundRenderer.kt`
- `DynamicBackgroundData` fields `secondaryArtworkUrl` and `swipeFraction` remain unused (preserved for future use)

**Alternative (simpler, less smooth for button path)**: Only apply continuous blending during swipe (`userSwipedPager = true`), and let button-path transitions use the existing 240ms `animateArtworkPalette` morph after settle. This is simpler but doesn't achieve full VIVI parity for button path.

**Recommended**: Apply continuous blending for BOTH swipe and button paths, since `pagerState.currentPageOffsetFraction` works for both gestures and programmatic animations.

---

## 6. Exact Role of `yield()` — Should It Be Removed?

### Current `yield()` Behavior

At [NowPlayingModal.kt:370-374](file:///c:/Users/shrey/OneDrive/Desktop/Auralis/android/app/src/main/java/com/auralis/music/ui/player/NowPlayingModal.kt#L370-L374):

```kotlin
skipPlaybackJob?.cancel()
skipPlaybackJob = coroutineScope.launch {
    kotlinx.coroutines.yield()
    onNextClick()
}
```

`yield()` suspends until the next dispatch cycle. This:
1. Allows the pager animation coroutine (launched just above) to be scheduled first
2. Debounces rapid taps via `skipPlaybackJob?.cancel()` — only the last tap's `onNextClick()` actually executes

### Analysis

**The debouncing is valuable.** During rapid A→B→C→D:
- Tap 1: launches `onNextClick()` for B (deferred via yield)
- Tap 2: cancels B's playback job, launches C's
- Tap 3: cancels C's, launches D's
- Result: only D's playback executes

Without this debouncing, `onNextClick()` would be called 3 times, causing ExoPlayer to resolve and buffer 3 intermediate tracks wastefully.

**The `yield()` itself is now harmless** (neither helpful nor harmful) with the `displayedTrackIndex` fix, because:
- `displayedTrackIndex` is not set on button click, so the visual identity doesn't change regardless of yield timing
- The temporal gap between visual and playback identity is no longer a problem because visual identity is explicitly frozen

### Recommendation: Keep Debouncing, Remove `yield()`

Replace the `yield()` + `skipPlaybackJob` pattern with a simpler debounce:

```kotlin
// In handleNext:
skipPlaybackJob?.cancel()
skipPlaybackJob = coroutineScope.launch {
    onNextClick()  // no yield — dispatch immediately
}
```

The debouncing via `skipPlaybackJob?.cancel()` is preserved. The `yield()` is removed because:
1. It served no visual purpose (the visual identity is now frozen by `displayedTrackIndex`)
2. Starting playback 1 dispatch cycle earlier means audio arrives sooner (slightly better UX)
3. The ordering between animation start and playback dispatch is irrelevant since they are decoupled

**However**, if `onNextClick()` is heavyweight (triggers synchronous work on the main thread before suspending), keeping the `yield()` is harmless and ensures the animation coroutine gets first priority. The choice is low-stakes.

**Verdict: Remove `yield()`, keep `skipPlaybackJob?.cancel()` debouncing.**

---

## 7. Smallest Safe Implementation Plan

### Step 1: Introduce `displayedTrackIndex`

In [NowPlayingModal.kt](file:///c:/Users/shrey/OneDrive/Desktop/Auralis/android/app/src/main/java/com/auralis/music/ui/player/NowPlayingModal.kt):

```kotlin
// Line ~335: Replace pendingTargetIndex with two variables
var pagerTargetIndex by remember { mutableStateOf<Int?>(null) }
var displayedTrackIndex by remember { mutableIntStateOf(currentTrackIndex) }
```

### Step 2: Update `deriveActiveTrack` Call Site

```kotlin
// Line 345-346: Change from pendingTargetIndex to displayedTrackIndex
val activeTrack = remember(currentTab, displayedTrackIndex, queue, track) {
    deriveActiveTrack(currentTab, displayedTrackIndex, queue, track)
}
```

### Step 3: Update `deriveActiveTrack` Function Signature

```kotlin
// Lines 213-226: Simplify
fun deriveActiveTrack(
    currentTab: NowPlayingTab,
    displayedTrackIndex: Int,
    queue: List<Track>,
    playingTrack: Track
): Track {
    if (currentTab == NowPlayingTab.PLAYER && queue.isNotEmpty()) {
        if (displayedTrackIndex in queue.indices) {
            return queue[displayedTrackIndex]
        }
    }
    return playingTrack
}
```

### Step 4: Update `handleNext` / `handlePrevious`

```kotlin
// Lines 351-374: handleNext
val handleNext = {
    if (queue.isNotEmpty() && pageCount > 1) {
        val fromIndex = pagerTargetIndex ?: pagerState.currentPage
        val targetIndex = (fromIndex + 1).coerceAtMost(pageCount - 1)
        if (targetIndex != fromIndex) {
            pagerTargetIndex = targetIndex  // ← pager animation only
            // displayedTrackIndex NOT set — stays frozen
            coroutineScope.launch {
                try {
                    pagerState.animateScrollToPage(
                        page = targetIndex,
                        animationSpec = tween(500, easing = FastOutSlowInEasing)
                    )
                } catch (_: Exception) {}
            }
        }
    }
    skipPlaybackJob?.cancel()
    skipPlaybackJob = coroutineScope.launch {
        onNextClick()  // yield removed
    }
}

// Lines 377-401: handlePrevious — same treatment, using (fromIndex - 1).coerceAtLeast(0)
```

### Step 5: Update snapshotFlow Settle Handler

```kotlin
// Lines 494-519: Add button-settle and swipe-settle handling for displayedTrackIndex
LaunchedEffect(pagerState, currentTab) {
    snapshotFlow { Pair(pagerState.isScrollInProgress, pagerState.settledPage) }
        .distinctUntilChanged()
        .collect { (isScrolling, settledPage) ->
            val curIndex = currentTrackIndexState.value
            val curQueue = currentQueueState.value
            if (currentTab == NowPlayingTab.PLAYER && !isScrolling) {
                if (userSwipedPager && !isProgrammaticScroll) {
                    // Swipe settle
                    userSwipedPager = false
                    if (curQueue.isNotEmpty() && settledPage in curQueue.indices && settledPage != displayedTrackIndex) {
                        displayedTrackIndex = settledPage  // ← commit visual identity
                        val targetTrack = curQueue[settledPage]
                        ArtworkPaletteCache.updateForTrack(context, targetTrack)
                        currentOnSelectQueueTrackState.value(settledPage)
                    }
                } else if (pagerTargetIndex != null) {
                    // Button-initiated animation settled
                    userSwipedPager = false
                    if (settledPage == pagerTargetIndex && curQueue.isNotEmpty() && settledPage in curQueue.indices) {
                        displayedTrackIndex = settledPage  // ← commit visual identity
                        pagerTargetIndex = null            // ← clear animation target
                        val targetTrack = curQueue[settledPage]
                        ArtworkPaletteCache.updateForTrack(context, targetTrack)
                        // Playback already dispatched via skipPlaybackJob — no need to call onSelectQueueTrack
                    }
                } else {
                    // Programmatic scroll settled or no-op
                    userSwipedPager = false
                    if (settledPage == curIndex) {
                        pagerTargetIndex = null
                    }
                }
            }
        }
}
```

### Step 6: Update LaunchedEffect Reconciliation

```kotlin
// Lines 404-458: External track change handler
LaunchedEffect(currentTrackIndex, track.id, currentTab) {
    // If a button animation is in progress, do NOT update displayedTrackIndex
    if (pagerTargetIndex != null) {
        // Playback is catching up to button press — let animation complete
        if (currentTrackIndex == pagerTargetIndex && !pagerState.isScrollInProgress) {
            // Pager already settled and playback caught up — commit
            displayedTrackIndex = currentTrackIndex
            pagerTargetIndex = null
        }
        return@LaunchedEffect
    }

    // No animation in progress — sync displayed track to playback
    if (displayedTrackIndex != currentTrackIndex) {
        displayedTrackIndex = currentTrackIndex
    }

    // Sync pager position
    if (pagerState.currentPage == currentTrackIndex || pagerState.targetPage == currentTrackIndex) {
        return@LaunchedEffect
    }
    if (currentTab == NowPlayingTab.PLAYER && pagerState.isScrollInProgress) {
        return@LaunchedEffect
    }
    if (currentTrackIndex in 0 until pageCount && pagerState.currentPage != currentTrackIndex) {
        isProgrammaticScroll = true
        try {
            if (currentTab == NowPlayingTab.PLAYER && !skipPagerAnimation) {
                val distance = abs(pagerState.currentPage - currentTrackIndex)
                if (distance == 1 && !pagerState.isScrollInProgress) {
                    pagerState.animateScrollToPage(
                        page = currentTrackIndex,
                        animationSpec = tween(500, easing = FastOutSlowInEasing)
                    )
                } else {
                    pagerState.scrollToPage(currentTrackIndex)
                }
            } else {
                pagerState.scrollToPage(currentTrackIndex)
            }
        } catch (_: Exception) {
        } finally {
            skipPagerAnimation = false
            isProgrammaticScroll = false
        }
    }
}
```

### Step 7: Update Safety Timeout

```kotlin
// Lines 471-476: Timeout now guards pagerTargetIndex, and also commits displayedTrackIndex
LaunchedEffect(pagerTargetIndex) {
    if (pagerTargetIndex != null) {
        delay(2500)
        // Safety: if pager never settled (edge case), commit wherever we are
        displayedTrackIndex = pagerState.currentPage
        pagerTargetIndex = null
    }
}
```

### Step 8: Add Pre-caching by `pagerTargetIndex`

```kotlin
// After existing line 555, add:
LaunchedEffect(pagerTargetIndex, queue) {
    val target = pagerTargetIndex
    if (target != null && queue.isNotEmpty()) {
        withContext(Dispatchers.IO) {
            val imageLoader = context.imageLoader
            // Pre-cache the target page AND one page beyond
            listOfNotNull(queue.getOrNull(target), queue.getOrNull(target + 1), queue.getOrNull(target - 1))
                .distinctBy { it.id }
                .forEach { t ->
                    val fullUrl = getHighResArtworkUrl(t.thumbnail)
                    val req = ImageRequest.Builder(context)
                        .data(fullUrl)
                        .size(600, 600)
                        .allowHardware(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    imageLoader.enqueue(req)
                }
        }
    }
}
```

### Step 9: Update `isSharedArtworkActive`

```kotlin
// Line 1321: Use displayedTrackIndex instead of pendingTargetIndex
val isSharedArtworkActive = (page == displayedTrackIndex && pageTrack.id == activeTrack.id)
```

### Step 10: Wire Continuous Background Blending

```kotlin
// Replace lines 657-667 with continuous palette blending:
val pagerOffset by remember {
    derivedStateOf { pagerState.currentPageOffsetFraction }
}

val adjacentPalette = remember(displayedTrackIndex, queue, isDynamicAccent) {
    if (!isDynamicAccent) null
    else {
        // Pre-fetch the next track's palette for blending
        val nextIdx = displayedTrackIndex + 1
        if (nextIdx in queue.indices) {
            ArtworkPaletteCache.getOrCreatePalette(context, queue[nextIdx])
                .takeIf { !it.isDefault }
        } else null
    }
}

val prevAdjacentPalette = remember(displayedTrackIndex, queue, isDynamicAccent) {
    if (!isDynamicAccent) null
    else {
        val prevIdx = displayedTrackIndex - 1
        if (prevIdx in queue.indices) {
            ArtworkPaletteCache.getOrCreatePalette(context, queue[prevIdx])
                .takeIf { !it.isDefault }
        } else null
    }
}

val blendedPalette = remember(extractedColors, adjacentPalette, prevAdjacentPalette, pagerOffset) {
    val absOffset = abs(pagerOffset)
    when {
        absOffset < 0.005f -> extractedColors
        pagerOffset > 0 && adjacentPalette != null ->
            lerpArtworkPalette(extractedColors, adjacentPalette, absOffset)
        pagerOffset < 0 && prevAdjacentPalette != null ->
            lerpArtworkPalette(extractedColors, prevAdjacentPalette, absOffset)
        else -> extractedColors
    }
}

val dynamicBgData = remember(activeTrack, blendedPalette) {
    DynamicBackgroundData(
        primaryArtworkUrl = activeTrack.thumbnail,
        secondaryArtworkUrl = null,
        swipeFraction = 0f,
        palette = blendedPalette
    )
}
```

### Step 11: Update `pageTrack` Selection in HorizontalPager

```kotlin
// Lines 1266-1271: Use displayedTrackIndex-based activeTrack consistently
val pageTrack = if (queue.isNotEmpty() && page in queue.indices) {
    val qTrack = queue[page]
    if (qTrack.id == activeTrack.id) activeTrack else qTrack
} else {
    activeTrack
}
```

No change needed here — `activeTrack` is already derived from `displayedTrackIndex` via the updated `deriveActiveTrack`.

---

## 8. Exact Files/Functions to Change

### [NowPlayingModal.kt](file:///c:/Users/shrey/OneDrive/Desktop/Auralis/android/app/src/main/java/com/auralis/music/ui/player/NowPlayingModal.kt)

| Line(s) | What | Change |
|---------|------|--------|
| 213-226 | `deriveActiveTrack` function | Change signature: remove `pendingTargetIndex: Int?` and `currentTrackIndex: Int`, add `displayedTrackIndex: Int`. Remove null-check branching, use `displayedTrackIndex` directly. |
| 335 | `var pendingTargetIndex` | Replace with `var pagerTargetIndex by remember { mutableStateOf<Int?>(null) }` AND `var displayedTrackIndex by remember { mutableIntStateOf(currentTrackIndex) }` |
| 345-346 | `deriveActiveTrack(... pendingTargetIndex ...)` | Use `deriveActiveTrack(currentTab, displayedTrackIndex, queue, track)` |
| 349-374 | `skipPlaybackJob` + `yield()` + `handleNext` | Remove `yield()`. Set `pagerTargetIndex` (not `displayedTrackIndex`). Keep `skipPlaybackJob?.cancel()` debouncing. |
| 377-401 | `handlePrevious` | Same treatment as `handleNext`. |
| 404-458 | LaunchedEffect reconciliation | Guard against updating `displayedTrackIndex` when `pagerTargetIndex != null`. When no animation pending, sync `displayedTrackIndex` to `currentTrackIndex`. |
| 471-476 | Safety timeout | Guard `pagerTargetIndex`, commit `displayedTrackIndex` on timeout. |
| 496-519 | snapshotFlow settle handler | Add button-settle path: set `displayedTrackIndex = settledPage`, clear `pagerTargetIndex`. Keep swipe-settle path setting `displayedTrackIndex`. |
| 523-555 (after) | Pre-caching | Add new `LaunchedEffect(pagerTargetIndex)` to pre-cache target page and neighbors. |
| 657-667 | `dynamicBgData` | Replace with continuous palette blending via `pagerState.currentPageOffsetFraction` and `lerpArtworkPalette`. |
| 1321 | `isSharedArtworkActive` | Use `displayedTrackIndex` instead of `pendingTargetIndex`. |

---

## 9. Exact Files/Functions NOT to Change

| File/Function | Reason |
|---------------|--------|
| `PlayerBackgroundRenderer.kt` — all of it | No structural changes needed. `animateArtworkPalette`, `lerpArtworkPalette`, `SeamlessGradientLayer`, `SingleGradientLayer`, `PlayerBackground`, all background styles — preserved as-is. Continuous blending is handled upstream in NowPlayingModal. |
| `PlayerTransitionMotion.kt` | Timing constants are correct. |
| `ArtworkCard.kt` | No changes. The black-frame fix is upstream (pre-caching). |
| `ArtworkPaletteCache.kt` | No changes. Cache, extraction, `getOrCreatePalette`, `getCachedOrFastExtract` — all correct. |
| `AuralisAudioPlayer.kt` | No changes. Playback pipeline is independent. |
| `MiniPlayer.kt` | No changes. MiniPlayer has its own palette flow. |
| `ClassicPlayerView.kt` | No changes unless it also uses `pendingTargetIndex` (verify). |
| HorizontalPager `key` strategy | `key = { page -> queue.getOrNull(page)?.id ?: page }` — CORRECT, do not change. |
| `beyondViewportPageCount = 1` | Keep at 1. The black-frame fix is via pre-caching, not by increasing viewport count (which would waste memory). |
| Speed Dial, MediaSession, Downloads/Offline, Wake Mode, Queue Drag/Drop, Navigation, Settings | All untouched. |
| `DynamicBackgroundData` data class | Keep the class as-is (fields preserved). Only change what's passed to it. |
| `boostColorVibrancy` | Untouched. |
| Swipe settle → track change flow | Preserved (just now also sets `displayedTrackIndex`). |

---

## 10. Focused Test Plan

### Test 1: Single Next Button — Visual Identity Stays Frozen

```
Setup: Queue = [A, B, C], playing A, pager on page 0
Action: Tap Next once
Assert Frame 0-249ms:
  - displayedTrackIndex == 0
  - activeTrack.id == A.id
  - pagerTargetIndex == 1
  - Title shows "Track A"
  - Background palette == Track A palette
Assert Frame ~500ms (pager settled):
  - displayedTrackIndex == 1
  - activeTrack.id == B.id
  - pagerTargetIndex == null
  - Title animates to "Track B"
```

### Test 2: Single Previous Button — Same Frozen Behavior

```
Setup: Queue = [A, B, C], playing B, pager on page 1
Action: Tap Previous once
Assert during animation:
  - displayedTrackIndex == 1
  - activeTrack.id == B.id
Assert after settle:
  - displayedTrackIndex == 0
  - activeTrack.id == A.id
```

### Test 3: Rapid A→B→C→D — Only Final Target Committed

```
Setup: Queue = [A, B, C, D, E], playing A, pager on page 0
Action: Tap Next 3 times rapidly (within 300ms)
Assert during animation:
  - displayedTrackIndex == 0 (FROZEN on A throughout)
  - pagerTargetIndex == 3 (final target)
  - activeTrack.id == A.id
  - Title: "Track A" (no intermediate B/C flicker)
  - Background: Track A palette (no intermediate jumps)
Assert after settle on page 3:
  - displayedTrackIndex == 3
  - activeTrack.id == D.id
  - pagerTargetIndex == null
  - Single clean A→D title/background transition
```

### Test 4: Swipe — Full Commit on Settle

```
Setup: Queue = [A, B], playing A, pager on page 0
Action: Simulate full swipe from page 0 to page 1
Assert during swipe:
  - displayedTrackIndex == 0
  - activeTrack.id == A.id
  - pagerTargetIndex == null (swipes don't set this)
Assert after settle:
  - displayedTrackIndex == 1
  - activeTrack.id == B.id
  - onSelectQueueTrack(1) was called
```

### Test 5: Partial Swipe Cancellation — No Identity Change

```
Setup: Queue = [A, B], playing A, pager on page 0
Action: Simulate drag partway toward page 1, then release (snap back)
Assert:
  - displayedTrackIndex == 0 throughout
  - activeTrack.id == A.id throughout
  - No onSelectQueueTrack call
  - Background returns to Track A palette
```

### Test 6: Mixed Swipe + Button — No Conflict

```
Setup: Queue = [A, B, C], playing A, pager on page 0
Action 1: Tap Next → pagerTargetIndex = 1, animation starts
Action 2: Before settle, user starts swiping
Expected: userSwipedPager = true, but pagerTargetIndex is still set
  The swipe settle handler should correctly distinguish and handle
Assert: Only one displayedTrackIndex update on final settle
```

### Test 7: Artwork Never Shows Black When Cached

```
Setup: Queue = [A, B, C, D], all artwork pre-cached in Coil memory
Action: Tap Next 3 times rapidly (A→D)
Assert for each page entering viewport:
  - ArtworkCard receives url with valid Coil memory cache hit
  - AsyncImage paints on first frame (isCachedInMemory = true → crossfade disabled)
  - Color(0xFF141414) background is never visible
```

### Test 8: Artwork Pre-caching Targets pagerTargetIndex

```
Setup: Queue = [A, B, C, D, E], pager on page 0
Action: Tap Next 3 times rapidly → pagerTargetIndex = 3
Assert:
  - LaunchedEffect(pagerTargetIndex=3) fires
  - Coil enqueue requests for queue[3].thumbnail (high-res), queue[4].thumbnail, queue[2].thumbnail
  - By the time pager reaches page 3, memory cache contains the artwork
```

### Test 9: Continuous Background Interpolation During Swipe

```
Setup: Queue = [A, B], Track A palette = red, Track B palette = blue
Action: Slowly drag from page 0 toward page 1
Assert at pagerOffset = 0.0: palette == red
Assert at pagerOffset = 0.3: palette == lerp(red, blue, 0.3)
Assert at pagerOffset = 0.7: palette == lerp(red, blue, 0.7)
Assert at pagerOffset = 1.0 (settled): palette == blue
  - No discrete jump at any point
```

### Test 10: Continuous Background During Button Animation

```
Setup: Queue = [A, B], Track A palette = red, Track B palette = blue
Action: Tap Next
Assert during 500ms animation:
  - palette continuously interpolates red→blue matching pager offset fraction
  - No discrete 240ms jump after settle
```

### Test 11: External Track Change (Song End) Syncs displayedTrackIndex

```
Setup: Queue = [A, B], playing A, pager on page 0, no animation pending
Action: Song A ends naturally → ExoPlayer advances to B → currentTrackIndex = 1
Assert:
  - pagerTargetIndex == null → LaunchedEffect enters "no animation" path
  - displayedTrackIndex = 1 (synced to playback)
  - Pager animateScrollToPage(1) triggered
  - Visual identity transitions smoothly
```

### Test 12: External Track Change During Button Animation — displayedTrackIndex Stays Frozen

```
Setup: Queue = [A, B, C], playing A, user taps Next → pagerTargetIndex = 1
Action: Before pager settles, currentTrackIndex changes to 1 (playback catches up)
Assert:
  - displayedTrackIndex remains 0 (frozen)
  - LaunchedEffect sees pagerTargetIndex != null → does NOT update displayedTrackIndex
  - On pager settle: displayedTrackIndex = 1, pagerTargetIndex = null
```

---

## 11. Summary of Previous Diagnosis Errors

| Previous Claim | Status | Correction |
|----------------|--------|------------|
| `committedTargetIndex` + fallback to `playingTrack` is sufficient | **INCOMPLETE** | Fallback leaks playback identity during animation. Need explicit `displayedTrackIndex` that never falls through. |
| `key(pageTrack.id)` causes Coil cancellation → black frame | **INCORRECT** | `key()` is stable per page. Black frame is caused by pages entering viewport for the first time without pre-cached artwork. |
| `yield()` is "actively harmful" | **PARTIALLY CORRECT** | `yield()` is unnecessary with `displayedTrackIndex` fix, but the debouncing via `skipPlaybackJob?.cancel()` IS valuable and must be preserved. The `yield()` was not the primary cause of visual issues — `pendingTargetIndex` immediately setting visual identity was. |
| Background fix: "wire `swipeFraction` from `pagerState.currentPageOffsetFraction`" | **INSUFFICIENT** | The existing `swipeFraction`/`secondaryArtworkUrl` mechanism in `PlayerBackground` only fades gradient alpha — it doesn't blend palettes. The correct approach is to pre-blend palettes via `lerpArtworkPalette` in NowPlayingModal and pass the result as the single palette. |
| `animateArtworkPalette` 240ms tween is "well-designed" | **CORRECT BUT INCOMPLETE** | It handles committed transitions well, but continuous pager-driven blending bypasses it during gesture/animation for truly smooth per-frame interpolation. |
