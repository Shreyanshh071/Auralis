package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.BetterLyricsSource
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.data.network.provider.UnisonLyricsSource
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LiveLyricsClientAuditTest {

    data class SongTest(
        val title: String,
        val artist: String,
        val durationSec: Long,
        val videoId: String? = null,
        val album: String? = null
    )

    @Test
    fun auditDetailedProviderBreakdown() = runBlocking {
        val testSongs = listOf(
            SongTest("Touch", "KATSEYE", 143L, "H5tO_9wZ0hg", "SIS (Soft Is Strong)"),
            SongTest("Touch", "KATSEYE", 130L, "H5tO_9wZ0hg", "SIS (Soft Is Strong)"),
            SongTest("Loving Machine", "TV Girl", 227L, "5tpQaCAq6Qc", "Who Really Cares"),
            SongTest("MIDDLE OF THE NIGHT", "Elley Duhé", 184L, "4zsVKROnQfY", "MIDDLE OF THE NIGHT"),
            SongTest("Feel Good Inc.", "Gorillaz", 223L, "NxxjLD2pmlk", "Demon Days"),
            SongTest("Like a Prayer", "Madonna", 340L, "ilottRbDnGY", "Like a Prayer"),
            SongTest("Bitter Sweet Symphony", "The Verve", 276L, "_UWOHofs0kA", "Urban Hymns"),
            SongTest("Sunflower", "Post Malone & Swae Lee", 158L, "r7Rn4ryE_w8", "Spider-Man: Into the Spider-Verse"),
            SongTest("Fake Plastic Trees", "Radiohead", 290L, "6gDhsUWCHrg", "The Bends"),
            SongTest("Love Me Not", "Ravyn Lenae", 213L, "HfpR4tAmI7E", "Bird's Eye"),
            SongTest("STAY", "The Kid LAROI & Justin Bieber", 141L, "XfEMj-z3TtA", "F*CK LOVE 3: OVER YOU"),
            SongTest("Shape of My Heart", "Sting", 278L, "RVfGFmBFgoc", "Ten Summoner's Tales"),
            SongTest("Starboy", "The Weeknd", 230L, "3_g2un5M350", "Starboy"),
            SongTest("This Is What You Came For", "Calvin Harris ft. Rihanna", 239L, "inCm3ByI17o", "This Is What You Came For")
        )

        val unisonSource = UnisonLyricsSource()
        val betterLyricsSource = BetterLyricsSource()

        println("\n" + "=".repeat(100))
        println("DETAILED PROVIDER BREAKDOWN (UNISON vs BETTER_LYRICS)")
        println("=".repeat(100))

        for (s in testSongs) {
            val query = LyricsSearchQuery(
                title = s.title,
                artist = s.artist,
                durationSec = s.durationSec,
                videoId = s.videoId,
                album = s.album,
                durationMs = s.durationSec * 1000L
            )

            println("\n>>> TRACK: \"${s.title}\" by \"${s.artist}\" (playback=${s.durationSec}s, videoId=${s.videoId})")

            // 1. Check Unison
            val t0 = System.currentTimeMillis()
            val uCand = try { unisonSource.search(query) } catch (e: Exception) { println("    UNISON EXCEPTION: $e"); null }
            val uTime = System.currentTimeMillis() - t0
            if (uCand != null) {
                val uTier = LyricsClient.tierOf(uCand.lyricsData)
                val uDur = uCand.lyricsData.effectiveDurationMs
                val uMatch = LyricsAlignmentEngine.evaluateMasterMatch(
                    lyrics = uCand.lyricsData,
                    playbackDurationMs = s.durationSec * 1000L,
                    playbackTitle = s.title,
                    candidateTitle = uCand.lyricsData.trackName
                )
                val uWords = uCand.lyricsData.lines.sumOf { l -> l.words?.count { it.duration != null } ?: 0 }
                println("    [UNISON] in ${uTime}ms: syncType=${uCand.syncType}, tier=$uTier, words=$uWords, dur=${uDur}ms, match=$uMatch, confidence=${uCand.confidence}%")
                if (uMatch == MasterMatchStatus.MASTER_MISMATCH && uTier == LyricsClient.TIER_WORD) {
                    println("             => REJECTED by LyricsClient (tier==TIER_WORD && masterMatch==MASTER_MISMATCH)!")
                }
            } else {
                println("    [UNISON] in ${uTime}ms: NULL (No lyrics found)")
            }

            // 2. Check BetterLyrics
            val t1 = System.currentTimeMillis()
            val bCand = try { betterLyricsSource.search(query) } catch (e: Exception) { println("    BETTER_LYRICS EXCEPTION: $e"); null }
            val bTime = System.currentTimeMillis() - t1
            if (bCand != null) {
                val bTier = LyricsClient.tierOf(bCand.lyricsData)
                val bDur = bCand.lyricsData.effectiveDurationMs
                val bMatch = LyricsAlignmentEngine.evaluateMasterMatch(
                    lyrics = bCand.lyricsData,
                    playbackDurationMs = s.durationSec * 1000L,
                    playbackTitle = s.title,
                    candidateTitle = bCand.lyricsData.trackName
                )
                val bWords = bCand.lyricsData.lines.sumOf { l -> l.words?.count { it.duration != null } ?: 0 }
                println("    [BETTER_LYRICS] in ${bTime}ms: syncType=${bCand.syncType}, tier=$bTier, words=$bWords, dur=${bDur}ms, match=$bMatch, confidence=${bCand.confidence}%")
                if (bMatch == MasterMatchStatus.MASTER_MISMATCH && bTier == LyricsClient.TIER_WORD) {
                    println("             => REJECTED by LyricsClient (tier==TIER_WORD && masterMatch==MASTER_MISMATCH)!")
                }
            } else {
                println("    [BETTER_LYRICS] in ${bTime}ms: NULL (No lyrics found)")
            }

            // 3. Check Paxsenix
            val paxsenixSource = com.auralis.music.data.network.provider.PaxsenixLyricsSource()
            val t2 = System.currentTimeMillis()
            val pCand = try { paxsenixSource.search(query) } catch (e: Exception) { println("    PAXSENIX EXCEPTION: $e"); null }
            val pTime = System.currentTimeMillis() - t2
            if (pCand != null) {
                val pTier = LyricsClient.tierOf(pCand.lyricsData)
                val pDur = pCand.lyricsData.effectiveDurationMs
                val pMatch = LyricsAlignmentEngine.evaluateMasterMatch(
                    lyrics = pCand.lyricsData,
                    playbackDurationMs = s.durationSec * 1000L,
                    playbackTitle = s.title,
                    candidateTitle = pCand.lyricsData.trackName
                )
                val pWords = pCand.lyricsData.lines.sumOf { l -> l.words?.count { it.duration != null } ?: 0 }
                val firstLyricMs = pCand.lyricsData.lines.firstOrNull { !it.isInstrumental }?.time ?: 0L
                println("    [PAXSENIX] in ${pTime}ms: syncType=${pCand.syncType}, tier=$pTier, words=$pWords, dur=${pDur}ms, firstLyric=${firstLyricMs}ms, match=$pMatch, confidence=${pCand.confidence}%")
                if (pMatch == MasterMatchStatus.MASTER_MISMATCH && pTier == LyricsClient.TIER_WORD) {
                    println("               => REJECTED by LyricsClient (tier==TIER_WORD && masterMatch==MASTER_MISMATCH)!")
                }
            } else {
                println("    [PAXSENIX] in ${pTime}ms: NULL (No lyrics found or duration delta > 3.5s)")
            }
        }
        println("\n" + "=".repeat(100) + "\n")
    }

    @Test
    fun audit13SongPaxsenixMatrix() = runBlocking {
        val testSongs = listOf(
            SongTest("Touch", "KATSEYE", 130L, "H5tO_9wZ0hg", "SIS (Soft Is Strong)"),
            SongTest("Loving Machine", "TV Girl", 227L, "5tpQaCAq6Qc", "Who Really Cares"),
            SongTest("MIDDLE OF THE NIGHT", "Elley Duhé", 184L, "4zsVKROnQfY", "MIDDLE OF THE NIGHT"),
            SongTest("Bitter Sweet Symphony", "The Verve", 276L, "_UWOHofs0kA", "Urban Hymns"),
            SongTest("This Is What You Came For", "Calvin Harris ft. Rihanna", 239L, "inCm3ByI17o", "This Is What You Came For"),
            SongTest("Fake Plastic Trees", "Radiohead", 290L, "6gDhsUWCHrg", "The Bends"),
            SongTest("Feel Good Inc.", "Gorillaz", 223L, "NxxjLD2pmlk", "Demon Days"),
            SongTest("Like a Prayer", "Madonna", 340L, "ilottRbDnGY", "Like a Prayer"),
            SongTest("Sunflower", "Post Malone & Swae Lee", 158L, "r7Rn4ryE_w8", "Spider-Man: Into the Spider-Verse"),
            SongTest("Love Me Not", "Ravyn Lenae", 213L, "HfpR4tAmI7E", "Bird's Eye"),
            SongTest("STAY", "The Kid LAROI & Justin Bieber", 141L, "XfEMj-z3TtA", "F*CK LOVE 3: OVER YOU"),
            SongTest("Shape of My Heart", "Sting", 278L, "RVfGFmBFgoc", "Ten Summoner's Tales"),
            SongTest("Starboy", "The Weeknd", 230L, "3_g2un5M350", "Starboy")
        )

        val paxsenixSource = com.auralis.music.data.network.provider.PaxsenixLyricsSource()
        val fullClient = LyricsClient()

        println("\n" + "=".repeat(130))
        println(String.format("%-25s | %-7s | %-12s | %-8s | %-10s | %-7s | %-10s | %-16s | %-12s | %-7s",
            "Track", "PaxHTTP", "AppleMusicID", "AppleDur", "SyncType", "Words", "1stLyricMs", "MasterMatch", "Winner", "Latency"))
        println("-".repeat(130))

        for (s in testSongs) {
            val query = LyricsSearchQuery(
                title = s.title,
                artist = s.artist,
                durationSec = s.durationSec,
                videoId = s.videoId,
                album = s.album,
                durationMs = s.durationSec * 1000L
            )

            val t0 = System.currentTimeMillis()
            val pCand = try { paxsenixSource.search(query) } catch (_: Exception) { null }
            val pLatency = System.currentTimeMillis() - t0

            // Query Apple Music track info directly to get ID and duration even if rejected by delta
            val amTrack = try {
                paxsenixSource.searchAppleMusicTrack(
                    com.auralis.music.data.network.TitleCleaner.cleanCoreSongTitle(s.title),
                    s.artist,
                    s.durationSec * 1000L,
                    s.album
                )
            } catch (_: Exception) { null }

            val appleId = amTrack?.id ?: "N/A"
            val appleDurSec = amTrack?.durationInMillis?.let { "${it / 1000}s" } ?: "N/A"
            val paxHttp = if (amTrack != null) (if (pCand != null) "200" else "REJ") else "404"
            val syncType = pCand?.syncType?.name ?: "NONE"
            val wordCount = pCand?.lyricsData?.lines?.sumOf { l -> l.words?.count { it.duration != null } ?: 0 } ?: 0
            val firstLyric = pCand?.lyricsData?.lines?.firstOrNull { !it.isInstrumental }?.time?.let { "${it}ms" } ?: "N/A"

            val masterMatch = if (amTrack != null && amTrack.durationInMillis != null) {
                val delta = kotlin.math.abs(s.durationSec * 1000L - amTrack.durationInMillis)
                when {
                    delta <= 1500L -> "EXACT_MATCH"
                    delta <= 3500L -> "COMPATIBLE"
                    else -> "MASTER_MISMATCH"
                }
            } else "N/A"

            // Run full cascade winner
            val winner = fullClient.getLyrics(
                title = s.title,
                artist = s.artist,
                durationSec = s.durationSec,
                videoId = s.videoId,
                album = s.album,
                durationMs = s.durationSec * 1000L
            )
            val winnerStr = "${winner?.provider ?: "NONE"}(${winner?.syncType ?: "NONE"})"

            println(String.format("%-25s | %-7s | %-12s | %-8s | %-10s | %-7d | %-10s | %-16s | %-12s | %-5dms",
                s.title.take(25), paxHttp, appleId.take(12), appleDurSec, syncType, wordCount, firstLyric, masterMatch, winnerStr, pLatency))
        }
        println("=".repeat(130) + "\n")
    }

    @Test
    fun auditFullLyricsClientRace() = runBlocking {
        val testSongs = listOf(
            SongTest("Touch", "KATSEYE", 143L, "H5tO_9wZ0hg", "SIS (Soft Is Strong)"),
            SongTest("Touch", "KATSEYE", 130L, "H5tO_9wZ0hg", "SIS (Soft Is Strong)"),
            SongTest("Loving Machine", "TV Girl", 227L, "5tpQaCAq6Qc", "Who Really Cares"),
            SongTest("MIDDLE OF THE NIGHT", "Elley Duhé", 184L, "4zsVKROnQfY", "MIDDLE OF THE NIGHT"),
            SongTest("Feel Good Inc.", "Gorillaz", 223L, "NxxjLD2pmlk", "Demon Days"),
            SongTest("Like a Prayer", "Madonna", 340L, "ilottRbDnGY", "Like a Prayer"),
            SongTest("Bitter Sweet Symphony", "The Verve", 276L, "_UWOHofs0kA", "Urban Hymns"),
            SongTest("Sunflower", "Post Malone & Swae Lee", 158L, "r7Rn4ryE_w8", "Spider-Man: Into the Spider-Verse"),
            SongTest("Fake Plastic Trees", "Radiohead", 290L, "6gDhsUWCHrg", "The Bends"),
            SongTest("Love Me Not", "Ravyn Lenae", 213L, "HfpR4tAmI7E", "Bird's Eye"),
            SongTest("STAY", "The Kid LAROI & Justin Bieber", 141L, "XfEMj-z3TtA", "F*CK LOVE 3: OVER YOU"),
            SongTest("Shape of My Heart", "Sting", 278L, "RVfGFmBFgoc", "Ten Summoner's Tales"),
            SongTest("Starboy", "The Weeknd", 230L, "3_g2un5M350", "Starboy"),
            SongTest("This Is What You Came For", "Calvin Harris ft. Rihanna", 239L, "inCm3ByI17o", "This Is What You Came For")
        )

        val client = LyricsClient()

        println("\n" + "=".repeat(100))
        println("FULL LYRICS CLIENT RACE AUDIT (ALL PROVIDERS RACING)")
        println("=".repeat(100))

        for (s in testSongs) {
            val t0 = System.currentTimeMillis()
            val winner = client.getLyrics(
                title = s.title,
                artist = s.artist,
                durationSec = s.durationSec,
                videoId = s.videoId,
                album = s.album,
                durationMs = s.durationSec * 1000L
            )
            val elapsed = System.currentTimeMillis() - t0

            if (winner != null) {
                val tier = LyricsClient.tierOf(winner)
                val words = winner.lines.sumOf { l -> l.words?.count { it.duration != null } ?: 0 }
                val match = LyricsAlignmentEngine.evaluateMasterMatch(
                    lyrics = winner,
                    playbackDurationMs = s.durationSec * 1000L,
                    playbackTitle = s.title,
                    candidateTitle = winner.trackName,
                    playbackVideoId = s.videoId
                )
                println(String.format(">>> %-30s (%3ds): WINNER=%-15s sync=%-10s tier=%d words=%-4d dur=%6dms match=%-18s [%4dms]",
                    "\"${s.title}\"", s.durationSec, winner.provider, winner.syncType, tier, words, winner.effectiveDurationMs, match, elapsed))
            } else {
                println(String.format(">>> %-30s (%3ds): NO LYRICS FOUND [%4dms]",
                    "\"${s.title}\"", s.durationSec, elapsed))
            }
        }
        println("\n" + "=".repeat(100) + "\n")
    }
}

