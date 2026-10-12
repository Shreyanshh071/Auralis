package com.auralis.music.data.network

import org.json.JSONArray
import org.json.JSONObject

/** Keep the browse ID and opaque params supplied by this artist's own shelf. */
internal fun artistMoreEndpoint(shelf: JSONObject): JSONObject? {
    val header = shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        ?: shelf.optJSONObject("header")?.optJSONObject("musicShelfBasicHeaderRenderer")
    return shelf.optJSONObject("bottomEndpoint")?.optJSONObject("browseEndpoint")
        ?: header?.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
            ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        ?: (header?.optJSONObject("title") ?: shelf.optJSONObject("title"))
            ?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
}

/** Full-list pages use grids or playlist shelves, unlike the short artist carousels. */
internal fun artistCatalogueItems(root: JSONObject): JSONArray = JSONArray().apply {
    fun visit(value: Any?) {
        when (value) {
            is JSONObject -> {
                if (value.has("musicResponsiveListItemRenderer") || value.has("musicTwoRowItemRenderer")) put(value)
                else value.keys().forEach { visit(value.opt(it)) }
            }
            is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
        }
    }
    visit(root.optJSONObject("contents"))
    visit(root.optJSONObject("continuationContents"))
}
