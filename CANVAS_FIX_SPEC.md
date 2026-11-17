# Spotify Canvas: Architecture Diagnosis & Implementation Fix Spec

> **For Claude / Lead Engineer:**
> This document outlines the exact root causes of Spotify Canvas failures across clean installs (e.g., contributor laptops or after wiping `%APPDATA%\Auralis\cache\spotify-canvas`), why a visible vertical bar/seam appeared when fixing video quality, and the step-by-step code changes required to fix both issues permanently.

---

## 1. Executive Summary: What Went Wrong

### Issue A: Canvases Disappeared After Clearing Cache (Failed on Contributor Laptop)
1. **The Single Point of Failure (SPOF):**
   * Spotify's video CDN (`canvaz.scdn.co/.../<fileId>.cnvs.mp4`) and HQ manifests (`spclient.wg.spotify.com/manifests/v9/json/sources/<fileId>/options/supports_drm`) require the **32-character hexadecimal `fileId`**, not Spotify's 22-character `trackId`.
   * In `SpotifyCanvasLookup.kt` (line 48), Auralis relies exclusively on an external third-party scraper:
     `https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/$trackId`
   * SpicyLyrics' database is incomplete. For many songs (such as *hate that i made you love me* `20jbSiX29FDX4oQxBXyUEi` or *Bitter Sweet Symphony* `57iDDD9N9tTWe75x6pFDTw`), SpicyLyrics returns:
     ```json
     {"data": {"20jbSiX29FDX4oQxBXyUEi": null}}
     ```
2. **The 24-Hour Lockout:**
   * In `SpotifyCanvasLookup.kt` (lines 61-63), whenever SpicyLyrics returns `null`, Auralis immediately does:
     ```kotlin
     if (item == null || item.optString("type") !in setOf("VIDEO", "VIDEO_LOOPING", "VIDEO_LOOPING_RANDOM")) {
         misses[trackId] = now + DAY_MS // 24-hour lockout!
         return@withLock null
     }
     ```
   * Auralis puts the track into an in-memory 24-hour negative cache (`misses[trackId] = now + DAY_MS`) and aborts immediately without attempting any fallback.
3. **Why It Worked Earlier on the Primary Laptop:**
   * Before the cache was wiped, `%APPDATA%\Auralis\cache\spotify-canvas\index.properties` already had the `fileId` mapped to the track from prior sessions, and the video was already downloaded on disk.
   * Line 43 checks local disk first:
     ```kotlin
     indexedFileId(trackId)?.let { cachedFile(it)?.let { file -> return@withLock file.absolutePath } }
     ```
   * As long as the file existed locally, SpicyLyrics was never contacted. The moment the cache was cleared (and on any contributor's clean machine), Auralis had to ask SpicyLyrics, received `null`, and permanently failed.

---

### Issue B: The Muddy Brown/Orange Vertical Seam / Bar (Screenshot 5)
1. In `PlayerScreen.kt` (line 141), `AnimatedCover` is constrained to:
   ```kotlin
   Modifier.fillMaxWidth(.51f).fillMaxHeight()
   ```
2. In `AnimatedCovers.kt` (lines 316-324), a horizontal `DstIn` gradient mask is applied to the video:
   ```kotlin
   Brush.horizontalGradient(
       .76f to Color.Black,
       .82f to Color.Black.copy(alpha = .86f),
       .88f to Color.Black.copy(alpha = .5f),
       .94f to Color.Black.copy(alpha = .14f),
       1f to Color.Transparent
   )
   ```
   Over `fillMaxWidth(.51f)`, `.76f` equals **38.7% of total screen width**.
3. Underneath the video, `PlayerBackdrop("Dynamic", motionAura)` renders a blurred fluid mesh generated from sampled video frames (in *Love Me Not*, this sampled warm orange and brown tones).
4. **The Visual Bug:** Between 38.7% and 51% of screen width, the video fades out into transparency, exposing the warm dynamic blurred mesh underneath. This creates a visible vertical colored band/stripe right down the center between the dark video on the left and the lyrics stage on the right.
5. In addition, the vertical dimming overlay in `PlayerScreen.kt` (lines 145-148) darkens the screen with heavy black overlays, muting contrast and making the seam appear muddy.

---

### Issue C: Contributor Machine Prerequisites
* **64-bit VLC**: `CanvasVideoEngine.kt` uses `libVLC`. If 64-bit VLC is not installed on Windows, `CanvasVideoEngine.available` returns `false`, silently disabling all video playback.
* **Window Size**: `PlayerScreen.kt` (line 132) checks `maxWidth > 964.dp`. If the window is unmaximized on a 1080p screen with 125% or 150% Windows display scaling, canvas is disabled.

---

## 2. Implementation Action Plan for Claude

### File 1: `windows/src/main/kotlin/com/auralis/music/windows/player/SpotifyCanvasLookup.kt`

1. **Reduce Negative Cache Miss TTL:**
   * Change `DAY_MS` (86,400,000L) to a short 5-minute timeout for network or missing item errors:
   ```kotlin
   private const val MISS_RETRY_MS = 300_000L // 5 minutes instead of 24 hours
   ```
   * If SpicyLyrics returns `null` or 429, set `misses[trackId] = now + MISS_RETRY_MS`.
2. **Multi-Source Resolver Fallback:**
   * When SpicyLyrics returns `null` or fails, do not immediately return `null`. Fall back to:
     1. Resolving the canvas via secondary reverse-proxy or Spotify partner query.
     2. If Spotify Canvas has no `fileId`, allow the player to fall back gracefully to **Apple Music Motion / Tidal Animated Artwork** via existing `CanvasLookup.find(track, prefs.source)`.
3. **Preserve Quality Enforcement (Direct vs. Manifest):**
   * Keep the minimum direct byte size threshold (`MIN_DIRECT_BYTES = 1_500_000L`).
   * If a direct `.cnvs.mp4` download is a low-bitrate mobile preview (< 1.5 MB), reject it and fall back to `downloadViaManifest(trackId, fileId)` which stitches the full 1080p TS stream.

---

### File 2: `windows/src/main/kotlin/com/auralis/music/windows/player/AnimatedCovers.kt`

1. **Fix the Horizontal Edge Mask (Eliminate the Seam):**
   * Keep the video solid across almost the entire width of the container before applying a subtle, feathered falloff at the very edge:
   ```kotlin
   val edgeBlend = if (canvasOnly) Modifier
       .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
       .drawWithContent {
           drawContent()
           drawRect(
               Brush.horizontalGradient(
                   0.88f to Color.Black,
                   0.94f to Color.Black.copy(alpha = 0.70f),
                   0.98f to Color.Black.copy(alpha = 0.20f),
                   1.00f to Color.Transparent
               ),
               blendMode = BlendMode.DstIn
           )
       } else Modifier
   ```
2. **Aspect Ratio Preservation:**
   * Maintain native 9:16 portrait ratio without excessive center-crop zoom, so the video stays crisp and 1:1 pixel-dense on high-resolution displays.

---

### File 3: `windows/src/main/kotlin/com/auralis/music/windows/player/PlayerScreen.kt`

1. **Fix Backdrop Clash:**
   * Ensure that behind the video stage, the background smoothly integrates with the video's dark letterboxing or native tone, rather than exposing bright contrasting fluid mesh directly under the feathered seam.
2. **Lighten / Refine Vertical Dimming Box (lines 145-148):**
   * Reduce the heavy black vertical gradient overlay so the canvas preserves native color vibrancy, dynamic range, and sharpness.

---

### Summary Checklist for Contributor Verification
- [ ] 64-bit VLC installed on machine (`libVLC` detected).
- [ ] Window maximized (`maxWidth > 964.dp`).
- [ ] **Settings → Appearance → Spotify Canvas** enabled.
- [ ] Test track with verified canvas (e.g. *Love Me Not*, *Christian Kuria*, *Bitter Sweet Symphony*).
