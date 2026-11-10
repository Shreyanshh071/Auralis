package com.auralis.music.data.network

import com.auralis.music.domain.model.ArtistReleasesEndpoint
import com.auralis.music.domain.model.PlaylistResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * The full-list link of an artist-page shelf (Albums, Singles & EPs), with the opaque params the
 * shelf supplies. The row on the artist page is a preview of about ten; this opens all of them.
 */
fun artistMoreEndpoint(shelf: JSONObject): ArtistReleasesEndpoint? {
    val header = shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        ?: shelf.optJSONObject("header")?.optJSONObject("musicShelfBasicHeaderRenderer")
    val endpoint = shelf.optJSONObject("bottomEndpoint")?.optJSONObject("browseEndpoint")
        ?: header?.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
            ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        ?: (header?.optJSONObject("title") ?: shelf.optJSONObject("title"))
            ?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        ?: return null
    val browseId = endpoint.optString("browseId").takeIf { it.isNotBlank() } ?: return null
    return ArtistReleasesEndpoint(browseId, endpoint.optString("params").takeIf { it.isNotBlank() })
}

/** Every item on a full-list page, which uses a grid (or a list) rather than the short carousel. */
fun artistCatalogueItems(root: JSONObject): JSONArray = JSONArray().apply {
    fun visit(value: Any?) {
        when (value) {
            is JSONObject -> {
                if (value.has("musicResponsiveListItemRenderer") || value.has("musicTwoRowItemRenderer")) put(value)
                // Sort chips reload the same list; their items are not releases.
                else value.keys().forEach { key -> if (key != "header") visit(value.opt(key)) }
            }
            is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
        }
    }
    visit(root.optJSONObject("contents"))
    visit(root.optJSONObject("continuationContents"))
}

/** The grid's next page, if the artist has more releases than one response holds. */
fun artistCatalogueContinuation(root: JSONObject): String? {
    var found: String? = null
    fun visit(value: Any?) {
        if (found != null) return
        when (value) {
            is JSONObject -> {
                value.optJSONArray("continuations")?.optJSONObject(0)?.optJSONObject("nextContinuationData")
                    ?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { found = it; return }
                value.keys().forEach { key -> if (key != "header") visit(value.opt(key)) }
            }
            is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
        }
    }
    visit(root.optJSONObject("contents"))
    visit(root.optJSONObject("continuationContents"))
    return found
}

/** One album or single from an artist's release grid: "Album • 2026" in the subtitle names its type. */
fun artistReleaseFromItem(item: JSONObject, artistName: String, thumbnail: (JSONArray?) -> String): PlaylistResult? {
    val twoRow = item.optJSONObject("musicTwoRowItemRenderer") ?: return null
    val title = twoRow.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
        ?.takeIf { it.isNotBlank() } ?: return null
    val browse = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return null
    val pageType = browse.optJSONObject("browseEndpointContextSupportedConfigs")
        ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
    if (!pageType.isNullOrBlank() && pageType != "MUSIC_PAGE_TYPE_ALBUM") return null
    val id = browse.optString("browseId").takeIf { it.isNotBlank() } ?: return null
    val runs = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
    val subtitle = (0 until (runs?.length() ?: 0)).joinToString("") { runs?.optJSONObject(it)?.optString("text").orEmpty() }
    val type = subtitle.substringBefore('•').trim().lowercase().takeIf { it in setOf("album", "single", "ep") }
    val thumb = thumbnail(twoRow.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
        ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
    // author stays the artist: saving, sharing and "View artist" read it.
    return PlaylistResult(id = id, title = title, thumbnail = thumb.ifBlank { null }, author = artistName, releaseType = type)
}
