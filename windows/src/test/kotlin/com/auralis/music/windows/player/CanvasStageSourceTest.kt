package com.auralis.music.windows.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertNull
import com.auralis.music.domain.model.Track
import com.auralis.music.util.PropertiesFileKeyValueStore
import kotlin.io.path.createTempDirectory

class CanvasStageSourceTest {
    @Test fun cachedCanvasCanFinishItsReleaseCheckAfterTheDownloadHeadStart() = runBlocking {
        val cached = CompletableDeferred<String?>()
        val download = CompletableDeferred<String?>()
        val album = CompletableDeferred<String?>("album")
        val selection = async(start = CoroutineStart.UNDISPATCHED) { chooseAutoStageSource(cached, download, album) }
        assertFalse(selection.isCompleted, "An already-ready album must wait for the local check")
        cached.complete("local canvas")
        assertEquals("local canvas" to album, withTimeout(1_000L) { selection.await() })
    }

    @Test fun autoPrefersReadySpotifyAndFallsBackWhenItIsSlow() = runBlocking {
        val album = CompletableDeferred<String?>("album")
        val spotify = CompletableDeferred<String?>()
        assertEquals("album" to spotify, preferredStageSource(spotify, album, graceMs = 1))
        spotify.complete("spotify")
        assertEquals("spotify" to album, preferredStageSource(spotify, album, graceMs = 1_000))
    }

    @Test fun aLateCanvasCannotReplaceAlbumMotionThatAlreadyStarted() {
        val prefs = CanvasPrefs()
        val album = StageVideo("album")
        val canvas = StageVideo("canvas", "spotify")
        val play = StagePlaybackChoice()
        play.commit(prefs, null, album)
        play.commit(prefs, null, canvas)
        assertEquals(album, play.current(prefs, null))
        assertNull(StagePlaybackChoice().current(prefs, null))
        assertNull(play.current(prefs, StageVisual.SPOTIFY))
        play.forgetFailed(prefs, null, album.path)
        play.commit(prefs, null, canvas)
        assertEquals(canvas, play.current(prefs, null))
    }

    @Test fun songChoiceSurvivesRestartAndCanBeResetForOneRelease() {
        val dir = createTempDirectory().toFile()
        try {
            val file = dir.resolve("stage-visuals.properties")
            val song = Track(id = "song", albumId = "album")
            val otherRelease = song.copy(albumId = "single")
            SongStageVisuals(PropertiesFileKeyValueStore(file, synchronous = true)).set(song, StageVisual.ALBUM)
            val reloaded = SongStageVisuals(PropertiesFileKeyValueStore(file, synchronous = true))
            assertEquals(StageVisual.ALBUM, reloaded.get(song))
            assertNull(reloaded.get(otherRelease))
            reloaded.set(song, null)
            assertNull(SongStageVisuals(PropertiesFileKeyValueStore(file)).get(song))
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test fun readyCanvasWaitsForAlbumLookup() = runBlocking {
        val album = CompletableDeferred<String?>()
        val canvas = CompletableDeferred<String?>("canvas")
        val selected = async(start = CoroutineStart.UNDISPATCHED) { preferredStageSource(album, canvas) }
        try {
            assertFalse(selected.isCompleted)
        } finally {
            album.complete("album")
        }
        val (first, alternate) = withTimeout(1_000) { selected.await() }
        assertEquals("album", first)
        assertSame(canvas, alternate)
    }

    @Test fun readyAlbumWinsWhenBothSourcesAreReady() = runBlocking {
        val album = CompletableDeferred<String?>("album")
        val canvas = CompletableDeferred<String?>("canvas")
        assertEquals("album" to canvas, preferredStageSource(album, canvas))
    }

    @Test fun aMissDoesNotHideTheOtherSource() = runBlocking {
        val album = CompletableDeferred<String?>().apply { complete(null) }
        val canvas = CompletableDeferred<String?>("canvas")
        assertEquals("canvas" to null, preferredStageSource(album, canvas))
    }
}
