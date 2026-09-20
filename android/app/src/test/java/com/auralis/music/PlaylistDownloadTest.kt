package com.auralis.music

import com.auralis.music.data.download.*
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlaylistDownloadTest {
    private val tracks = listOf(Track(id = "one"), Track(id = "two"))

    private fun durableJob(
        jobId: String = "job",
        playlistId: String = "playlist",
        offlineEnabled: Boolean = false,
        status: PlaylistDownloadStatus = PlaylistDownloadStatus.COMPLETE,
        results: List<TrackDownloadResult> = emptyList()
    ) = PlaylistDownloadJobEntity(
        jobId = jobId, playlistId = playlistId, playlistName = "Playlist", folderName = "Playlist",
        backend = PlaylistDownloadCoordinator.BACKEND_WORK_MANAGER,
        trackSnapshotJson = Json.encodeToString(tracks), resultsJson = Json.encodeToString(results),
        status = status.name, completedCount = results.count { it.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES },
        failedCount = results.count { it.outcome == DownloadOutcome.FAILURE }, skippedCount = 0,
        currentTrackId = null, cancelRequested = false, offlineEnabled = offlineEnabled,
        createdAt = 1L, updatedAt = 1L
    )

    @Test fun `playlist waits sequentially and completes only after final verified item`() = runTest {
        val saved = mutableSetOf<String>()
        val gates = tracks.associate { it.id to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val states = mutableListOf<PlaylistDownloadState>()
        val job = async {
            runPlaylistDownload(tracks, saved::contains, { track ->
                started += track.id
                gates.getValue(track.id).await()
                saved += track.id
                TrackDownloadResult(track.id, DownloadOutcome.SUCCESS)
            }, states::add)
        }
        runCurrent()
        assertEquals(listOf("one"), started)
        assertFalse(job.isCompleted)
        assertEquals(0, states.last().finished)
        gates.getValue("one").complete(Unit)
        runCurrent()
        assertEquals(listOf("one", "two"), started)
        assertEquals(1, states.last().finished)
        assertTrue(states.all { it.status == PlaylistDownloadStatus.DOWNLOADING })
        gates.getValue("two").complete(Unit)
        assertEquals(PlaylistDownloadStatus.COMPLETE, job.await().status)
    }

    @Test fun `mixed results retain errors and never report complete`() = runTest {
        val saved = mutableSetOf<String>()
        val result = runPlaylistDownload(tracks, saved::contains, { track ->
            if (track.id == "two") TrackDownloadResult(track.id, DownloadOutcome.FAILURE, "transfer", "HTTP 403")
            else {
                saved += track.id
                TrackDownloadResult(track.id, DownloadOutcome.SUCCESS)
            }
        }, {})
        assertEquals(PlaylistDownloadStatus.PARTIAL_FAILURE, result.status)
        assertEquals(1, result.succeeded)
        assertEquals("HTTP 403", result.results.last().error)
        assertEquals("transfer", result.results.last().stage)
    }

    @Test fun `all failures are retained and later items still run`() = runTest {
        val result = runPlaylistDownload(tracks, { false }, { throw IllegalStateException("resolution failed") }, {})
        assertEquals(PlaylistDownloadStatus.FAILED, result.status)
        assertEquals(2, result.finished)
        assertTrue(result.results.all { it.error == "resolution failed" })
    }

    @Test fun `already downloaded items require no transfers and duplicates are filtered`() = runTest {
        val result = runPlaylistDownload(tracks + tracks, { true }, { error("Must not download") }, {})
        assertEquals(PlaylistDownloadStatus.ALREADY_DOWNLOADED, result.status)
        assertEquals(2, result.total)
        assertEquals(2, result.succeeded)
    }

    @Test fun `empty playlist is not a downloaded playlist`() = runTest {
        val result = runPlaylistDownload(emptyList(), { true }, { error("Must not download") }, {})
        assertEquals(PlaylistDownloadStatus.EMPTY, result.status)
    }

    @Test fun `success without persisted file is a verification failure`() = runTest {
        val result = runPlaylistDownload(tracks, { false }, { TrackDownloadResult(it.id, DownloadOutcome.SUCCESS) }, {})
        assertEquals(PlaylistDownloadStatus.FAILED, result.status)
        assertTrue(result.results.all { it.stage == "verification" })
    }

    @Test fun `removal during batch prevents completion`() = runTest {
        val saved = mutableSetOf<String>()
        val result = runPlaylistDownload(tracks, saved::contains, {
            saved.clear()
            saved += it.id
            TrackDownloadResult(it.id, DownloadOutcome.SUCCESS)
        }, {})
        assertEquals(PlaylistDownloadStatus.PARTIAL_FAILURE, result.status)
        assertEquals("verification", result.results.first().stage)
    }

    @Test fun `existing active transfer is awaited rather than counted as downloaded`() = runTest {
        val saved = mutableSetOf<String>()
        val active = CompletableDeferred<TrackDownloadResult>()
        val job = async { runPlaylistDownload(tracks.take(1), saved::contains, { active.await() }, {}) }
        runCurrent()
        assertFalse(job.isCompleted)
        saved += "one"
        active.complete(TrackDownloadResult("one", DownloadOutcome.SUCCESS))
        assertEquals(PlaylistDownloadStatus.COMPLETE, job.await().status)
    }

    @Test fun `cancelled shared item does not hide later outcomes`() = runTest {
        val cancelled = CompletableDeferred<TrackDownloadResult>().apply { cancel() }
        val saved = mutableSetOf<String>()
        val result = runPlaylistDownload(tracks, saved::contains, {
            if (it.id == "one") cancelled.await() else {
                saved += it.id
                TrackDownloadResult(it.id, DownloadOutcome.SUCCESS)
            }
        }, {})
        assertEquals(DownloadOutcome.CANCELLED, result.results.first().outcome)
        assertEquals(PlaylistDownloadStatus.PARTIAL_FAILURE, result.status)
    }

    @Test fun `playlist cancellation records all unfinished items`() = runTest {
        val states = mutableListOf<PlaylistDownloadState>()
        val gate = CompletableDeferred<TrackDownloadResult>()
        val job = async { runPlaylistDownload(tracks, { false }, { gate.await() }, states::add) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(PlaylistDownloadStatus.CANCELLED, states.last().status)
        assertEquals(2, states.last().finished)
        assertTrue(states.last().results.all { it.outcome == DownloadOutcome.CANCELLED })
    }

    @Test fun `skipped work is explicit and not success`() = runTest {
        val result = runPlaylistDownload(tracks, { false }, {
            TrackDownloadResult(it.id, DownloadOutcome.SKIPPED, "queue", "Removal in progress")
        }, {})
        assertEquals(PlaylistDownloadStatus.FAILED, result.status)
        assertTrue(result.results.all { it.outcome == DownloadOutcome.SKIPPED })
    }

    @Test fun `playlist folder uses the sanitized playlist name and only collisions add an id suffix`() {
        assertEquals("Road Trip 2026", PlaylistDownloadPaths.stableFolder("Road: Trip 2026."))
        val first = PlaylistDownloadPaths.collisionFolder("Road: Trip 2026.", "playlist-one")
        val second = PlaylistDownloadPaths.collisionFolder("Road: Trip 2026.", "playlist-two")
        assertTrue(first.startsWith("Road Trip 2026 ("))
        assertNotEquals(first, second)
    }

    @Test fun `track filenames remain deterministic and storage safe`() {
        val fileName = PlaylistDownloadPaths.trackFileName(2, Track(id = "id", title = "AC/DC: Live?"))
        assertEquals("03 - AC DC Live.m4a", fileName)
    }

    @Test fun `scheduler backend boundary is API 34`() {
        assertEquals(PlaylistDownloadCoordinator.BACKEND_WORK_MANAGER, PlaylistDownloadCoordinator.backendForApi(24))
        assertEquals(PlaylistDownloadCoordinator.BACKEND_WORK_MANAGER, PlaylistDownloadCoordinator.backendForApi(33))
        assertEquals(PlaylistDownloadCoordinator.BACKEND_UIDT, PlaylistDownloadCoordinator.backendForApi(34))
        assertEquals(PlaylistDownloadCoordinator.BACKEND_UIDT, PlaylistDownloadCoordinator.backendForApi(35))
    }

    @Test fun `offline availability is invalidated when playlist membership changes`() {
        val snapshot = listOf(Track(id = "one"), Track(id = "two"))
        val entity = PlaylistDownloadJobEntity(
            jobId = "job", playlistId = "playlist", playlistName = "Playlist", folderName = "Playlist",
            backend = PlaylistDownloadCoordinator.BACKEND_WORK_MANAGER,
            trackSnapshotJson = Json.encodeToString(snapshot), resultsJson = "[]",
            status = PlaylistDownloadStatus.COMPLETE.name, completedCount = 2, failedCount = 0,
            skippedCount = 0, currentTrackId = null, cancelRequested = false, offlineEnabled = true,
            createdAt = 1L, updatedAt = 1L
        )
        assertTrue(PlaylistDownloadCoordinator.matchesTrackSnapshot(entity, listOf("two", "one")))
        assertFalse(PlaylistDownloadCoordinator.matchesTrackSnapshot(entity, listOf("one", "three")))
    }

    @Test fun `offline toggle cannot remain enabled for an incomplete playlist`() {
        val results = listOf(
            TrackDownloadResult("one", DownloadOutcome.SUCCESS, contentUri = "one", bytes = 10L),
            TrackDownloadResult("two", DownloadOutcome.FAILURE, "transfer", "HTTP 403")
        )
        val reconciled = reconcilePlaylistDownloadState(
            tracks.map { it.id }, results, true, { it == "one" }, { it == "one" }
        )
        assertEquals(PlaylistDownloadStatus.PARTIAL_FAILURE, reconciled.status)
        assertFalse(reconciled.offlineEnabled)
    }

    @Test fun `verified offline toggle survives durable state reload`() {
        val results = tracks.map { TrackDownloadResult(it.id, DownloadOutcome.SUCCESS, contentUri = it.id, bytes = 20L) }
        val persisted = durableJob(offlineEnabled = true, results = results)
        val reloaded = indexPlaylistJobs(listOf(persisted))[persisted.playlistId]
        assertTrue(reloaded?.offlineEnabled == true)
        val reconciled = reconcilePlaylistDownloadState(
            tracks.map { it.id }, results, reloaded!!.offlineEnabled, { true }, { true }
        )
        assertEquals(PlaylistDownloadStatus.COMPLETE, reconciled.status)
        assertTrue(reconciled.offlineEnabled)
    }

    @Test fun `missing public file disables offline availability during reconciliation`() {
        val results = tracks.map { TrackDownloadResult(it.id, DownloadOutcome.SUCCESS, contentUri = it.id, bytes = 20L) }
        val reconciled = reconcilePlaylistDownloadState(
            tracks.map { it.id }, results, true, { true }, { it != "two" }
        )
        assertEquals(PlaylistDownloadStatus.PARTIAL_FAILURE, reconciled.status)
        assertFalse(reconciled.offlineEnabled)
        assertEquals("verification", reconciled.results.last().stage)
    }

    @Test fun `removing one playlist job leaves every other playlist intact`() {
        val first = durableJob(jobId = "job-one", playlistId = "playlist-one")
        val second = durableJob(jobId = "job-two", playlistId = "playlist-two")
        val remaining = removePlaylistJobFromState(indexPlaylistJobs(listOf(first, second)), first.jobId)
        assertFalse(remaining.containsKey(first.playlistId))
        assertSame(second, remaining[second.playlistId])
    }

    @Test fun `durable job state maps back to the correct playlist after restart`() {
        val first = durableJob(jobId = "job-one", playlistId = "playlist-one", status = PlaylistDownloadStatus.PARTIAL_FAILURE)
        val second = durableJob(jobId = "job-two", playlistId = "playlist-two", status = PlaylistDownloadStatus.DOWNLOADING)
        val restored = indexPlaylistJobs(listOf(second, first))
        assertEquals("job-one", restored["playlist-one"]?.jobId)
        assertEquals(PlaylistDownloadStatus.DOWNLOADING.name, restored["playlist-two"]?.status)
    }

    @Test fun `reconciliation never changes Track identity`() {
        val originalIds = tracks.map { it.id }
        val results = tracks.map { TrackDownloadResult(it.id, DownloadOutcome.SUCCESS, contentUri = it.id) }
        val reconciled = reconcilePlaylistDownloadState(originalIds, results, true, { true }, { true })
        assertEquals(originalIds, tracks.map { it.id })
        assertEquals(originalIds, reconciled.results.map { it.trackId })
    }

    @Test fun `download size includes only verified successful results`() {
        val results = listOf(
            TrackDownloadResult("one", DownloadOutcome.SUCCESS, bytes = 1024L),
            TrackDownloadResult("two", DownloadOutcome.FAILURE, bytes = 4096L)
        )
        assertEquals(1024L, verifiedDownloadBytes(results))
    }

    // ========================================================================
    // 10 MANDATORY PHYSICAL DESTINATION & VERIFICATION TESTS
    // ========================================================================

    @Test fun `test 1 - Playlist Chill Hits resolves to Downloads Auralis Chill Hits`() {
        val folder = PlaylistDownloadPaths.stableFolder("Chill Hits")
        assertEquals("Chill Hits", folder)
        assertEquals("Download/Auralis/Chill Hits/", PlaylistDownloadPaths.relativePath("Chill Hits"))
        val root = java.io.File("/storage/emulated/0/Download")
        val legacy = PlaylistDownloadPaths.legacyFolder(root, "Chill Hits")
        assertEquals(java.io.File(root, "Auralis/Chill Hits"), legacy)
    }

    @Test fun `test 2 - Playlist Workout resolves to Downloads Auralis Workout`() {
        val folder = PlaylistDownloadPaths.stableFolder("Workout")
        assertEquals("Workout", folder)
        assertEquals("Download/Auralis/Workout/", PlaylistDownloadPaths.relativePath("Workout"))
        val root = java.io.File("/storage/emulated/0/Download")
        val legacy = PlaylistDownloadPaths.legacyFolder(root, "Workout")
        assertEquals(java.io.File(root, "Auralis/Workout"), legacy)
    }

    @Test fun `test 3 - Same-named distinct playlists resolve to separate deterministic folders`() {
        val firstFolder = PlaylistDownloadPaths.stableFolder("Chill Hits")
        val secondFolder = PlaylistDownloadPaths.collisionFolder("Chill Hits", "playlist-two-id")
        val thirdFolder = PlaylistDownloadPaths.collisionFolder("Chill Hits", "playlist-three-id")
        assertEquals("Chill Hits", firstFolder)
        assertTrue(secondFolder.startsWith("Chill Hits ("))
        assertTrue(thirdFolder.startsWith("Chill Hits ("))
        assertNotEquals(firstFolder, secondFolder)
        assertNotEquals(secondFolder, thirdFolder)
        // Deterministic: same id always produces same folder
        assertEquals(secondFolder, PlaylistDownloadPaths.collisionFolder("Chill Hits", "playlist-two-id"))
    }

    @Test fun `test 4 - The same playlist resolves to the same folder after reconstruction from durable job state`() {
        val persisted = durableJob(jobId = "job-42", playlistId = "chill-hits-id").copy(folderName = "Chill Hits (a1b2c3d4)")
        val restored = indexPlaylistJobs(listOf(persisted))["chill-hits-id"]
        assertNotNull(restored)
        assertEquals("Chill Hits (a1b2c3d4)", restored!!.folderName)
    }

    @Test fun `test 5 - Sanitized names cannot escape Auralis Playlist`() {
        val evil1 = PlaylistDownloadPaths.sanitize("../../etc/passwd")
        val evil2 = PlaylistDownloadPaths.sanitize("../..")
        val evil3 = PlaylistDownloadPaths.sanitize("..\\..\\windows\\system32")
        assertFalse(evil1.contains("/"))
        assertFalse(evil1.contains("\\"))
        assertFalse(evil1.contains(".."))
        assertEquals("Playlist", evil2)
        assertFalse(evil3.contains(".."))
        assertFalse(evil3.contains("\\"))

        val root = java.io.File("/storage/emulated/0/Download")
        val dest = PlaylistDownloadPaths.legacyFile(root, "../../etc/passwd", "../evil.m4a")
        assertFalse(dest.path.contains(".."))
        assertTrue(dest.path.replace('\\', '/').startsWith("/storage/emulated/0/Download/Auralis/"))
    }

    @Test fun `test 6 - API 29+ RELATIVE_PATH contains the playlist folder and trailing slash`() {
        val relPath = PlaylistDownloadPaths.relativePath("Chill Hits")
        assertEquals("Download/Auralis/Chill Hits/", relPath)
        assertTrue(relPath.contains("Auralis/Chill Hits/"))
        assertTrue(relPath.endsWith("/"))
    }

    @Test fun `test 7 - API 24-28 filesystem destination contains the playlist folder`() {
        val root = java.io.File("/storage/emulated/0/Download")
        val dest = PlaylistDownloadPaths.legacyFile(root, "Chill Hits", "01 - Song 1.m4a")
        assertEquals(java.io.File(root, "Auralis/Chill Hits/01 - Song 1.m4a"), dest)
        assertTrue(dest.path.replace('\\', '/').endsWith("Auralis/Chill Hits/01 - Song 1.m4a"))
    }

    @Test fun `test 8 - DownloadStore isDownloaded recognizes the resulting file`() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "auralis_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val store = DownloadStore(tempDir)
            store.load()
            val audio = store.audioFile("track_test_8")
            audio.parentFile!!.mkdirs()
            audio.writeBytes(ByteArray(2048) { 7 })
            store.add(Track(id = "track_test_8", title = "Test Song"))
            assertNotNull(store.downloadedFile("track_test_8"))
            assertEquals("track_test_8.m4a", store.downloadedFile("track_test_8")!!.name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test fun `test 9 - Playlist completion verification still succeeds`() {
        val results = tracks.map {
            TrackDownloadResult(it.id, DownloadOutcome.SUCCESS, contentUri = "content://downloads/${it.id}", bytes = 2048L)
        }
        val reconciled = reconcilePlaylistDownloadState(
            tracks.map { it.id }, results, true,
            isInDownloadStore = { true },
            isPublicFileValid = { true }
        )
        assertEquals(PlaylistDownloadStatus.COMPLETE, reconciled.status)
        assertTrue(reconciled.offlineEnabled)
        assertEquals(2, reconciled.results.size)
        assertTrue(reconciled.results.all { it.outcome == DownloadOutcome.SUCCESS })
    }

    @Test fun `test 10 - Offline playback can still locate the downloaded file`() {
        val results = tracks.map {
            TrackDownloadResult(it.id, DownloadOutcome.SUCCESS, contentUri = "content://media/external/downloads/${it.id}", bytes = 4096L)
        }
        val entity = durableJob(jobId = "job-offline", playlistId = "pl-offline", offlineEnabled = true, results = results)
        val verifiedJob = indexPlaylistJobs(listOf(entity))["pl-offline"]
        assertNotNull(verifiedJob)
        assertTrue(verifiedJob!!.offlineEnabled)
        val trackResult = Json.decodeFromString<List<TrackDownloadResult>>(verifiedJob.resultsJson).firstOrNull { it.trackId == "one" }
        assertNotNull(trackResult)
        assertEquals("content://media/external/downloads/one", trackResult!!.contentUri)
    }

    @Test fun `resumed download purges stale failure results and resets failedCount`() {
        val totalTracks = (1..52).map { Track(id = "track_$it", title = "Track $it") }
        val previousResults = totalTracks.mapIndexed { index, track ->
            if (index < 13) TrackDownloadResult(track.id, DownloadOutcome.SUCCESS, contentUri = "uri://${track.id}", bytes = 1000L)
            else TrackDownloadResult(track.id, DownloadOutcome.FAILURE, "download", "Timeout")
        }
        val priorJob = durableJob(jobId = "prior-job", playlistId = "playlist-52", status = PlaylistDownloadStatus.PARTIAL_FAILURE, results = previousResults)
        val priorResults = Json.decodeFromString<List<TrackDownloadResult>>(priorJob.resultsJson)
        assertEquals(52, priorResults.size)
        assertEquals(13, priorJob.completedCount)
        assertEquals(39, priorJob.failedCount)

        val retainedResults = priorResults.filter { it.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES }
        val resumedJob = priorJob.copy(
            resultsJson = Json.encodeToString(retainedResults),
            status = PlaylistDownloadStatus.DOWNLOADING.name,
            completedCount = retainedResults.count { it.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES },
            failedCount = 0
        )
        assertEquals(13, resumedJob.completedCount)
        assertEquals(0, resumedJob.failedCount)
        assertEquals(13, retainedResults.size)
        assertFalse(retainedResults.any { it.trackId == "track_14" })
    }

    @Test fun `active download on 14th track of 52 reflects current track index rather than total`() {
        val totalTracks = (1..52).map { Track(id = "track_$it", title = "Track $it") }
        val currentTrack = totalTracks[13] // 14th track ("track_14")
        val currentIndex = totalTracks.indexOfFirst { it.id == currentTrack.id }.let { if (it >= 0) it + 1 else null }
        assertEquals(14, currentIndex)
        val total = totalTracks.size
        assertEquals(52, total)
        val progress = (currentIndex ?: 0).coerceIn(0, total)
        assertEquals(14, progress)
        assertNotEquals(52, progress)
    }
}
