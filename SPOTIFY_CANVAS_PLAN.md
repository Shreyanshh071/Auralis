# Spotify Canvas: Direct CDN MP4 with Manifest Fallback, Disk Indexing & Zero-Config Plan

This document outlines the finalized architecture and implementation specification for adding a direct CDN MP4 fast path with manifest fallback, persistent disk indexing, playback validation, and an Appearance setting for Spotify Canvas in Auralis Windows.

---

## 1. Zero Setup / No-Login Requirement

> [!IMPORTANT]
> **Strict UX Requirement**: The user **never** has to log in to Spotify, enter an API key, or provide credentials.
> - Spotify Canvas retrieval is completely anonymous and automatic.
> - Metadata is retrieved via SpicyLyrics' public canvasstore endpoint.
> - Direct MP4 is fetched anonymously from Spotify's public CDN (`canvaz.scdn.co`).
> - Manifest fallback uses the existing anonymous embed session token scraping from `open.spotify.com/embed`.
> - **Appearance Setting**: Users can simply toggle Spotify Canvas on/off under **Appearance Settings** with zero configuration.

---

## 2. Problem & Context

Currently, [`SpotifyCanvasLookup.kt`](windows/src/main/kotlin/com/auralis/music/windows/player/SpotifyCanvasLookup.kt) skips directly to the manifest-based chunk stitching approach:
1. Queries SpicyLyrics (`https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/$trackId`).
2. Extracts `fileId`.
3. Scrapes an anonymous token from `https://open.spotify.com/embed/track/$trackId`.
4. Calls `https://spclient.wg.spotify.com/manifests/v9/json/sources/$fileId/options/supports_drm`.
5. Parses profiles, fetches multiple `.ts` chunks sequentially, and stitches them into `$fileId-hq.ts`.

While the manifest method works, it requires **8–12 HTTP round-trips**, token scraping, and manual chunk reassembly.

The SpicyLyrics API response already includes a direct CDN URL in many cases:
```json
{
  "url": "https://canvaz.scdn.co/upload/artist/.../video/c69226559d614236ac21f89dc808cb5c.cnvs.mp4",
  "type": "VIDEO_LOOPING_RANDOM",
  "fileId": "4745a3f23770320a86497b1808aaa336",
  "uri": "spotify:canvas:62HgP7oHidOS4ZI0DifLVa"
}
```

---

## 3. Key Refinements & Architecture

### **A. Persistent Disk Index (`trackId -> fileId`)**
* **Issue**: Caching only by `$fileId-hq.*` means on every app restart, the app must call SpicyLyrics just to discover what `fileId` corresponds to `trackId`.
* **Fix**: Maintain a persistent disk index (`cache/spotify-canvas/index.properties`). On lookup:
  - If `trackId` is indexed to `fileId`, and the cached file (`$fileId-hq.mp4` or `$fileId-hq.ts`) exists and is non-empty, **return it immediately with zero network requests**.

### **B. Tiered Fallback Hierarchy**

```
[Request Canvas for trackId]
        │
        ▼
[Check Appearance Setting: spotifyCanvasEnabled] ──(Disabled)──► Return null
        │ (Enabled)
        ▼
[Check Persistent Disk Index: trackId -> fileId]
   │
   ├─(Found & File on Disk)──► Return cached path immediately (0 network calls)
   │
   ▼ (Index Miss / File Missing)
[Fetch SpicyLyrics Canvas Metadata]
   │
   ├─► Update persistent index: trackId -> fileId
   │
   ├──────────────────────────────────────────┐
   │ Has direct .cnvs.mp4 URL                 │ Missing direct URL
   ▼                                          │
[Tier 1: Direct CDN Stream (Fast Path)]       │
   - Single GET to canvaz.scdn.co             │
   - Stream to temp file; sanity check size   │
   - Atomically move to $fileId-hq.mp4        │
        │                                     │
        ├─(Success: HTTP 200 & valid size)──┐ │
        │                                   │ │
        ▼ (404 / Non-200 / Corrupt / Small) ▼ │
[Tier 2: Manifest & Chunk Stitching Fallback] ◄┘
   - Scrape token from embed
   - Query spclient manifest
   - Download & stitch .ts chunks to $fileId-hq.ts
        │
        ├─(Success)─► Cache & Return
        │
        ▼ (Failed)
[Tier 3: Graceful Degradation]
   - Return null
   - Downstream UI leaves large video stage inactive; keeps album artwork visible
```

### **C. Playback Confirmation (Decoding Validation)**
* HTTP 200 only confirms the download finished, not that libVLC can decode the file.
* If libVLC fails to initialize or produce a frame for the candidate file within a timeout:
  - Invalidate/delete the bad MP4 candidate.
  - Automatically fall back to the manifest `.ts` path.
* Keep the album artwork visible while the video is loading or decoding (fades in only upon first frame).

### **D. Stage Scope Separation**
* **Album Cover Area**: Holds static artwork and square/portrait animated covers from Apple Music, Tidal, or Curated.
* **Large Lyrics Stage**: Activates *only* when a 9:16 Spotify Canvas starts playing and delivering frames, preventing album cover animations from blowing up onto the large stage.

---

## 4. Appearance Settings Integration

In `windows/src/main/kotlin/com/auralis/music/windows/player/AnimatedCovers.kt` and `windows/src/main/kotlin/com/auralis/music/windows/settings/DesktopSettingsPages.kt`:

1. **`CanvasPrefs` update**:
   ```kotlin
   internal data class CanvasPrefs(
       val enabled: Boolean = true,
       val source: CanvasSource = CanvasSource.AUTO,
       val spotifyCanvas: Boolean = true // New toggle, enabled by default
   )
   ```
2. **UI in `AnimatedCoversSettings()`**:
   Add a toggle switch under Animated Covers:
   - Label: `"Spotify Canvas on lyrics stage"`
   - Subtitle: `"Stream artist video loops during lyrics without requiring a Spotify login"`
   - Default: `true`

---

## 5. Implementation Specification for `SpotifyCanvasLookup.kt`

Modify [`windows/src/main/kotlin/com/auralis/music/windows/player/SpotifyCanvasLookup.kt`](file:///c:/Users/shrey/OneDrive/Desktop/Auralis/windows/src/main/kotlin/com/auralis/music/windows/player/SpotifyCanvasLookup.kt):

### 1. Disk Index & Cache Check
```kotlin
private val folder by lazy { File(desktopAppDataDir(), "cache/spotify-canvas").apply { mkdirs() } }
private val diskIndex by lazy {
    com.auralis.music.util.PropertiesFileKeyValueStore(File(folder, "index.properties"))
}

private fun findCachedFile(fileId: String): String? {
    val mp4 = File(folder, "$fileId-hq.mp4")
    if (mp4.isFile && mp4.length() > 64 * 1024) return mp4.absolutePath
    val ts = File(folder, "$fileId-hq.ts")
    if (ts.isFile && ts.length() > 64 * 1024) return ts.absolutePath
    return null
}
```

### 2. Fast-Path Lookup in `find(trackId)`
Before calling SpicyLyrics:
```kotlin
val cachedFileId = diskIndex.getString(trackId, "")
if (cachedFileId.isNotBlank()) {
    findCachedFile(cachedFileId)?.let { return@withLock it }
}
```

### 3. Direct CDN Download (`downloadDirect`)
```kotlin
private fun downloadDirect(url: String, fileId: String): String? {
    val uri = URI(url)
    require(uri.scheme == "https" && (uri.host.endsWith(".scdn.co") || uri.host.endsWith(".spotifycdn.com")))
    val target = File(folder, "$fileId-hq.mp4")
    val temp = File.createTempFile(fileId, ".mp4.partial", folder)
    try {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val length = body.contentLength()
            // Sanity check: must be at least 100 KB and within 64 MB
            if (length in 1..100_000 || length > 64 * 1024 * 1024) return null
            temp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (temp.length() < 100_000) return null
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target.absolutePath
    } catch (_: Exception) {
        return null
    } finally {
        temp.delete()
    }
}
```

### 4. Combined Download with Manifest Fallback
```kotlin
private fun download(trackId: String, fileId: String, directUrl: String?): String {
    // 1. Try Direct CDN MP4 if available
    if (!directUrl.isNullOrBlank()) {
        val directPath = runCatching { downloadDirect(directUrl, fileId) }.getOrNull()
        if (directPath != null) return directPath
    }
    // 2. Fall back to Manifest .ts Chunk Stitcher
    return downloadViaManifest(trackId, fileId)
}
```
And save mapping to `diskIndex.putStrings(mapOf(trackId to fileId))` upon a successful fetch.

### 5. Invalidation Hook for Decoding Failures
Add an invalidation hook callable from `AnimatedCover` or `CanvasVideoEngine` if VLC fails to decode:
```kotlin
fun invalidateCandidate(trackId: String, filePath: String) {
    runCatching {
        File(filePath).delete()
        val fileId = diskIndex.getString(trackId, "")
        if (fileId.isNotBlank()) {
            cache.remove(trackId)
        }
    }
}
```

---

## 6. Verification

1. **Test `trackId -> fileId` Disk Index**:
   Play a song with canvas, restart the app, play the song again. Verify in logs: zero network calls to SpicyLyrics; instant playback from disk.
2. **Test Direct CDN MP4**:
   Test on a track with direct URL (e.g. `spotify:track:4cOdK2wGLETKBW3PvgPWqT`). Verify `$fileId-hq.mp4` is saved and plays with 1 single HTTP request.
3. **Test 404 Manifest Fallback**:
   Test on a track where direct MP4 404s (e.g. Ariana Grande). Verify it falls back to manifest `.ts` and plays smoothly without error.
4. **Test Artwork Preservation**:
   Verify static album art remains visible until the first video frame decodes.
5. **Test Appearance Setting**:
   Verify the toggle in Appearance Settings disables/enables Spotify Canvas without needing any login or API key.
