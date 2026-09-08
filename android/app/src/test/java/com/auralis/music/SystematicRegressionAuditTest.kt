package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.*
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.math.abs

class SystematicRegressionAuditTest {

    data class SongAuditTarget(
        val title: String,
        val artist: String,
        val durationSec: Long,
        val videoId: String? = null,
        val album: String? = null,
        val queryCoreTitle: String? = null
    )

    data class CandidateAuditRecord(
        val provider: LyricsProvider,
        val responseStatus: String,
        val syncType: SyncType,
        val tier: Int,
        val wordCount: Int,
        val durationMs: Long?,
        val effectiveDurationMs: Long,
        val leadingSilenceMs: Long?,
        val firstLyricMs: Long?,
        val matchedVideoId: String?,
        val isExactVideoMatch: Boolean,
        val alignmentResult: MasterMatchStatus,
        val rejectionReason: String?,
        val finalScore: Double,
        val source: String = "network"
    )

    @Test
    fun auditAllTargetSongs() = runBlocking {
        val targets = listOf(
            SongAuditTarget("Hailie's Song", "Eminem", 321L, "sp_6db8ilz7yy1pfijjllejyh", "The Eminem Show"),
            SongAuditTarget("Hailie's World", "Eminem", 321L, "sp_6db8ilz7yy1pfijjllejyh", "The Eminem Show"),
            SongAuditTarget("Lovers Rock", "TV Girl", 213L, "sp_6dbuzqjtbnia1twybyw5cm", "Who Really Cares"),
            SongAuditTarget("Sunflower", "Post Malone & Swae Lee", 158L, "r7rn4rye_w8", "Spider-Man: Into the Spider-Verse"),
            SongAuditTarget("STAY", "The Kid LAROI & Justin Bieber", 141L, "xfemj-z3tta", "F*CK LOVE 3: OVER YOU"),
            SongAuditTarget("Feel Good Inc.", "Gorillaz", 223L, "sp_0d28khcov6aiegscpg5tut", "Demon Days"),
            SongAuditTarget("Like a Prayer", "Madonna", 340L, "sp_2v7ywbuzcgcvohhakucacv", "Like a Prayer"),
            SongAuditTarget("Fake Plastic Trees", "Radiohead", 290L, "sp_73ckjw3vsuxrpy3nnx4h7f", "The Bends"),
            SongAuditTarget("Love Me Not", "Ravyn Lenae", 213L, "hfpr4tami7e", "Bird's Eye"),
            SongAuditTarget("Starboy", "The Weeknd", 230L, "3_g2un5m350", "Starboy"),
            SongAuditTarget("Shape of My Heart", "Sting", 278L, "rvfgfmbfgoc", "Ten Summoner's Tales"),
            SongAuditTarget("Touch", "KATSEYE", 130L, "sp_1v7ptgubs2dbbrmfig0hu2", "SIS (Soft Is Strong)")
        )

        val betterLyricsSource = BetterLyricsSource()
        val paxsenixSource = PaxsenixLyricsSource()
        val unisonSource = UnisonLyricsSource()
        val lrcLibSource = LrcLibLyricsSource()
        val netEaseSource = NetEaseLyricsSource()
        val musixmatchSource = MusixmatchLyricsSource()
        val kuGouSource = KuGouLyricsSource()
        val jioSaavnSource = JioSaavnLyricsSource()

        val fullClient = LyricsClient()

        println("\n" + "=".repeat(140))
        println("AURALIS SYSTEMATIC REGRESSION AUDIT: LYRICS PIPELINE")
        println("=".repeat(140))

        for (target in targets) {
            val query = LyricsSearchQuery(
                title = target.title,
                artist = target.artist,
                durationSec = target.durationSec,
                videoId = target.videoId,
                album = target.album,
                durationMs = target.durationSec * 1000L
            )
            val playbackMs = target.durationSec * 1000L

            println("\n" + "#".repeat(120))
            println("TRACK: \"${target.title}\" | ARTIST: \"${target.artist}\" | ALBUM: \"${target.album}\" | DUR: ${target.durationSec}s (${playbackMs}ms) | VIDEO_ID: ${target.videoId}")
            println("#".repeat(120))

            val candidateRecords = mutableListOf<CandidateAuditRecord>()

            val providersToAudit: List<Pair<String, suspend () -> LyricsCandidate?>> = listOf(
                "BETTER_LYRICS" to { betterLyricsSource.search(query) },
                "PAXSENIX" to { paxsenixSource.search(query) },
                "UNISON" to { unisonSource.search(query) },
                "LRCLIB" to { lrcLibSource.search(query) },
                "NETEASE" to { netEaseSource.search(query) },
                "MUSIXMATCH" to { musixmatchSource.search(query) },
                "KUGOU" to { kuGouSource.search(query) },
                "JIOSAAVN" to { jioSaavnSource.search(query) }
            )

            for ((pName, fetchAction) in providersToAudit) {
                val t0 = System.currentTimeMillis()
                var cand: LyricsCandidate? = null
                var status = "200"
                try {
                    cand = fetchAction()
                    if (cand == null) status = "NULL/404"
                } catch (e: Exception) {
                    status = "ERR: ${e.javaClass.simpleName}: ${e.message}"
                }
                val latency = System.currentTimeMillis() - t0

                if (cand != null) {
                    val tier = LyricsClient.tierOf(cand.lyricsData)
                    val wordCount = cand.lyricsData.lines.sumOf { l -> l.words?.count { it.duration != null } ?: 0 }
                    val firstLyricMs = cand.lyricsData.lines.firstOrNull { !it.isInstrumental }?.time
                    val alignment = LyricsAlignmentEngine.evaluateMasterMatch(
                        lyrics = cand.lyricsData,
                        playbackDurationMs = playbackMs,
                        playbackTitle = target.title,
                        candidateTitle = cand.lyricsData.trackName,
                        playbackVideoId = target.videoId
                    )
                    val score = LyricsClient.calculateQualityScore(
                        cand = cand,
                        queryDurationSec = target.durationSec,
                        queryDurationMs = playbackMs,
                        queryTitle = target.title,
                        queryVideoId = target.videoId
                    )

                    var rejectionReason: String? = null
                    val lyricDur = cand.lyricsData.effectiveDurationMs
                    val deltaMs = abs(playbackMs - lyricDur)
                    if (alignment == MasterMatchStatus.MASTER_MISMATCH) {
                        if (tier == LyricsClient.TIER_WORD) {
                            rejectionReason = "REJECTED: Word sync with MASTER_MISMATCH (delta=${deltaMs}ms)"
                        } else if (deltaMs > 15_000L) {
                            rejectionReason = "REJECTED: Line sync with severe delta (${deltaMs}ms > 15s)"
                        }
                    }
                    if (score < 0) {
                        rejectionReason = (rejectionReason ?: "") + " REJECTED: Score < 0 ($score)"
                    }

                    val rec = CandidateAuditRecord(
                        provider = cand.provider,
                        responseStatus = "$status (${latency}ms)",
                        syncType = cand.syncType,
                        tier = tier,
                        wordCount = wordCount,
                        durationMs = cand.lyricsData.durationMs,
                        effectiveDurationMs = cand.lyricsData.effectiveDurationMs,
                        leadingSilenceMs = cand.lyricsData.leadingSilenceMs,
                        firstLyricMs = firstLyricMs,
                        matchedVideoId = cand.matchedVideoId,
                        isExactVideoMatch = cand.isExactVideoMatch,
                        alignmentResult = alignment,
                        rejectionReason = rejectionReason,
                        finalScore = score
                    )
                    candidateRecords.add(rec)
                } else {
                    val rec = CandidateAuditRecord(
                        provider = LyricsProvider.valueOf(pName),
                        responseStatus = "$status (${latency}ms)",
                        syncType = SyncType.PLAIN,
                        tier = LyricsClient.TIER_NONE,
                        wordCount = 0,
                        durationMs = null,
                        effectiveDurationMs = 0L,
                        leadingSilenceMs = null,
                        firstLyricMs = null,
                        matchedVideoId = null,
                        isExactVideoMatch = false,
                        alignmentResult = MasterMatchStatus.MASTER_MISMATCH,
                        rejectionReason = "No candidate returned ($status)",
                        finalScore = -1000.0
                    )
                    candidateRecords.add(rec)
                }
            }

            // Print candidate table
            println(String.format("%-15s | %-16s | %-10s | %-4s | %-5s | %-8s | %-8s | %-10s | %-16s | %-7s | %s",
                "Provider", "Status", "SyncType", "Tier", "Words", "DurMs", "1stLyric", "Alignment", "Score", "ExactVid", "Rejection/Notes"))
            println("-".repeat(140))
            for (r in candidateRecords) {
                println(String.format("%-15s | %-16s | %-10s | %-4d | %-5d | %-8s | %-8s | %-10s | %7.1f | %-7s | %s",
                    r.provider.name,
                    r.responseStatus.take(16),
                    r.syncType.name,
                    r.tier,
                    r.wordCount,
                    r.durationMs?.toString() ?: "null",
                    r.firstLyricMs?.toString() ?: "null",
                    r.alignmentResult.name,
                    r.finalScore,
                    r.isExactVideoMatch.toString(),
                    r.rejectionReason ?: "ACCEPTED"
                ))
            }

            // Now run actual full LyricsClient race
            val raceT0 = System.currentTimeMillis()
            val raceWinner = fullClient.getLyrics(
                title = target.title,
                artist = target.artist,
                durationSec = target.durationSec,
                videoId = target.videoId,
                album = target.album,
                durationMs = playbackMs
            )
            val raceElapsed = System.currentTimeMillis() - raceT0
            println("\n>>> FULL RACE WINNER: ${raceWinner?.provider?.name ?: "NONE"} (${raceWinner?.syncType?.name ?: "NONE"}), words=${raceWinner?.lines?.sumOf { l -> l.words?.count { it.duration != null } ?: 0 } ?: 0}, tier=${raceWinner?.let { LyricsClient.tierOf(it) } ?: 0} in ${raceElapsed}ms")
        }
        println("\n" + "=".repeat(140) + "\n")
    }
}
