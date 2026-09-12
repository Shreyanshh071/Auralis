package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AudioStreamResolverTest {

    @Test
    fun testDirectStreamResolution() = runBlocking {
        val tracks = listOf(
            Triple("1lyu1KKwC74", "Bitter Sweet Symphony", "The Verve"),
            Triple("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd"),
            Triple("fJ9rUzIMcZQ", "Bohemian Rhapsody", "Queen"),
            Triple("JGwWNGJdvx8", "Shape of You", "Ed Sheeran"),
            Triple("34Na4j8AVgA", "Starboy", "The Weeknd"),
            Triple("opwZ_PJ-F_E", "Choo Lo", "The Local Train"),
            Triple("EwLgGHAxTa8", "Raanjhanaa", "A.R. Rahman"),
            Triple("H5v3kku4y6Q", "As It Was", "Harry Styles")
        )

        for ((id, title, artist) in tracks) {
            AudioStreamResolver.clearCache()
            println("==================================================")
            println("PHASE 1 COLD RESOLUTION TEST: '$title' by '$artist' ($id)")
            val t0 = System.currentTimeMillis()
            val stream = AudioStreamResolver.resolveAudioStream(id, title, artist)
            val coldMs = System.currentTimeMillis() - t0
            println("COLD RESULT: resolved in ${coldMs}ms")
            println("URL: ${stream?.take(80)}...")
            assert(!stream.isNullOrBlank())

            // Warm / Prefetched Test (from cache)
            val tWarm0 = System.currentTimeMillis()
            val warmStream = AudioStreamResolver.resolveAudioStream(id, title, artist)
            val warmMs = System.currentTimeMillis() - tWarm0
            println("WARM / PREFETCHED RESULT: resolved in ${warmMs}ms")
            assert(!warmStream.isNullOrBlank())
            println("==================================================")
        }
    }

    @Test
    fun testTheLessIKnowTheBetter() = runBlocking {
        val client = com.auralis.music.data.network.InnerTubeClient()
        println("SEARCH RESULTS FOR 'The Less I Know The Better':")
        val results = client.search("The Less I Know The Better", com.auralis.music.data.network.InnerTubeClient.FILTER_SONGS).songs
        for ((idx, r) in results.take(8).withIndex()) {
            println("[$idx] id=${r.id}, title='${r.title}', artist='${r.artist}', album='${r.album}', dur=${r.duration}")
            val target = com.auralis.music.domain.model.Track(id = "sp_test", title = "The Less I Know The Better", artist = "Tame Impala", duration = 217)
            val score = com.auralis.music.domain.search.SearchQueryMatcher.scoreTrackCandidate(target, r, idx)
            println("     score=$score")
        }

        // Test Spotify ID resolution
        val resolvedSpotify = AudioStreamResolver.resolveAudioStream("sp_6K4t315aqR7MxTxc2jZndN", "The Less I Know The Better", "Tame Impala", duration = 217)
        val matchedSpotifyId = AudioStreamResolver.getMatchedVideoId("sp_6K4t315aqR7MxTxc2jZndN")
        println("RESOLVED FOR SPOTIFY TRACK: $resolvedSpotify")
        println("MATCHED VIDEO ID: $matchedSpotifyId")
        org.junit.Assert.assertEquals("PvM79DJ2PmM", matchedSpotifyId)

        // Test Legacy Bloated Video ID Redirection
        val resolvedLegacy = AudioStreamResolver.resolveAudioStream("sBzrzS1Ag_g", "The Less I Know The Better", "Tame Impala", duration = 217)
        val matchedLegacyId = AudioStreamResolver.getMatchedVideoId("sBzrzS1Ag_g")
        println("RESOLVED FOR LEGACY VIDEO ID: $resolvedLegacy")
        println("MATCHED LEGACY ID: $matchedLegacyId")
        org.junit.Assert.assertEquals("PvM79DJ2PmM", matchedLegacyId)

        // Test General Search + Official Search as executed in SearchRepositoryImpl
        println("==================================================")
        println("SEARCH REPOSITORY SIMULATION FOR 'less i know the better':")
        val official = client.search("less i know the better", com.auralis.music.data.network.InnerTubeClient.FILTER_SONGS).songs
        val general = client.search("less i know the better").songs
        val allSongs = (official + general).distinctBy { it.id }
        println("ALL CANDIDATES:")
        for ((idx, s) in allSongs.take(8).withIndex()) {
            println("  [$idx] id=${s.id}, title='${s.title}', artist='${s.artist}', views='${s.views}', dur=${s.duration}, album='${s.album}'")
        }

        val (matched, recs) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(allSongs, "less i know the better")
        println("MATCHED SONGS (After partitionResults & deduplication):")
        for ((idx, s) in matched.take(5).withIndex()) {
            println("  [$idx] WINNER id=${s.id}, title='${s.title}', artist='${s.artist}', views='${s.views}', dur=${s.duration}, album='${s.album}'")
        }
        org.junit.Assert.assertTrue(matched.isNotEmpty())
        org.junit.Assert.assertEquals("PvM79DJ2PmM", matched.first().id)
        println("==================================================")
    }


    @Test
    fun testInspectInitialSeedTracks() = runBlocking {
        val client = com.auralis.music.data.network.InnerTubeClient()
        val seedTracks = com.auralis.music.domain.recommendations.NewUserSeedProvider.getInitialSeedTracks()
        println("VERIFYING ${seedTracks.size} INITIAL SEED TRACKS:")
        for (track in seedTracks) {
            val songs = client.search("${track.title} ${track.artist}", com.auralis.music.data.network.InnerTubeClient.FILTER_SONGS).songs
            val topOfficial = songs.firstOrNull()
            println("SEED: '${track.title}' by '${track.artist}' (id=${track.id}, dur=${track.duration})")
            if (topOfficial != null) {
                val matches = topOfficial.id == track.id
                println("   -> Top Official Song: id=${topOfficial.id}, title='${topOfficial.title}', artist='${topOfficial.artist}', album='${topOfficial.album}', dur=${topOfficial.duration} [MATCH: $matches]")
            } else {
                println("   -> NO OFFICIAL SONG FOUND")
            }
        }
    }
}

