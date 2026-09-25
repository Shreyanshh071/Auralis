package com.auralis.music.ui.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.auralis.music.data.local.dao.LyricsDao
import com.auralis.music.data.local.entity.LyricsEntity
import com.auralis.music.data.network.AiLyricsTranslator
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.LyricsRepository
import com.auralis.music.domain.repository.SettingsRepository
import com.auralis.music.ui.viewmodel.PlayerViewModel
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 7.7 — where speaker agents are lost on the way to [ExperimentalLyricsView].
 *
 * Fixtures under `test/resources/lyrics/speaker` are the provider responses Auralis actually
 * received on 2026-09-25 for the exact YouTube Music tracks in `tracks.json` (Paxsenix /
 * BetterLyrics Apple TTML, LRCLIB LRC). Every `<p>` keeps its real begin/end/ttm:agent and the
 * head keeps the real agent declarations; lyric text is trimmed to two words per line.
 *
 * Root cause proven here: [AiLyricsTranslator] cached its result per *track id* only. Every
 * lyrics load runs it (it returns a result even without an API key), so the first lyrics shown
 * for a track — e.g. an older untagged cached copy — were cached, and when the speaker-tagged
 * copy arrived later in the same process the translator returned the stale copy and
 * PlayerViewModel put the untagged lines back on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerDataFlowRegressionTest {

    private data class Song(
        val slug: String,
        val taggedProvider: LyricsProvider,
        val untaggedProvider: LyricsProvider,
        val expectedAgents: Map<String?, Int>
    )

    private val songs = listOf(
        Song("gerua", LyricsProvider.BETTER_LYRICS, LyricsProvider.LRCLIB, mapOf("v1" to 39, "v2" to 14, "v1000" to 1)),
        Song("samjhawan", LyricsProvider.PAXSENIX, LyricsProvider.LRCLIB, mapOf("v1" to 35, "v2" to 15)),
        Song("agar_tum_saath_ho", LyricsProvider.PAXSENIX, LyricsProvider.BETTER_LYRICS, mapOf("v1" to 27, "v2" to 22)),
        Song("feel_good_inc", LyricsProvider.PAXSENIX, LyricsProvider.LRCLIB, mapOf("v1" to 41, "v2" to 20)),
        Song("tere_liye", LyricsProvider.BETTER_LYRICS, LyricsProvider.LRCLIB, mapOf("v1" to 15, "v2" to 16, "v1000" to 4))
    )

    private val metro = AppearanceSettings(lyricsAnimation = LyricsAnimationMode.METRO_LYRICS.displayName)

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("lyrics/speaker/$name")) { name }.readText(Charsets.UTF_8)

    private fun track(slug: String): Track {
        val t = JSONObject(resource("tracks.json")).getJSONObject(slug)
        return Track(
            id = t.getString("trackId"),
            title = t.getString("title"),
            artist = t.getString("artist"),
            album = t.optString("album").takeIf { it.isNotBlank() },
            duration = t.getLong("durationSec")
        )
    }

    private fun tagged(song: Song): LyricsData =
        TtmlParser.parse(resource("${song.slug}.tagged.ttml"), song.taggedProvider)
            .copy(trackName = track(song.slug).title, artistName = track(song.slug).artist)

    private fun untagged(song: Song): LyricsData {
        val lrc = javaClass.classLoader!!.getResource("lyrics/speaker/${song.slug}.untagged.lrc")
        val data = if (lrc != null) LrcParser.parse(lrc.readText(Charsets.UTF_8), song.untaggedProvider)
        else TtmlParser.parse(resource("${song.slug}.untagged.ttml"), song.untaggedProvider)
        return data.copy(trackName = track(song.slug).title, artistName = track(song.slug).artist)
    }

    /** Exactly what ExperimentalLyricsView does with `lyrics.lines` before building the layout. */
    private fun viewLines(data: LyricsData): List<LyricLine> = data.lines
        .map { WordTiming.splitMergedWordsInLine(it) }
        .filter { it.text.isNotBlank() || it.words?.any { w -> w.word.isNotBlank() } == true }

    private fun agentCounts(lines: List<LyricLine>) = lines.groupingBy { it.agent }.eachCount()

    // ── 1. Fresh data: provider -> repository -> cache -> translator -> view ──

    @Test
    fun `speaker-tagged lyrics reach ExperimentalLyricsView with their agents for all five songs`() = runBlocking {
        for (song in songs) {
            val t = track(song.slug)
            val durationMs = t.duration * 1000L
            val client = mockk<LyricsClient>()
            coEvery { client.getLyrics(any(), any(), any(), any(), any(), any(), any(), any()) } returns tagged(song)
            val saved = slot<LyricsEntity>()
            val dao = mockk<LyricsDao>(relaxed = true)
            coEvery { dao.getLyrics(any()) } answers { if (saved.isCaptured) saved.captured else null }
            coEvery { dao.insertLyrics(capture(saved)) } returns Unit
            val repository = LyricsRepositoryImpl(lyricsClient = client, lyricsDao = dao)

            // Network result, as PlayerViewModel's background refresh receives it
            val network = repository.getLyrics(t.title, t.artist, t.duration, t.id, true, t.album, null, durationMs, null)!!
            // What the next session reads back from the Room cache
            val fromCache = LyricsRepositoryImpl(lyricsClient = mockk(), lyricsDao = dao)
                .getCachedLyrics(t.title, t.artist, t.duration, t.id, t.album, null, durationMs, null)!!

            for ((stage, data) in listOf("network" to network, "cache" to fromCache)) {
                val translated = AiLyricsTranslator.translateLyrics("fresh_${stage}_${song.slug}", data, AiTranslationSettings()) ?: data
                val lines = viewLines(translated)
                assertEquals("${song.slug} $stage agents", song.expectedAgents, agentCounts(lines))
                assertNotNull("${song.slug} $stage layout", MetroSpeakerLayout.forAppearance(lines, metro))
            }
        }
    }

    // ── 2. The divergence: an older untagged copy shown first in the same process ──

    @Test
    fun `tagged lyrics arriving after an untagged copy of the same track keep their agents through translation`() = runBlocking {
        for (song in songs) {
            val trackId = "stale_${song.slug}"
            val first = untagged(song)
            assertNull("${song.slug}: the first copy has no usable speakers", MetroSpeakerLayout.forAppearance(viewLines(first), metro))
            AiLyricsTranslator.translateLyrics(trackId, first, AiTranslationSettings())

            val upgraded = tagged(song)
            val shown = AiLyricsTranslator.translateLyrics(trackId, upgraded, AiTranslationSettings()) ?: upgraded

            assertEquals("${song.slug}: provider on screen", upgraded.provider, shown.provider)
            assertEquals("${song.slug}: agents on screen", song.expectedAgents, agentCounts(viewLines(shown)))
            assertNotNull("${song.slug}: layout", MetroSpeakerLayout.forAppearance(viewLines(shown), metro))
        }
    }

    @Test
    fun `synced lyrics found after a plain fallback are not replaced by the plain copy`() = runBlocking {
        // A slow or failed race gives plain text first (Genius / YouTube); the synced copy arrives later.
        val synced = LrcParser.parse(resource("feel_good_inc.untagged.lrc"), LyricsProvider.LRCLIB)
        val plain = LyricsData(
            syncType = SyncType.PLAIN,
            lines = synced.lines.map { LyricLine(time = 0L, text = it.text) },
            provider = LyricsProvider.YOUTUBE
        )
        AiLyricsTranslator.translateLyrics("plain_then_synced", plain, AiTranslationSettings())
        val shown = AiLyricsTranslator.translateLyrics("plain_then_synced", synced, AiTranslationSettings()) ?: synced

        assertEquals(SyncType.LINE_SYNC, shown.syncType)
        assertEquals(LyricsProvider.LRCLIB, shown.provider)
        assertEquals(synced.lines.map { it.time }, shown.lines.map { it.time })
    }

    @Test
    fun `translation is still reused for identical lyrics`() = runBlocking {
        val song = songs.first { it.slug == "tere_liye" }
        val data = tagged(song)
        val a = AiLyricsTranslator.translateLyrics("reuse_tere", data, AiTranslationSettings())!!
        val b = AiLyricsTranslator.translateLyrics("reuse_tere", data, AiTranslationSettings())!!
        assertEquals(a.lines.map { it.translatedText }, b.lines.map { it.translatedText })
        assertEquals(data.lines.map { it.agent }, b.lines.map { it.agent })
    }

    // ── 3. The real PlayerViewModel sequence ──

    private class FakeLyricsRepo(private val cached: LyricsData, private val network: LyricsData) : LyricsRepository {
        override suspend fun getCachedLyrics(
            title: String, artist: String, durationSec: Long?, videoId: String?, album: String?,
            channelTitle: String?, durationMs: Long?, audioLeadingSilenceMs: Long?
        ): LyricsData? = cached

        override suspend fun getLyrics(
            title: String, artist: String, durationSec: Long?, videoId: String?, forceRefresh: Boolean,
            album: String?, channelTitle: String?, durationMs: Long?, audioLeadingSilenceMs: Long?
        ): LyricsData? {
            // The provider race takes seconds on a phone; the cached copy is translated long before.
            kotlinx.coroutines.delay(1_500L)
            return network
        }
    }

    @Test
    fun `plain interim stays behind loading until synced lyrics arrive`() = runBlocking {
        val t = track("gerua")
        val synced = untagged(songs.first { it.slug == "gerua" })
        val plain = LyricsData(
            syncType = SyncType.PLAIN,
            lines = synced.lines.map { LyricLine(time = 0L, text = it.text) },
            provider = LyricsProvider.GENIUS
        )
        val plainDelivered = CompletableDeferred<Unit>()
        val allowSynced = CompletableDeferred<Unit>()
        val syncedDelivered = CompletableDeferred<Unit>()
        val allowFinal = CompletableDeferred<Unit>()
        val repo = object : LyricsRepository {
            override suspend fun getLyricsWithInterim(
                title: String, artist: String, durationSec: Long?, videoId: String?,
                forceRefresh: Boolean, album: String?, channelTitle: String?, durationMs: Long?,
                audioLeadingSilenceMs: Long?, onInterim: (LyricsData) -> Unit
            ): LyricsData? {
                onInterim(plain)
                plainDelivered.complete(Unit)
                allowSynced.await()
                onInterim(synced)
                syncedDelivered.complete(Unit)
                allowFinal.await()
                return plain
            }
        }

        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(
                    libraryRepository = NoLibrary(), historyRepository = NoHistory(),
                    lyricsRepository = repo, settingsRepository = NoSettings(),
                    audioPlayer = null, innerTubeClient = NoRadioInnerTube()
                ) as T
            })[PlayerViewModel::class.java]

            vm.playTrack(t, listOf(t), 0)
            withTimeout(5_000L) { plainDelivered.await() }
            assertTrue("plain interim must leave the spinner visible", vm.uiState.value.isLoadingLyrics)
            assertNull("plain interim must not be shown", vm.uiState.value.lyrics)

            allowSynced.complete(Unit)
            withTimeout(5_000L) { syncedDelivered.await() }
            assertSame(synced, vm.uiState.value.lyrics)
            assertFalse(vm.uiState.value.isLoadingLyrics)

        } finally {
            allowSynced.complete(Unit)
            allowFinal.complete(Unit)
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private class NoRadioInnerTube : InnerTubeClient() {
        override suspend fun getRadioTracks(videoId: String, artist: String?, title: String?): List<Track> = emptyList()
    }

    private class NoLibrary : LibraryRepository {
        override fun getFavoriteTracks(): Flow<List<Track>> = flowOf(emptyList())
        override fun isFavorite(trackId: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggleFavorite(track: Track) {}
        override suspend fun setFavorite(track: Track, isFavorite: Boolean) {}
        override fun getPlaylists(): Flow<List<Playlist>> = flowOf(emptyList())
        override fun getPlaylist(playlistId: String): Flow<Playlist?> = flowOf(null)
        override suspend fun createPlaylist(title: String, description: String?, coverUrl: String?): Playlist = Playlist(id = "1", title = title, coverUrl = coverUrl)
        override suspend fun updatePlaylist(playlistId: String, title: String, description: String?, coverUrl: String?) {}
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track) {}
        override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {}
        override suspend fun deletePlaylist(playlistId: String) {}
        override suspend fun reorderPlaylist(playlistId: String, tracks: List<Track>) {}
        override suspend fun replacePlaylistTracks(playlistId: String, tracks: List<Track>) {}
        override fun getSavedArtists(): Flow<List<SavedArtist>> = flowOf(emptyList())
        override fun isArtistSaved(artistId: String): Flow<Boolean> = flowOf(false)
        override suspend fun saveArtist(artist: SavedArtist) {}
        override suspend fun removeArtist(artistId: String) {}
        override fun getSavedAlbums(): Flow<List<SavedAlbum>> = flowOf(emptyList())
        override fun isAlbumSaved(albumId: String): Flow<Boolean> = flowOf(false)
        override suspend fun saveAlbum(album: SavedAlbum) {}
        override suspend fun removeAlbum(albumId: String) {}
    }

    private class NoHistory : HistoryRepository {
        override fun getHistory(): Flow<List<HistoryEntry>> = flowOf(emptyList())
        override suspend fun addToHistory(track: Track) {}
        override suspend fun removeFromHistory(trackId: String) {}
        override suspend fun clearHistory() {}
        override fun getTopPlayedTracks(): Flow<List<PlayCountEntry>> = flowOf(emptyList())
        override suspend fun recordPlay(track: Track) {}
        override suspend fun getPlayCounts(): List<PlayCountEntry> = emptyList()
        override suspend fun getForgottenFavorites(cutoffTimestamp: Long): List<Track> = emptyList()
        override suspend fun getRecentHeavyRotation(fromTimestamp: Long): List<Track> = emptyList()
        override suspend fun getLikedSeeds(limit: Int): List<Track> = emptyList()
    }

    private class NoSettings : SettingsRepository {
        override val settingsFlow: Flow<PlayerSettings> = flowOf(PlayerSettings())
        override suspend fun updateSettings(settings: PlayerSettings) {}
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun setAudioQuality(quality: AudioQuality) {}
        override suspend fun setGaplessPlayback(enabled: Boolean) {}
        override suspend fun setSkipSilence(enabled: Boolean) {}
        override suspend fun setSpatialAudio(enabled: Boolean) {}
        override suspend fun setVolume(volume: Float) {}
        override suspend fun setPlaybackRate(rate: Float) {}
    }

    @Test
    fun `PlayerViewModel shows the upgraded speaker-tagged Gerua instead of the cached untagged copy`() {
        val song = songs.first { it.slug == "gerua" }
        val t = track(song.slug)
        val cachedLineSync = untagged(song)
        val networkTagged = tagged(song)
        assertEquals(SyncType.LINE_SYNC, cachedLineSync.syncType)

        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(
                    libraryRepository = NoLibrary(),
                    historyRepository = NoHistory(),
                    lyricsRepository = FakeLyricsRepo(cachedLineSync, networkTagged),
                    settingsRepository = NoSettings(),
                    audioPlayer = null,
                    innerTubeClient = NoRadioInnerTube()
                ) as T
            })[PlayerViewModel::class.java]

            vm.playTrack(t, listOf(t), 0)

            // Wait for the lyrics to settle, then require them to stay settled (no late stale overwrite).
            fun onScreen() = vm.uiState.value.lyrics
            fun settled() = onScreen()?.provider == networkTagged.provider &&
                agentCounts(viewLines(onScreen()!!)) == song.expectedAgents
            val deadline = System.currentTimeMillis() + 8_000L
            while (!settled() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val stableUntil = System.currentTimeMillis() + 750L
            while (settled() && System.currentTimeMillis() < stableUntil) Thread.sleep(20)

            val shown = onScreen()
            assertNotNull("lyrics on screen", shown)
            assertEquals("provider on screen", networkTagged.provider, shown!!.provider)
            assertEquals("agents on screen", song.expectedAgents, agentCounts(viewLines(shown)))
            assertNotNull(MetroSpeakerLayout.forAppearance(viewLines(shown), metro))
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    // ── 4. Songs genuinely without speaker metadata keep the old layout ──

    @Test
    fun `lyrics without line-level speakers keep the existing MetroLyrics layout`() {
        for (song in songs) {
            assertNull(song.slug, MetroSpeakerLayout.forAppearance(viewLines(untagged(song)), metro))
        }
        // Agar Tum Saath Ho's BetterLyrics copy tags every line v1: one singer is not a duet.
        val agarSolo = untagged(songs.first { it.slug == "agar_tum_saath_ho" })
        assertEquals(setOf("v1"), agarSolo.lines.mapNotNull { it.agent }.toSet())
    }
}
