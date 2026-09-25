package com.auralis.music

import com.auralis.music.data.network.provider.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import org.junit.Test

/** Prints what every provider returns for one track (title, length, first lines). Gated by AURALIS_LIVE_LYRICS=1. */
class LiveProviderVersionProbeTest {
    @Test
    fun probeProviders(): Unit = runBlocking {
        Assume.assumeTrue(System.getenv("AURALIS_LIVE_LYRICS") == "1")
        val q = LyricsSearchQuery(
            title = System.getenv("AURALIS_PROBE_TITLE") ?: "Anarkali Disco Chali (Hyper Mix)[Remix By Dj Shiva]",
            artist = System.getenv("AURALIS_PROBE_ARTIST") ?: "Mamta Sharma & Sukhwinder Singh",
            durationSec = (System.getenv("AURALIS_PROBE_SEC") ?: "283").toLong(),
            videoId = System.getenv("AURALIS_PROBE_VID") ?: "sDUOa6s1T-A",
            channelTitle = null,
            durationMs = (System.getenv("AURALIS_PROBE_SEC") ?: "283").toLong() * 1000L
        )
        val sources: List<Pair<String, suspend () -> Any?>> = listOf(
            "AMLL" to { AmllLyricsSource().search(q) },
            "BETTER" to { BetterLyricsSource().search(q) },
            "UNISON" to { UnisonLyricsSource().search(q) },
            "PAXSENIX" to { PaxsenixLyricsSource().search(q) },
            "LRCLIB" to { LrcLibLyricsSource().search(q) },
            "JIOSAAVN" to { JioSaavnLyricsSource().search(q) },
            "NETEASE" to { NetEaseLyricsSource().search(q) },
            "KUGOU" to { KuGouLyricsSource().search(q) },
            "YOULY" to { YouLyPlusLyricsSource().search(q) },
            "SIMP" to { SimpMusicLyricsSource().search(q) },
        )
        for ((name, fetch) in sources) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { withTimeoutOrNull(14_000L) { fetch() } }
            val c = r.getOrNull() as? LyricsCandidate
            if (c == null) { println("[PROBE] $name -> none in ${System.currentTimeMillis() - t0}ms (${r.exceptionOrNull() ?: r.getOrNull()?.javaClass})"); continue }
            val d = c.lyricsData
            val first = d.lines.filter { !it.isInstrumental && it.text.isNotBlank() }.take(2).joinToString(" / ") { "${it.time}ms ${it.text}" }
            println("[PROBE] $name conf=${c.confidence} sync=${d.syncType} title=\"${d.trackName}\" durMs=${d.durationMs} eff=${d.effectiveDurationMs} lines=${d.lines.size} :: $first")
        }
    }
}
