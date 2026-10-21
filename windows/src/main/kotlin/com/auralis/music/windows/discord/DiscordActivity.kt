package com.auralis.music.windows.discord

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.auralis.music.data.network.RecordingMatches
import org.json.JSONArray
import org.json.JSONObject

internal const val AURALIS_URL = "https://github.com/Shreyanshh071/Auralis"
internal data class DiscordTrack(val track: Track, val artistId: String? = null) {
    private val video get() = if (track.source == TrackSource.LOCAL) null else
        RecordingMatches.getMatchedVideoId(track.id) ?: track.id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    fun text(slot: String): String? = when (slot) {
        "title" -> track.title; "artist" -> track.artist; "album" -> track.album
        "artist_album" -> listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.album?.takeIf { it.isNotBlank() }).joinToString(" — ")
        else -> null
    }?.takeIf { it.isNotBlank() }
    fun link(slot: String): String? {
        if (track.source == TrackSource.LOCAL) return null
        return when (slot) {
            "title", "listen" -> video?.let { "https://music.youtube.com/watch?v=$it" }
            "album" -> track.albumId?.takeIf { it.startsWith("MPRE") || it.startsWith("OLAK") || it.startsWith("VL") }
                ?.let { "https://music.youtube.com/browse/$it" }
            "artist", "artist_album" -> artistId?.takeIf { it.startsWith("UC") }?.let { "https://music.youtube.com/channel/$it" }
            else -> null
        }
    }
}

/** One builder for the live IPC payload and the popup preview. No authentication fields. */
internal fun discordActivity(track: DiscordTrack?, cfg: DiscordCardSettings, playing: Boolean, played: Boolean,
    positionMs: Long, durationMs: Long, nowMs: Long = System.currentTimeMillis()): JSONObject? {
    if (!cfg.enabled || track == null || (!playing && !(cfg.showPaused && played))) return null
    val name = cfg.appName.trim().ifBlank { "Auralis" }.take(128)
    val result = JSONObject().put("type", 2).put("name", name)
    if (cfg.hideDetails) return result.put("status_display_type", 0)
    val line1 = if (track.text(cfg.line1) == null) "title" else cfg.line1
    result.put("details", track.text(line1).orEmpty().take(128))
    track.text(cfg.line2)?.let { result.put("state", it.take(128)) }
    result.put("status_display_type", when (cfg.statusLine) { "app" -> 0; "line1" -> 1; else -> 2 })
    if (cfg.linkLine1) track.link(line1)?.let { result.put("details_url", it) }
    if (cfg.linkLine2) track.link(cfg.line2)?.let { result.put("state_url", it) }
    if (cfg.progress && playing && durationMs > 0) {
        val start = nowMs - positionMs.coerceIn(0, durationMs)
        result.put("timestamps", JSONObject().put("start",start).put("end",start + durationMs))
    }
    val art = track.track.thumbnail.takeIf { it.startsWith("https://") }
    if (cfg.cover && art != null) {
        val assets = JSONObject().put("large_image",art)
        track.text(cfg.line3)?.let { assets.put("large_text",it.take(128)) }
        if (cfg.linkCover) (track.link("album") ?: track.link("title"))?.let { assets.put("large_url", it) }
        if (cfg.badge) assets.put("small_image", "https://auralis-self-nu.vercel.app/logo.png").put("small_text",name)
        result.put("assets", assets)
    }
    val buttons = listOf(cfg.button1,cfg.button2).mapNotNull { kind ->
        val url = if (kind == "app") AURALIS_URL else track.link(kind)
        val label = when (kind) { "listen" -> "Listen on YouTube Music"; "album" -> "View album"; "artist" -> "View artist"; "app" -> "Get Auralis"; else -> null }
        if (url == null || label == null) null else label to url
    }.distinct()
    if (buttons.isNotEmpty()) result.put("buttons", JSONArray().apply { buttons.take(2).forEach { (label,url) -> put(JSONObject().put("label",label).put("url",url)) } })
    return result
}
