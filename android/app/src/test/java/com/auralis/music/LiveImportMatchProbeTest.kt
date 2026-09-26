package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test

/**
 * Prints every YouTube Music candidate the Spotify importer sees for AURALIS_PROBE_TRACKS
 * ("title|artist|album|seconds;..."), with its score and rejection reason. Skipped unless
 * AURALIS_LIVE_LYRICS=1.
 */
class LiveImportMatchProbeTest {
    @Test
    fun printImportCandidates(): Unit = runBlocking {
        Assume.assumeTrue(System.getenv("AURALIS_LIVE_LYRICS") == "1")
        val spec = System.getenv("AURALIS_PROBE_TRACKS") ?: return@runBlocking
        val client = InnerTubeClient()
        for (entry in spec.split(';').filter { it.isNotBlank() }) {
            val (title, artist, album, sec) = entry.split('|')
            val target = Track(id = "sp_probe", title = title, artist = artist, album = album, duration = sec.toLong())
            println("[TARGET] $title | $artist | $album | ${sec}s")
            val primary = artist.split(Regex("[,&/]")).first().trim()
            for (q in listOf("$title $primary", title)) {
                for ((label, songs) in listOf(
                    "songs" to client.search(q, InnerTubeClient.FILTER_SONGS).songs,
                    "general" to client.search(q).songs
                )) {
                    println("  [QUERY] \"$q\" ($label)")
                    songs.take(8).forEachIndexed { i, c ->
                        val score = SearchQueryMatcher.scoreTrackCandidate(target, c, i)
                        val why = SearchQueryMatcher.recordingMismatch(target, c)
                        println("    #$i ${c.id} \"${c.title}\" | ${c.artist} | album=${c.album} | ${c.duration}s | score=${"%.0f".format(score)}${why?.let { " REJECT($it)" } ?: ""}")
                    }
                    val pick = SearchQueryMatcher.findBestCandidateForTrack(target, songs)
                    println("    -> ${pick?.let { "${it.id} \"${it.title}\" | ${it.artist} | ${it.album}" } ?: "none"}")
                }
            }
        }
    }
}
