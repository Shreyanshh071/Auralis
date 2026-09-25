package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.LrcLibLyricsSource
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import org.junit.Test

/**
 * Live coverage audit: resolves each song through YouTube Music search exactly as the app does,
 * runs it through the full [LyricsClient] race, and for every song that doesn't end up synced,
 * shows what lrclib alone had and which acceptance check threw it away.
 *
 * Hits the network, so it is skipped unless AURALIS_LIVE_LYRICS=1 is set in the environment.
 */
class LiveLyricsCoverageAuditTest {

    private val queries = listOf(
        "Hawayein Arijit Singh", "Mast Magan Arijit Singh", "Tum Hi Ho Arijit Singh",
        "Kesariya Arijit Singh", "Channa Mereya Arijit Singh", "Apna Bana Le Arijit Singh",
        "Heeriye Arijit Singh", "Raataan Lambiyan Jubin Nautiyal", "Pehle Bhi Main Vishal Mishra",
        "Satranga Arijit Singh", "Chaleya Arijit Singh", "O Maahi Arijit Singh",
        "Tujhe Kitna Chahne Lage Arijit Singh", "Ve Kamleya Arijit Singh", "Kabira Tochi Raina",
        "Agar Tum Saath Ho Arijit Singh", "Ilahi Arijit Singh", "Gerua Arijit Singh",
        "Shayad Arijit Singh", "Tera Ban Jaunga Akhil Sachdeva", "Bekhayali Sachet Tandon",
        "Khairiyat Arijit Singh", "Softly Karan Aujla", "Brown Munde AP Dhillon",
        "Excuses AP Dhillon", "Lover Diljit Dosanjh", "295 Sidhu Moose Wala",
        "Kho Gaye Hum Kahan Jasleen Royal", "Husn Anuv Jain", "Baarishein Anuv Jain",
        "Tu Aake Dekhle King", "Blinding Lights The Weeknd"
    )

    @Test
    fun auditSyncedCoverage() = runBlocking {
        Assume.assumeTrue("set AURALIS_LIVE_LYRICS=1 to run", System.getenv("AURALIS_LIVE_LYRICS") == "1")

        // AURALIS_AUDIT_QUERIES="a|b|c" audits just those songs instead of the built-in list.
        val queries = System.getenv("AURALIS_AUDIT_QUERIES")?.split('|')?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: queries
        val search = InnerTubeClient()
        val client = LyricsClient()
        val lrclib = LrcLibLyricsSource()
        val tally = linkedMapOf("WORD" to 0, "LINE" to 0, "PLAIN/NONE" to 0, "NOT_FOUND_ON_YTM" to 0)

        println("\n" + "=".repeat(110))
        println("LYRICS COVERAGE AUDIT")
        println("=".repeat(110))

        for (q in queries) {
            val track = try { search.search(q, InnerTubeClient.FILTER_SONGS).songs.firstOrNull() } catch (_: Exception) { null }
            if (track == null) {
                tally["NOT_FOUND_ON_YTM"] = tally.getValue("NOT_FOUND_ON_YTM") + 1
                println("\n[$q] -> not found on YouTube Music")
                continue
            }
            val durMs = track.duration * 1000L
            // AURALIS_AUDIT_NO_ALBUM=1 mimics tracks that arrive without YouTube Music's album
            // (e.g. imported playlists), whose Better Lyrics cache key then misses.
            val album = if (System.getenv("AURALIS_AUDIT_NO_ALBUM") == "1") null else track.album
            val tStart = System.currentTimeMillis()
            val result = withTimeoutOrNull(20_000L) {
                client.getLyrics(
                    title = track.title,
                    artist = track.artist,
                    durationSec = track.duration,
                    videoId = track.id,
                    album = album,
                    channelTitle = track.channelTitle,
                    durationMs = durMs
                )
            }
            val outcome = when {
                result == null || result.lines.isEmpty() -> "PLAIN/NONE"
                LyricsClient.tierOf(result) == LyricsClient.TIER_WORD -> "WORD"
                result.syncType != SyncType.PLAIN || result.lines.any { it.time > 0L } -> "LINE"
                else -> "PLAIN/NONE"
            }
            tally[outcome] = tally.getValue(outcome) + 1
            println("\n[$outcome] \"${track.title}\" by \"${track.artist}\" (${track.duration}s, album=${track.album}) -> provider=${result?.provider} in ${System.currentTimeMillis() - tStart}ms")

            if (outcome == "PLAIN/NONE") {
                val lq = LyricsSearchQuery(
                    title = track.title, artist = track.artist, durationSec = track.duration,
                    videoId = track.id, album = album, channelTitle = track.channelTitle, durationMs = durMs
                )
                val cand = try { lrclib.search(lq) } catch (e: Exception) { println("    lrclib EXCEPTION $e"); null }
                if (cand == null) {
                    println("    lrclib alone: NOTHING (all four lookups failed or scored < 45)")
                } else {
                    val d = cand.lyricsData
                    val master = LyricsAlignmentEngine.evaluateMasterMatch(
                        lyrics = d, playbackDurationMs = durMs, playbackTitle = track.title,
                        candidateTitle = d.trackName, playbackChannelTitle = track.channelTitle,
                        playbackVideoId = track.id, playbackArtist = track.artist, candidateArtist = d.artistName
                    )
                    val acceptable = LyricsAlignmentEngine.isAcceptableMasterMatch(
                        lyrics = d, playbackDurationMs = durMs, playbackTitle = track.title,
                        candidateTitle = d.trackName, playbackChannelTitle = track.channelTitle,
                        playbackVideoId = track.id, playbackArtist = track.artist, candidateArtist = d.artistName
                    )
                    println("    lrclib alone: \"${d.trackName}\" by \"${d.artistName}\" sync=${cand.syncType} conf=${cand.confidence} " +
                        "lyricDur=${d.effectiveDurationMs}ms tier=${LyricsClient.tierOf(d)} master=$master acceptable=$acceptable")
                }
            }
        }

        println("\n" + "=".repeat(110))
        println("TOTALS: $tally  (of ${queries.size})")
        println("=".repeat(110))
    }
}
