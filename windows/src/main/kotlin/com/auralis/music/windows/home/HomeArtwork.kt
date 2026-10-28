package com.auralis.music.windows.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.auralis.music.ui.components.getOptimizedThumbnailUrl
import kotlinx.coroutines.delay

private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
private val videoThumbnailPattern = Regex("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/")

/** Preserve the supplied artwork, including signed URLs, before trying exact-video fallbacks. */
internal fun homeArtworkSources(url: String?, trackId: String?): List<String> {
    val raw = url?.trim()?.takeIf { it.isNotEmpty() }?.let {
        (if (it.startsWith("//")) "https:$it" else it)
            .replace(Regex("""(?:hq)+default\.jpg"""), "hqdefault.jpg")
    }
    val host = raw?.let { runCatching { java.net.URI(it).host?.lowercase() }.getOrNull() }
    val thumbnailVideo = if (host in listOf("i.ytimg.com", "img.youtube.com", "i1.ytimg.com"))
        raw?.let { videoThumbnailPattern.find(it)?.groupValues?.get(1) } else null
    val video = thumbnailVideo ?: trackId?.takeIf { videoIdPattern.matches(it) }
    return buildList {
        if (raw != null) {
            add(getOptimizedThumbnailUrl(raw) ?: raw)
            add(raw)
            if (host?.endsWith("googleusercontent.com") == true || host?.endsWith("ggpht.com") == true) {
                add(raw.replace(Regex("=w\\d+-h\\d+.*"), "=w400-h400-l90-rj")
                    .replace(Regex("=s\\d+.*"), "=s400-c"))
            }
        } else if (video != null) add("https://i.ytimg.com/vi/$video/hq720.jpg")
        if (video != null) {
            add("https://i.ytimg.com/vi/$video/hqdefault.jpg")
            add("https://i.ytimg.com/vi/$video/mqdefault.jpg")
        }
    }.distinct()
}

/**
 * Decode size for Home's covers (150dp tiles at up to 250% scaling), the same as the shared
 * renderer's tiles so a cover seen on Home is already in memory on an album or playlist page.
 */
private const val HOME_ARTWORK_PX = com.auralis.music.ui.components.DesktopArtworkRenderer.TILE_PX

/** Starts loading Home's covers (artwork URL, track id) as [HomeArtwork] will request them. */
internal fun prefetchHomeArtwork(items: List<Pair<String?, String?>>) {
    val context = coil3.PlatformContext.INSTANCE
    val loader = coil3.SingletonImageLoader.get(context)
    items.mapNotNull { (url, trackId) -> homeArtworkSources(url, trackId).firstOrNull() }.distinct().forEach {
        loader.enqueue(ImageRequest.Builder(context).data(it).size(HOME_ARTWORK_PX).build())
    }
}

private val recoveredArtwork = object : java.util.LinkedHashMap<String, String>(64, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
}

@Composable
internal fun HomeArtwork(url: String?, modifier: Modifier, trackId: String? = null, contentDescription: String? = null) {
    val key = url.orEmpty() + "\n" + trackId.orEmpty()
    val sources = remember(key) {
        val candidates = homeArtworkSources(url, trackId)
        val known = synchronized(recoveredArtwork) { recoveredArtwork[key] }?.takeIf { it in candidates }
        if (known == null) candidates else listOf(known) + candidates.filter { it != known }
    }
    HomeArtworkImage(sources, modifier, contentDescription) { source -> synchronized(recoveredArtwork) { recoveredArtwork[key] = source } }
}

/** One retry for a transient failure, then a finite list of alternate sources. */
@Composable
internal fun HomeArtworkImage(sources: List<String>, modifier: Modifier, contentDescription: String? = null, onLoaded: (String) -> Unit = {}) {
    val context = LocalPlatformContext.current
    val loaded = rememberUpdatedState(onLoaded)
    val currentSources = rememberUpdatedState(sources)
    var attempt by remember(sources) { mutableIntStateOf(0) }
    var failed by remember(sources) { mutableStateOf(false) }
    val lastAttempt = sources.size // first source is attempted twice
    LaunchedEffect(sources, attempt, failed) {
        if (failed && sources.isNotEmpty() && attempt < lastAttempt) {
            delay(if (attempt == 0) 350L else 100L)
            failed = false
            attempt++
        }
    }
    val requestAttempt = attempt
    val source = sources.getOrNull((attempt - 1).coerceAtLeast(0))
    val request = remember(context, source, attempt) {
        source?.let {
            ImageRequest.Builder(context).data(it).size(HOME_ARTWORK_PX).apply {
                // A corrupt cached response must not poison the retry.
                if (attempt == 1) {
                    memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    diskCachePolicy(CachePolicy.WRITE_ONLY)
                }
            }.build()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
        if (request != null) key(sources, attempt) {
            // Coil's model equality can treat requests with the same URL but new cache policies
            // as identical. Recreate the painter so a failed same-URL request actually restarts.
            AsyncImage(model = request, contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                onSuccess = { if (attempt == requestAttempt && sources == currentSources.value && source != null) loaded.value(source) },
                onError = { if (attempt == requestAttempt) failed = true })
        }
    }
}
