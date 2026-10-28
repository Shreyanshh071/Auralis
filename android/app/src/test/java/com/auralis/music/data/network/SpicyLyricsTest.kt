package com.auralis.music.data.network

import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.data.network.provider.SpicyLyricsSource
import com.auralis.music.data.parser.SpicyLyricsParser
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpicyLyricsTest {
    @Test fun songwriterCreditsSurviveCacheEncodingAlongsideSyncContributors() {
        val response = envelope()
        response.getJSONObject("Body").put("SongWriters", org.json.JSONArray()
            .put("Song Writer").put("Second Writer").put("Song Writer").put(" "))
        val data = SpicyLyricsParser.parse(response, recording)!!
        assertEquals("Written by Song Writer, Second Writer", data.credits.last().label)
        assertEquals("", data.credits.last().url)
        assertEquals("Uploaded by Uploader", data.credits[1].label)
        assertEquals("Made by Maker", data.credits[2].label)
        assertEquals(data.credits, SpicyLyricsParser.decodeCredits(SpicyLyricsParser.encodeCredits(data.credits)))
    }

    private val id = "1QV6tiMFM6fSOKOGLMHYYg"
    private val query = LyricsSearchQuery("Test Song", "Test Artist - Topic", 200, "abcdefghijk", album = "Test Album")
    private val recording = SpicyRecording(id, "Test Song", "Test Artist", 200000, "Test Album")
    private val key = "sl_pk_test_native"

    private fun envelope(type: String = "Syllable", source: String = "spicy_lyrics"): JSONObject = JSONObject("""
        {"Status":200,"Body":{"id":"$id","Type":"$type","source":"$source",
        "UploadAttribution":{"Uploader":{"id":"123","username":"Uploader","url":"https://spicylyrics.org/uid/123"},"Maker":{"id":"456","username":"Maker","url":"https://spicylyrics.org/uid/456"}},
        "Content":[{"Type":"Vocal","OppositeAligned":true,
          "Lead":{"StartTime":10.123,"EndTime":12.9,"TranslatedText":"Translated line","Syllables":[
            {"Text":"Hel","StartTime":10.123,"EndTime":10.4,"IsPartOfWord":true},
            {"Text":"lo","StartTime":10.5,"EndTime":10.9},
            {"Text":"world","StartTime":12.0,"EndTime":12.9}]},
          "Background":[{"StartTime":10.8,"EndTime":11.2,"Syllables":[{"Text":"oh","StartTime":10.8,"EndTime":11.2}]}]}]}}
    """)

    @Test fun recordingMatchRequiresIdentityVersionAndDuration() {
        assertTrue(SpicyRecordingMatcher.matches(query, recording))
        assertFalse(SpicyRecordingMatcher.matches(query, recording.copy(artist = "Cover Artist")))
        assertFalse(SpicyRecordingMatcher.matches(query, recording.copy(title = "Different Song")))
        assertFalse(SpicyRecordingMatcher.matches(query, recording.copy(title = "Test Song (Live)")))
        assertFalse(SpicyRecordingMatcher.matches(query.copy(recordingTitle = "Test Song (Slowed)"), recording))
        assertFalse(SpicyRecordingMatcher.matches(query, recording.copy(durationMs = 203000)))
        assertFalse(SpicyRecordingMatcher.matches(query.copy(durationSec = null), recording))
        assertFalse(SpicyRecordingMatcher.matches(query.copy(isrc = "GB123"), recording.copy(isrc = "US999")))
        assertTrue(SpicyRecordingMatcher.matches(query.copy(isrc = "GB123"), recording.copy(isrc = "gb123")))
    }

    @Test fun validatesSpotifyIdentitiesWithoutTreatingVideoIdsAsSpotify() {
        assertEquals(id, SpicyRecordingMatcher.spotifyId("sp_$id"))
        assertEquals(id, SpicyRecordingMatcher.spotifyId("spotify:track:$id"))
        assertEquals(id, SpicyRecordingMatcher.spotifyId("https://open.spotify.com/track/$id?si=test"))
        assertNull(SpicyRecordingMatcher.spotifyId(query.videoId))
        assertNull(SpicyRecordingMatcher.spotifyId("spotify:album:$id"))
    }

    @Test fun syllablesPreserveSpacingRestsBackgroundsSpeakersAndCredits() {
        val data = SpicyLyricsParser.parse(envelope(), recording)!!
        assertEquals(SyncType.RICHSYNC, data.syncType)
        assertEquals(LyricsProvider.SPICY_LYRICS, data.provider)
        assertEquals("Hello world", data.lines.first().text)
        val parts = data.lines.first().words!!.flatMap { it.syllables ?: listOf(it) }
        assertEquals(10123L, parts.first().time)
        assertEquals(277L, parts.first().duration)
        assertEquals(12000L, parts[2].time)
        assertEquals("Hello ", data.lines.first().words!!.first().word)
        assertEquals("Translated line", data.lines.first().translatedText)
        assertEquals("v2", data.lines.first().agent)
        assertTrue(data.lines[1].isBackground)
        assertTrue(data.lines[1].words!!.all { it.isBackground })
        assertEquals(200000L, data.durationMs) // Not the lyrics' final 12.9s timestamp.
        assertEquals(3, data.credits.size)
        assertEquals("https://spicylyrics.org/uid/123", data.credits[1].url)
        assertFalse(data.isExactVideoMatch)
    }

    @Test fun lineAndStaticFormatsDoNotInventWordTiming() {
        val line = envelope("Line", "spotify").apply {
            getJSONObject("Body").put("Content", org.json.JSONArray("""[{"Type":"Vocal","Text":"A full line","StartTime":10.25,"EndTime":12.0}]"""))
        }
        val synced = SpicyLyricsParser.parse(line, recording)!!
        assertEquals(SyncType.LINE_SYNC, synced.syncType)
        assertNull(synced.lines.first().words)
        assertEquals(10250L, synced.lines.first().time)
        assertEquals(listOf("Spotify"), synced.credits.map { it.label })
        val plain = envelope("Static", "apple_music").apply {
            getJSONObject("Body").put("Lines", org.json.JSONArray("""[{"Text":"Plain line"}]"""))
        }
        assertEquals(SyncType.PLAIN, SpicyLyricsParser.parse(plain, recording)!!.syncType)
        assertEquals("Plain line", SpicyLyricsParser.parse(plain, recording)!!.plainLyrics)
    }

    @Test fun contributorLinksUseResponseUrlsAndMissingLinksKeepNames() {
        val body = envelope()
        val uploader = body.getJSONObject("Body").getJSONObject("UploadAttribution").getJSONObject("Uploader")
        uploader.put("url", "https://spicylyrics.org/profile/custom-uploader")
        assertEquals("https://spicylyrics.org/profile/custom-uploader", SpicyLyricsParser.parse(body, recording)!!.credits[1].url)
        uploader.remove("url")
        val unlinked = SpicyLyricsParser.parse(body, recording)!!
        assertEquals("Uploaded by Uploader", unlinked.credits[1].label)
        assertEquals("", unlinked.credits[1].url)
        assertEquals(unlinked.credits, SpicyLyricsParser.decodeCredits(SpicyLyricsParser.encodeCredits(unlinked.credits)))
        uploader.put("url", "javascript:alert(1)")
        assertEquals("", SpicyLyricsParser.parse(body, recording)!!.credits[1].url)
        body.getJSONObject("Body").remove("source")
        assertEquals("Unknown lyrics source", SpicyLyricsParser.parse(body, recording)!!.credits.single().label)
    }

    @Test fun rejectsWrongRecordingMalformedTimingAndMissingCommunityCredits() {
        val wrong = envelope().apply { getJSONObject("Body").put("id", "wrong") }
        assertNull(SpicyLyricsParser.parse(wrong, recording))
        val badTime = envelope().apply {
            getJSONObject("Body").getJSONArray("Content").getJSONObject(0).getJSONObject("Lead")
                .getJSONArray("Syllables").getJSONObject(0).put("EndTime", 1.0)
        }
        assertNull(SpicyLyricsParser.parse(badTime, recording))
        val noCredits = envelope().apply { getJSONObject("Body").remove("UploadAttribution") }
        assertNull(SpicyLyricsParser.parse(noCredits, recording))
        val noMaker = envelope().apply { getJSONObject("Body").getJSONObject("UploadAttribution").remove("Maker") }
        assertEquals(2, SpicyLyricsParser.parse(noMaker, recording)!!.credits.size)
    }

    private val spotifyJson get() = """{"id":"$id","name":"Test Song","duration_ms":200000,"artists":[{"name":"Test Artist"}],"album":{"name":"Test Album"}}"""
    private fun client(requests: MutableList<Request>, status: Int = 200, lyrics: String = envelope().toString()) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(requests) { requests += request }
            val spotify = request.url.host == "api.spotify.com"
            val body = if (spotify) {
                if (request.url.encodedPath == "/v1/search") """{"tracks":{"items":[$spotifyJson]}}""" else spotifyJson
            } else lyrics
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (spotify) 200 else status)
                .message("test").header("Retry-After", "60").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun youtubeLookupUsesSpotifyMetadataCachesMatchAndKeepsTokensSeparate() = runBlocking {
        val requests = mutableListOf<Request>()
        val source = SpicyLyricsSource(client(requests), { key }, { "spotify_token" })
        assertNotNull(source.search(query))
        assertNotNull(source.search(query))
        assertEquals(1, requests.count { it.url.host == "api.spotify.com" })
        assertTrue(requests.filter { it.url.host == "api.spotify.com" }.all { it.header("Authorization") == "Bearer spotify_token" })
        assertTrue(requests.filter { it.url.host == "api.spicylyrics.org" }.all {
            it.url.encodedPath == "/v1/lyrics/$id" && it.header("Authorization") == "Bearer $key"
        })
        assertTrue(requests.none { it.url.toString().contains(query.videoId!!) })
    }

    @Test fun missingOrSecretKeysPerformNoNetworkAndNoTokenLookup() = runBlocking {
        val requests = mutableListOf<Request>()
        for (invalid in listOf("", "sl_sk_secret", "not-a-key")) {
            val source = SpicyLyricsSource(client(requests), { invalid }, { error("Must not resolve tokens") })
            assertNull(source.search(query))
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun wrongVersionNeverCallsLyricsApiAndRateLimitsCoolDown() = runBlocking {
        val requests = mutableListOf<Request>()
        val source = SpicyLyricsSource(client(requests), { key }, { "spotify_token" })
        assertNull(source.search(query.copy(recordingTitle = "Test Song (Live)")))
        assertTrue(requests.none { it.url.host == "api.spicylyrics.org" })
        requests.clear()
        var now = 1000L
        val limited = SpicyLyricsSource(client(requests, 429), { key }, { "spotify_token" }, { now })
        assertNull(limited.search(query))
        assertNull(limited.search(query))
        assertEquals(1, requests.count { it.url.host == "api.spicylyrics.org" })
        now += 61000
        assertNull(limited.search(query))
        assertEquals(2, requests.count { it.url.host == "api.spicylyrics.org" })
    }

    @Test fun httpFailuresAndMalformedPayloadsReturnNoCandidate() = runBlocking {
        for (status in listOf(401, 403, 404, 500)) {
            assertNull(SpicyLyricsSource(client(mutableListOf(), status), { key }, { "spotify_token" }).search(query))
        }
        assertNull(SpicyLyricsSource(client(mutableListOf(), lyrics = "not json"), { key }, { "spotify_token" }).search(query))
    }

    @Test fun knownSpotifyIdentityUsesTrackLookupAndMissingTokensDoNotCallApi() = runBlocking {
        val requests = mutableListOf<Request>()
        val source = SpicyLyricsSource(client(requests), { key }, { "spotify_token" })
        assertNotNull(source.search(query.copy(spotifyId = "sp_$id")))
        assertEquals("/embed/track/$id", requests.first().url.encodedPath)
        assertTrue(requests.any { it.url.host == "api.spotify.com" && it.url.encodedPath == "/v1/tracks/$id" })
        requests.clear()
        assertNull(SpicyLyricsSource(client(requests), { key }, { null }).search(query))
        assertTrue(requests.isEmpty())
    }

    @Test fun cachedMappingIsScopedToMetadataAndExpires() = runBlocking {
        val requests = mutableListOf<Request>()
        var now = 1000L
        val source = SpicyLyricsSource(client(requests), { key }, { "spotify_token" }, { now })
        assertNotNull(source.search(query))
        assertNull(source.search(query.copy(durationSec = 250)))
        assertNull(source.search(query.copy(recordingTitle = "Test Song (Remix)")))
        assertEquals(1, requests.count { it.url.host == "api.spicylyrics.org" })
        now += 86_400_001L
        assertNotNull(source.search(query))
        assertEquals(4, requests.count { it.url.host == "api.spotify.com" })
    }

    @Test fun providerPickerUsesAdapterAndRetainsOriginalVersionBeforeCleanup() = runBlocking {
        val requests = mutableListOf<Request>()
        val lines = envelope("Line", "spotify").apply {
            getJSONObject("Body").put("Content", org.json.JSONArray("""[
                {"Type":"Vocal","Text":"First sung line","StartTime":10.0,"EndTime":12.0},
                {"Type":"Vocal","Text":"Second sung line","StartTime":15.0,"EndTime":17.0},
                {"Type":"Vocal","Text":"Third sung line","StartTime":20.0,"EndTime":22.0}]
            """))
        }
        val source = SpicyLyricsSource(client(requests, lyrics = lines.toString()), { key }, { "spotify_token" })
        val cascade = LyricsClient(spicyLyricsSource = source)
        val data = cascade.searchProvider(LyricsProvider.SPICY_LYRICS, query.title, query.artist, 200, query.videoId)
        assertNotNull(data)
        assertEquals(LyricsProvider.SPICY_LYRICS, data!!.provider)
        assertEquals(1, data.credits.size)
        assertNull(cascade.searchProvider(LyricsProvider.SPICY_LYRICS, "Test Song (Live)", query.artist, 200, query.videoId))
        assertEquals(1, requests.count { it.url.host == "api.spicylyrics.org" })
    }
}
