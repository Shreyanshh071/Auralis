package com.auralis.music.data.network

import com.auralis.music.data.network.provider.*
import com.auralis.music.data.parser.LyricsValidator
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test

/** Live diagnosis of word-synced candidates rejected as master mismatches.
 * Network only: runs when AURALIS_LIVE_LYRICS=1. */
class LiveMasterMatchProbeTest {
    private data class Song(val title: String, val artist: String, val durationMs: Long)

    @Test fun probeRejectedWordSync() {
        if (System.getenv("AURALIS_LIVE_LYRICS") != "1") return
        val songs = listOf(
            Song("I Wonder", "Kanye West", 243461),
            Song("We Don't Talk Anymore (feat. Selena Gomez)", "Charlie Puth, Selena Gomez", 217721),
            Song("#BrooklynBloodPop!", "SyKo", 145621),
            Song("CLOUT", "Jack Prince", 155441)
        )
        val sources = listOf(BetterLyricsSource(), NetEaseLyricsSource(), QQMusicLyricsSource())
        runBlocking {
            for (song in songs) {
                val query = LyricsSearchQuery(TitleCleaner.cleanCoreSongTitle(song.title), song.artist,
                    durationSec = song.durationMs / 1000, durationMs = song.durationMs, recordingTitle = song.title)
                for (source in sources) {
                    val cand = runCatching { withTimeoutOrNull(15_000) { source.search(query) } }.getOrNull()
                    if (cand == null) { println("PROBE ${song.title} | ${source.provider} | none"); continue }
                    val data = cand.lyricsData
                    val first = data.lines.firstOrNull { !it.isInstrumental && it.text.isNotBlank() }
                    val q = TitleCleaner.extractFeaturedArtists(song.title, song.artist)
                    val c = TitleCleaner.extractFeaturedArtists(data.trackName, data.artistName)
                    println("PROBE ${song.title} | ${source.provider} | track='${data.trackName}' artist='${data.artistName}' dur=${data.durationMs}" +
                        " | firstLine=${first?.time} firstWord=${first?.words?.firstOrNull()?.time} text='${first?.text?.take(30)}'" +
                        " | corruptIntro=${LyricsValidator.hasCorruptIntroTiming(data, song.durationMs / 1000)}" +
                        " nonMonotonic=${LyricsValidator.hasNonMonotonicWordTimestamps(data)}" +
                        " overrun=${LyricsValidator.hasVocalOverrun(data, song.durationMs)}" +
                        " | qFeat=$q cFeat=$c" +
                        " | verdict=${LyricsAlignmentEngine.evaluateMasterMatch(data, song.durationMs, song.title, data.trackName, null, null, song.artist, data.artistName)}")
                }
            }
        }
    }
}
