package com.auralis.music.windows

import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.windows.search.DesktopSearchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * `Auralis.exe --self-test "<query>"`: checks an installed build end to end without anyone at the
 * keyboard. It searches, plays the top song, opens the player, waits for lyrics, writes a report
 * to %APPDATA%\Auralis\self-test.log and exits (code 0 when every step passed).
 */
class SelfTest(val query: String, private val report: File) {
    private val lines = mutableListOf<String>()
    private var failed = false

    private fun step(name: String, ok: Boolean, detail: String) {
        if (!ok) failed = true
        lines += "${if (ok) "PASS" else "FAIL"}  $name: $detail"
        com.auralis.music.util.Log.i("SelfTest", lines.last())
    }

    suspend fun run(
        search: DesktopSearchState,
        playback: DesktopPlaybackController?,
        lyrics: DesktopLyricsController?,
        openPlayer: () -> Unit
    ): Boolean {
        lines += "Auralis self-test, ${java.time.LocalDateTime.now()}, query \"$query\""
        lines += "libVLC: ${VlcAudioPlayer.loadedFrom ?: "not loaded"}"
        step("libVLC", playback != null, VlcAudioPlayer.loadedFrom ?: "no libVLC")

        val t0 = System.currentTimeMillis()
        search.onQueryChange(query)
        search.search(recordHistory = false)
        val found = withTimeoutOrNull(20_000) {
            while (search.isSearching || search.results.songs.isEmpty()) {
                if (!search.isSearching && search.hasSubmitted && search.searchFailed) return@withTimeoutOrNull false
                delay(100)
            }
            true
        } == true
        val songs = search.results.songs
        step("search", found, "${songs.size} songs in ${System.currentTimeMillis() - t0}ms; top: ${songs.firstOrNull()?.let { "${it.title} – ${it.artist}" }}")
        if (!found || playback == null || lyrics == null) return finish()

        val top = (search.results.topResult as? com.auralis.music.domain.model.SearchTopResult.SongResult)?.track ?: songs.first()
        val t1 = System.currentTimeMillis()
        playback.playFrom(top, songs)
        openPlayer()
        val playing = withTimeoutOrNull(20_000) {
            while (playback.state.value != VlcAudioPlayer.State.PLAYING) delay(100)
            true
        } == true
        step("playback starts", playing, "${top.title} in ${System.currentTimeMillis() - t1}ms (state ${playback.state.value})")
        if (playing) {
            val p0 = playback.positionMs.value
            val samples = mutableListOf<String>()
            repeat(8) {
                delay(500)
                samples += "${playback.positionMs.value}/${playback.state.value.name.take(4)}"
            }
            val p1 = playback.positionMs.value
            com.auralis.music.util.Log.i("SelfTest", "positions every 500ms: $samples")
            step("playback advances", p1 - p0 in 3000..5500, "position ${p0}ms -> ${p1}ms over 4s, length ${playback.lengthMs.value}ms")
            // Seek past the first megabyte or so, where the stream server is pickiest.
            val length = playback.lengthMs.value
            if (length > 30_000L) {
                val target = length * 6 / 10
                playback.seekTo(target)
                delay(3000)
                val after = playback.positionMs.value
                step(
                    "seek",
                    after in target..target + 4500 && playback.state.value == VlcAudioPlayer.State.PLAYING,
                    "to ${target}ms, at ${after}ms 3s later (state ${playback.state.value})"
                )
            }
        }

        val lyricState = withTimeoutOrNull(25_000) {
            while (lyrics.state.value.isLoading || lyrics.state.value.trackId != top.id) delay(200)
            lyrics.state.value
        }
        val data = lyricState?.lyrics
        step(
            "lyrics",
            data != null && data.lines.isNotEmpty(),
            if (data == null) "none after ${System.currentTimeMillis() - t1}ms" else "${data.provider} ${data.syncType}, ${data.lines.size} lines"
        )
        // Leave the player up for a moment so a screenshot can be taken.
        delay(8000)
        return finish()
    }

    private fun finish(): Boolean {
        lines += if (failed) "RESULT: FAIL" else "RESULT: PASS"
        runCatching { report.parentFile?.mkdirs(); report.writeText(lines.joinToString(System.lineSeparator()) + System.lineSeparator()) }
        return !failed
    }

    companion object {
        /** `--self-test "<query>"` or `--self-test=<query>`; null for a normal launch. */
        fun fromArgs(args: Array<String>, dataDir: File): SelfTest? {
            val i = args.indexOfFirst { it == "--self-test" || it.startsWith("--self-test=") }
            if (i < 0) return null
            val query = args[i].substringAfter("=", "").ifBlank { args.getOrNull(i + 1).orEmpty() }.ifBlank { "blinding lights" }
            return SelfTest(query, File(dataDir, "self-test.log"))
        }
    }
}
