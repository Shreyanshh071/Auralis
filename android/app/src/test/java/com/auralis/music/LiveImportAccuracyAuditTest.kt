package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Measures the Spotify importer on real tracks (AURALIS_AUDIT_FILE, lines of
 * `[SP] id "title" | artists | album=... | Ns`): how often its pick is from the same album
 * as Spotify's copy, and how often a same-album song was in the results but not picked.
 */
class LiveImportAccuracyAuditTest {
    private val line = Regex("""^\[SP] (\S+) "(.*)" \| (.*) \| album=(.*) \| (\d+)s$""")

    @Test
    fun audit(): Unit = runBlocking {
        Assume.assumeTrue(System.getenv("AURALIS_LIVE_LYRICS") == "1")
        val file = System.getenv("AURALIS_AUDIT_FILE") ?: return@runBlocking
        val n = (System.getenv("AURALIS_AUDIT_N") ?: "120").toInt()
        val all = File(file).readLines().mapNotNull { line.matchEntire(it.trim()) }.map {
            val (id, t, a, al, d) = it.destructured
            Track(id = id, title = t, artist = a, album = al, duration = d.toLong())
        }
        val step = maxOf(1, all.size / n)
        val sample = all.filterIndexed { i, _ -> i % step == 0 }.take(n)
        val importer = SpotifyPlaylistImporter()
        val client = InnerTubeClient()
        val gate = Semaphore(6)
        val rows = sample.map { src ->
            async {
                gate.withPermit {
                    val pick = runCatching { importer.matchToYouTube(src) }.getOrNull()
                    val primary = src.artist.split(',').first().trim()
                    val pool = listOf("${src.title} $primary", "${src.title} $primary ${src.album}").flatMap {
                        runCatching { client.search(it, InnerTubeClient.FILTER_SONGS).songs }.getOrDefault(emptyList())
                    }
                    val srcAlbum = SearchQueryMatcher.albumIdentity(src.album)
                    val sameAlbum = pool.filter {
                        SearchQueryMatcher.albumIdentity(it.album) == srcAlbum &&
                            SearchQueryMatcher.recordingMismatch(src, it) == null &&
                            SearchQueryMatcher.scoreTrackCandidate(src, it) > 0
                    }
                    val verdict = when {
                        pick == null && sameAlbum.isEmpty() -> "NONE"
                        pick == null -> "NONE_BUT_ALBUM_AVAILABLE"
                        SearchQueryMatcher.albumIdentity(pick.album) == srcAlbum -> "SAME_ALBUM"
                        sameAlbum.isNotEmpty() -> "MISSED_ALBUM"
                        else -> "OTHER_ALBUM_ONLY"
                    }
                    "$verdict\t\"${src.title}\" | ${src.artist} | ${src.album} | ${src.duration}s  ->  " +
                        (pick?.let { "${it.id} \"${it.title}\" | ${it.artist} | ${it.album} | ${it.duration}s" } ?: "none") +
                        (if (verdict == "MISSED_ALBUM" || verdict == "NONE_BUT_ALBUM_AVAILABLE") "  [same-album: ${sameAlbum.first().id} \"${sameAlbum.first().title}\" | ${sameAlbum.first().artist} | ${sameAlbum.first().album} | ${sameAlbum.first().duration}s]" else "")
                }
            }
        }.awaitAll()
        System.getenv("AURALIS_AUDIT_OUT")?.let { File(it).writeText(rows.joinToString("\n")) }
    }
}
