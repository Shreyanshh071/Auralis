package com.auralis.music.domain.recognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShazamRequestJson(
    @SerialName("geolocation")
    val geolocation: Geolocation,
    @SerialName("signature")
    val signature: Signature,
    @SerialName("timestamp")
    val timestamp: Long,
    @SerialName("timezone")
    val timezone: String
) {
    @Serializable
    data class Geolocation(
        @SerialName("altitude")
        val altitude: Double,
        @SerialName("latitude")
        val latitude: Double,
        @SerialName("longitude")
        val longitude: Double
    )

    @Serializable
    data class Signature(
        @SerialName("samplems")
        val samplems: Long,
        @SerialName("timestamp")
        val timestamp: Long,
        @SerialName("uri")
        val uri: String
    )
}

@Serializable
data class ShazamResponseJson(
    @SerialName("matches")
    val matches: List<Match?>? = null,
    @SerialName("track")
    val track: Track? = null,
    @SerialName("tagid")
    val tagid: String? = null
) {
    @Serializable
    data class Match(
        @SerialName("id")
        val id: String? = null,
        @SerialName("offset")
        val offset: Double? = null
    )

    @Serializable
    data class Track(
        @SerialName("layout")
        val layout: String? = null,
        @SerialName("type")
        val type: String? = null,
        @SerialName("key")
        val key: String? = null,
        @SerialName("title")
        val title: String? = null,
        @SerialName("subtitle")
        val subtitle: String? = null,
        @SerialName("images")
        val images: Images? = null,
        @SerialName("sections")
        val sections: List<Section?>? = null,
        @SerialName("url")
        val url: String? = null,
        @SerialName("isrc")
        val isrc: String? = null,
        @SerialName("genres")
        val genres: Genres? = null,
        @SerialName("hub")
        val hub: Hub? = null
    ) {
        @Serializable
        data class Hub(
            @SerialName("type")
            val type: String? = null,
            @SerialName("actions")
            val actions: List<Action?>? = null,
            @SerialName("options")
            val options: List<Option?>? = null
        ) {
            @Serializable
            data class Action(
                @SerialName("name")
                val name: String? = null,
                @SerialName("type")
                val type: String? = null,
                @SerialName("uri")
                val uri: String? = null
            )

            @Serializable
            data class Option(
                @SerialName("caption")
                val caption: String? = null,
                @SerialName("type")
                val type: String? = null,
                @SerialName("actions")
                val actions: List<Action?>? = null
            )
        }

        @Serializable
        data class Images(
            @SerialName("background")
            val background: String? = null,
            @SerialName("coverart")
            val coverart: String? = null,
            @SerialName("coverarthq")
            val coverarthq: String? = null
        )

        @Serializable
        data class Section(
            @SerialName("type")
            val type: String? = null,
            @SerialName("metadata")
            val metadata: List<Metadata?>? = null,
            @SerialName("text")
            val text: List<String>? = null
        ) {
            @Serializable
            data class Metadata(
                @SerialName("title")
                val title: String? = null,
                @SerialName("text")
                val text: String? = null
            )
        }

        @Serializable
        data class Genres(
            @SerialName("primary")
            val primary: String? = null
        )
    }
}

@Serializable
data class RecognitionResult(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverArtUrl: String? = null,
    val coverArtHqUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val lyrics: List<String>? = null,
    val shazamUrl: String? = null,
    val isrc: String? = null,
    val youtubeVideoId: String? = null
)

@Serializable
data class RecognitionHistoryItem(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverArtUrl: String? = null,
    val coverArtHqUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val recognizedAtEpochMillis: Long
)
