package com.auralis.music.data.network.discord

import com.auralis.music.ui.components.getHighResArtworkUrl
import java.net.URLEncoder

/** Discord needs a public image: it cannot apply the player's ContentScale.Crop itself. */
internal fun discordArtworkUrl(thumbnail: String, trackId: String): String {
    val source = thumbnail.trim().let {
        when {
            it.startsWith("https://") -> it
            it.startsWith("http://") -> it.replaceFirst("http://", "https://")
            it.startsWith("//") -> "https:$it"
            // Do not invent YouTube artwork for local files or imported provider identities.
            it.isBlank() && Regex("[A-Za-z0-9_-]{11}").matches(trackId) ->
                "https://i.ytimg.com/vi/$trackId/hq720.jpg"
            else -> return ""
        }
    }
    val artwork = getHighResArtworkUrl(source) ?: return ""
    // Album CDNs already provide square covers. Keep their original public delivery path.
    val host = runCatching { java.net.URI(artwork).host.orEmpty() }.getOrDefault("")
    if (host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com") ||
        host.endsWith("mzstatic.com") || host == "i.scdn.co") return artwork

    // Use the same centre square as the player, without padding. In particular, never send
    // YouTube's raw 4:3 hqdefault thumbnail, whose letterboxing survives Discord's rendering.
    val square = squareArtworkUrl(artwork)
    val youtubeId = if (host == "i.ytimg.com" || host == "img.youtube.com") {
        Regex("/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/").find(artwork)?.groupValues?.get(1)
    } else null
    // Older videos sometimes have no HD still. The 16:9 fallback has no 4:3 padding,
    // and is also cropped to a square before Discord receives it.
    return if (youtubeId != null) {
        val fallback = squareArtworkUrl("https://i.ytimg.com/vi/$youtubeId/mqdefault.jpg")
        "$square&default=${URLEncoder.encode(fallback, "UTF-8")}"
    } else square
}

private fun squareArtworkUrl(source: String): String =
    "https://wsrv.nl/?url=${URLEncoder.encode(source, "UTF-8")}" +
        // Cover defaults to centre alignment; omit redundant options to stay within
        // Discord's 300-character image field even with the encoded fallback URL.
        "&w=600&h=600&fit=cover"
