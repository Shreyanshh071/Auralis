package com.auralis.music.data.network

import org.json.JSONArray
import org.json.JSONObject

/** Finds the cover art in a YouTube Music browse/playlist response and upgrades it to full size. */
object PlaylistThumbnails {
    fun upgradeCoverUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.startsWith("//")) url = "https:$url"
        return when {
            url.contains("googleusercontent.com") || url.contains("ggpht.com") -> {
                url.replace(Regex("""=w\d+-h\d+.*"""), "=w1200-h1200-l90-rj")
                    .replace(Regex("""=s\d+.*"""), "=s1200-c")
            }
            url.contains("i.ytimg.com") || url.contains("img.youtube.com") -> {
                val noQuery = url.substringBefore('?')
                noQuery.replace("default.jpg", "hqdefault.jpg")
                    .replace("mqdefault.jpg", "hqdefault.jpg")
                    .replace("hq720.jpg", "hqdefault.jpg")
            }
            else -> url
        }
    }

    fun extractBestThumbnailUrl(thumbnails: JSONArray?): String? {
        if (thumbnails == null || thumbnails.length() == 0) return null
        var bestUrl: String? = null
        var maxArea = 0L

        for (i in 0 until thumbnails.length()) {
            val item = thumbnails.optJSONObject(i) ?: continue
            val u = item.optString("url").trim()
            if (u.isBlank()) continue
            val w = item.optLong("width", 0L)
            val h = item.optLong("height", 0L)
            val area = w * h
            if (area >= maxArea || bestUrl == null) {
                maxArea = area
                bestUrl = u
            }
        }

        if (bestUrl == null) {
            val last = thumbnails.optJSONObject(thumbnails.length() - 1)
            bestUrl = last?.optString("url")?.takeIf { it.isNotBlank() }
        }

        return bestUrl?.let { upgradeCoverUrl(it) }
    }

    private fun extractThumbnailFromHeaderNode(header: JSONObject?): String? {
        if (header == null) return null
        val effectiveHeader = header.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
            ?.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
            ?: header.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                ?.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
            ?: header.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
            ?: header.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
            ?: header

        val candidates = listOfNotNull(
            effectiveHeader.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"),
            effectiveHeader.optJSONObject("thumbnail")?.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"),
            effectiveHeader.optJSONObject("thumbnail")?.optJSONArray("thumbnails"),
            effectiveHeader.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"),
            effectiveHeader.optJSONObject("thumbnailRenderer")?.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"),
            effectiveHeader.optJSONObject("thumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"),
            effectiveHeader.optJSONObject("foregroundThumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"),
            effectiveHeader.optJSONObject("playlistHeaderBanner")?.optJSONObject("heroPlaylistThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"),
            effectiveHeader.optJSONObject("heroPlaylistThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        )

        for (arr in candidates) {
            val url = extractBestThumbnailUrl(arr)
            if (!url.isNullOrBlank()) return url
        }

        fun findThumbnails(node: Any?): JSONArray? {
            if (node is JSONObject) {
                val direct = node.optJSONArray("thumbnails")
                if (direct != null && direct.length() > 0) return direct
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "runs" && k != "navigationEndpoint" && k != "menu" && k != "serviceTrackingParams") {
                        val res = findThumbnails(node.opt(k))
                        if (res != null) return res
                    }
                }
            } else if (node is JSONArray) {
                for (i in 0 until node.length()) {
                    val res = findThumbnails(node.opt(i))
                    if (res != null) return res
                }
            }
            return null
        }

        val foundArr = findThumbnails(effectiveHeader)
        if (foundArr != null) {
            val url = extractBestThumbnailUrl(foundArr)
            if (!url.isNullOrBlank()) return url
        }

        return null
    }

    fun extractPlaylistThumbnail(json: JSONObject): String? {
        // 1. Direct check in microformatDataRenderer
        val microformatThumb = json.optJSONObject("microformat")
            ?.optJSONObject("microformatDataRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        extractBestThumbnailUrl(microformatThumb)?.let { return it }

        // 2. Direct checks on standard tabs in both twoColumnBrowseResultsRenderer and singleColumnBrowseResultsRenderer
        val contents = json.optJSONObject("contents")
        val tabs = contents?.optJSONObject("twoColumnBrowseResultsRenderer")?.optJSONArray("tabs")
            ?: contents?.optJSONObject("singleColumnBrowseResultsRenderer")?.optJSONArray("tabs")

        if (tabs != null && tabs.length() > 0) {
            val tab0 = tabs.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
            val sectionList = tab0?.optJSONObject("sectionListRenderer")
            val sectionContents = sectionList?.optJSONArray("contents")

            if (sectionContents != null) {
                for (i in 0 until sectionContents.length()) {
                    val sec = sectionContents.optJSONObject(i) ?: continue
                    for (headerKey in listOf(
                        "musicResponsiveHeaderRenderer",
                        "musicDetailHeaderRenderer",
                        "musicEditablePlaylistDetailHeaderRenderer",
                        "musicVisualHeaderRenderer"
                    )) {
                        sec.optJSONObject(headerKey)?.let { hdr ->
                            extractThumbnailFromHeaderNode(hdr)?.let { return it }
                        }
                    }
                    extractThumbnailFromHeaderNode(sec)?.let { return it }
                }
            }

            sectionList?.optJSONObject("header")?.let { hdr ->
                extractThumbnailFromHeaderNode(hdr)?.let { return it }
            }
        }

        // 3. Direct check on root header
        json.optJSONObject("header")?.let { rootHdr ->
            extractThumbnailFromHeaderNode(rootHdr)?.let { return it }
        }

        // 4. Recursive search across JSON for any playlist header renderer (excluding track lists & continuations)
        fun searchHeaderNode(node: Any?): String? {
            if (node is JSONObject) {
                for (key in listOf(
                    "musicResponsiveHeaderRenderer",
                    "musicDetailHeaderRenderer",
                    "musicEditablePlaylistDetailHeaderRenderer",
                    "musicVisualHeaderRenderer",
                    "playlistHeaderRenderer"
                )) {
                    node.optJSONObject(key)?.let { hdr ->
                        extractThumbnailFromHeaderNode(hdr)?.let { return it }
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "musicPlaylistShelfRenderer" &&
                        k != "musicResponsiveListItemRenderer" &&
                        k != "playlistVideoListRenderer" &&
                        k != "playlistVideoRenderer" &&
                        k != "continuations" &&
                        k != "onResponseReceivedActions" &&
                        k != "responseContext" &&
                        k != "trackingParams"
                    ) {
                        val res = searchHeaderNode(node.opt(k))
                        if (res != null) return res
                    }
                }
            } else if (node is JSONArray) {
                for (i in 0 until node.length()) {
                    val res = searchHeaderNode(node.opt(i))
                    if (res != null) return res
                }
            }
            return null
        }

        return searchHeaderNode(json)
    }
}
