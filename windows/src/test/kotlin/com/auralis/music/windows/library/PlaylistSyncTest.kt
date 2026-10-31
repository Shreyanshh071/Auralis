package com.auralis.music.windows.library

import com.auralis.music.data.playlistsync.*
import com.auralis.music.desktop.library.DesktopLibraryRepository
import com.auralis.music.domain.model.*
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.*

class PlaylistSyncTest {
    private class Memory : KeyValueStore {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String, default: String) = data[key] ?: default
        override fun putStrings(values: Map<String, String>) { data.putAll(values) }
        override fun clear() { data.clear() }
    }
    private fun snap(vararg ids: String, title: String = "Mix") = SyncSnapshot(title, "", ids.toList())
    private fun track(id: String) = Track(id, "Song $id", "Artist", duration = 180)
    private inner class Remote(@Volatile var current: SyncSnapshot, override val service: SyncService = SyncService.SPOTIFY) : PlaylistSyncRemote {
        var creations = 0
        var beforeWrite: (suspend () -> Unit)? = null
        var beforeRead: (suspend () -> Unit)? = null
        val reads = java.util.concurrent.CopyOnWriteArrayList<String>()
        var user = "account"; var revision = 0; var writes = 0; var fail = false; var editable = true; var failWrite = false
        override suspend fun account() = user
        override suspend fun read(id: String): RemoteSyncPlaylist {
            reads += id
            beforeRead?.invoke()
            if (fail) error("Offline")
            return RemoteSyncPlaylist(id, user, current, current.ids.map(::track), "$revision", editable)
        }
        override suspend fun create(title: String, description: String): String { creations++; return "created" }
        override suspend fun identify(track: Track) = track.id.takeUnless { it == "unmatched" }
        override suspend fun write(before: RemoteSyncPlaylist, target: SyncSnapshot) {
            if (before.revision != "$revision") throw PlaylistSyncStalePreviewException("Remote changed before upload")
            writes++; if (fail || failWrite) error("Offline during upload")
            beforeWrite?.invoke()
            current = target; revision++
        }
    }
    @Test fun creatingWithEitherServiceCreatesItsRemotePlaylistAndStartsAutomaticSync() = runBlocking<Unit> {
        for (service in SyncService.entries) {
            val dir = Files.createTempDirectory("create-sync").toFile()
            try {
                val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
                val local = library.createPlaylist("Mix")
                library.addTrackToPlaylist(local.id, track("a"))
                val remote = Remote(snap(), service)
                val store = PlaylistSyncStore(Memory())
                val engine = PlaylistSyncEngine(library, store, mapOf(service to remote))
                engine.connectCreated(local.id, service)
                assertEquals(1, remote.creations)
                assertEquals(listOf("a"), remote.current.ids)
                assertEquals(service to "created", engine.source(local.id))
                library.addTrackToPlaylist(local.id, track("b")); engine.sync(local.id)
                assertEquals(listOf("a", "b"), remote.current.ids)
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun retryingFailedCreationReusesTheRemotePlaylistInsteadOfCreatingDuplicates() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("retry-create-sync").toFile()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
            val local = library.createPlaylist("Mix"); library.addTrackToPlaylist(local.id, track("a"))
            val remote = Remote(snap()); remote.failWrite = true
            val store = PlaylistSyncStore(Memory())
            val engine = PlaylistSyncEngine(library, store, mapOf(SyncService.SPOTIFY to remote))
            assertFailsWith<IllegalStateException> { engine.connectCreated(local.id, SyncService.SPOTIFY) }
            assertEquals(1, remote.creations); assertTrue(store.links.value.single().interrupted)
            remote.failWrite = false
            engine.connectCreated(local.id, SyncService.SPOTIFY)
            assertEquals(1, remote.creations); assertEquals(listOf("a"), remote.current.ids)
            assertFalse(store.links.value.single().interrupted)
        } finally { dir.deleteRecursively() }
    }
    @Test fun importToggleUsesItsSavedServiceAndPlaylistWithoutCreatingOrChoosingAnotherCopy() = runBlocking<Unit> {
        for ((source, service) in listOf("spotify" to SyncService.SPOTIFY, "youtube_music" to SyncService.YOUTUBE)) {
            val dir = Files.createTempDirectory("sync-source-toggle").toFile()
            try {
                val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
                val local = library.restorePlaylist("imported:$source:original", "Mix")
                library.replacePlaylistTracks(local.id, listOf(track("a"), track("local-added")))
                val remote = Remote(snap("a", "remote-added"), service)
                val wrongService = Remote(snap("wrong"), if (service == SyncService.SPOTIFY) SyncService.YOUTUBE else SyncService.SPOTIFY)
                val engine = PlaylistSyncEngine(library, PlaylistSyncStore(Memory()), mapOf(service to remote, wrongService.service to wrongService))
                engine.enable(local.id)
                assertEquals(service to "original", engine.source(local.id))
                assertEquals(setOf("a", "local-added", "remote-added"), remote.current.ids.toSet())
                assertEquals(remote.current.ids, library.getPlaylist(local.id).first()!!.tracks.map { it.id })
                assertTrue(remote.reads.all { it == "original" })
                assertEquals(0, remote.creations); assertEquals(0, wrongService.creations)
                assertTrue(wrongService.reads.isEmpty())
                engine.disable(local.id)
                assertTrue(engine.store.links.value.isEmpty())
                assertEquals(service to "original", engine.source(local.id), "Turning off must retain the original import source")
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun additionsTravelBothWaysAutomaticallyAndTurningOffStopsTheWorker() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sync-toggle-worker").toFile()
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Default)
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
            val local = library.restorePlaylist("imported:youtube_music:original", "Mix")
            library.replacePlaylistTracks(local.id, listOf(track("a")))
            val remote = Remote(snap("a"), SyncService.YOUTUBE)
            val store = PlaylistSyncStore(Memory())
            val engine = PlaylistSyncEngine(library, store, mapOf(SyncService.YOUTUBE to remote), pollIntervalMs = 40, localDebounceMs = 20)
            engine.enable(local.id); engine.start(scope)
            library.addTrackToPlaylist(local.id, track("b"))
            withTimeout(5000) { store.links.first { it.singleOrNull()?.base?.ids == listOf("a", "b") } }
            assertEquals(listOf("a", "b"), remote.current.ids)
            remote.current = snap("a", "b", "c"); remote.revision++
            withTimeout(5000) { library.getPlaylist(local.id).first { it?.tracks?.map { t -> t.id } == listOf("a", "b", "c") } }
            withTimeout(5000) { store.links.first { it.singleOrNull()?.base?.ids == listOf("a", "b", "c") } }
            engine.disable(local.id)
            library.addTrackToPlaylist(local.id, track("d")); delay(150)
            assertEquals(listOf("a", "b", "c"), remote.current.ids)
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }

    @Test fun toggleDoesNotWriteToUnownedPlaylistsOrGuessAnUnknownSource() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sync-toggle-invalid").toFile()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
            val local = library.restorePlaylist("imported:spotify:original", "Mix")
            val remote = Remote(snap()); remote.editable = false
            val engine = PlaylistSyncEngine(library, PlaylistSyncStore(Memory()), mapOf(SyncService.SPOTIFY to remote))
            assertFailsWith<IllegalStateException> { engine.enable(local.id) }
            val unlinked = library.createPlaylist("Local")
            assertFailsWith<IllegalStateException> { engine.enable(unlinked.id) }
            assertTrue(engine.store.links.value.isEmpty()); assertEquals(0, remote.writes); assertEquals(0, remote.creations)
        } finally { dir.deleteRecursively() }
    }

    @Test fun anOldManuallyChosenLinkCannotOverrideTheOriginalImportSource() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sync-original-source").toFile()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
            val local = library.restorePlaylist("imported:youtube_music:original", "Mix")
            val wrong = Remote(snap("a"))
            val store = PlaylistSyncStore(Memory())
            store.put(PlaylistSyncLink(local.id, SyncService.SPOTIFY, "wrong", "account", snap("a"), emptyMap()))
            val engine = PlaylistSyncEngine(library, store, mapOf(SyncService.SPOTIFY to wrong))
            assertEquals(SyncService.YOUTUBE to "original", engine.source(local.id))
            engine.sync(local.id)
            assertTrue(wrong.reads.isEmpty()); assertEquals(0, wrong.writes)
            assertFailsWith<IllegalStateException> { engine.enable(local.id) }
            assertTrue(wrong.reads.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun threeWayMergePreservesIndependentAddsDeletesAndSingleSideReordering() {
        assertEquals(snap("a", "c", "d"), PlaylistSyncMerge.merge(snap("a", "b"), snap("a", "c"), snap("a", "b", "d")))
        assertEquals(snap("b", "a", "c"), PlaylistSyncMerge.merge(snap("a", "b"), snap("b", "a"), snap("a", "b", "c")))
        assertEquals(snap("a", "a", "b"), PlaylistSyncMerge.merge(snap("a", "b"), snap("a", "a", "b"), snap("a", "b")))
        assertNull(PlaylistSyncMerge.merge(snap("a", "b", "c"), snap("b", "a", "c"), snap("a", "c", "b")))
        assertNull(PlaylistSyncMerge.merge(snap("a"), snap("a", title = "Local"), snap("a", title = "Remote")))
    }
    @Test fun existingPlaylistCanEnableThenPushAndPullWithoutLosingDuplicates() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("playlist-sync").toFile()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
            val local = library.createPlaylist("Mix")
            library.replacePlaylistTracks(local.id, listOf(track("a"), track("a")))
            val remote = Remote(snap("a", "a"))
            val memory = Memory(); val store = PlaylistSyncStore(memory)
            val engine = PlaylistSyncEngine(library, store, mapOf(SyncService.SPOTIFY to remote))
            val preview = engine.preview(library.getPlaylist(local.id).first()!!, SyncService.SPOTIFY, "remote")
            assertEquals(0, remote.writes, "Preview must perform no writes")
            engine.apply(preview, SyncChoice.MERGE)
            assertEquals(1, store.links.value.size)
            assertEquals(2, library.getPlaylist(local.id).first()!!.tracks.size)
            library.addTrackToPlaylist(local.id, track("b"))
            engine.sync(local.id)
            assertEquals(snap("a", "a", "b"), remote.current)
            remote.current = snap("b", "a", "a", title = "Renamed remotely"); remote.revision++
            engine.sync(local.id)
            val pulled = library.getPlaylist(local.id).first()!!
            assertEquals("Renamed remotely", pulled.title)
            assertEquals(listOf("b", "a", "a"), pulled.tracks.map { it.id })
            assertEquals(store.links.value, PlaylistSyncStore(memory).links.value, "Link and baseline must survive restart")
            engine.disable(local.id)
            library.removeTrackFromPlaylist(local.id, "b"); engine.sync(local.id)
            assertEquals(listOf("b", "a", "a"), remote.current.ids, "Turning off sync must leave the remote copy intact")
        } finally { dir.deleteRecursively() }
    }
    @Test fun conflictsAndAccountChangesDoNotWriteAndStalePreviewCannotOverwriteNewEdits() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("playlist-sync-conflicts").toFile()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
            val local = library.createPlaylist("Mix"); library.replacePlaylistTracks(local.id, listOf(track("a")))
            val remote = Remote(snap("a")); val store = PlaylistSyncStore(Memory())
            val engine = PlaylistSyncEngine(library, store, mapOf(SyncService.SPOTIFY to remote))
            engine.apply(engine.preview(library.getPlaylist(local.id).first()!!, SyncService.SPOTIFY, "remote"), SyncChoice.MERGE)
            library.updatePlaylist(local.id, "Local")
            remote.current = snap("a", title = "Remote"); remote.revision++
            engine.sync(local.id)
            assertEquals(0, remote.writes)
            assertTrue(engine.status.value[local.id]!!.contains("Both copies changed"))
            val p = engine.preview(library.getPlaylist(local.id).first()!!, SyncService.SPOTIFY, "remote")
            remote.current = snap("a", "b", title = "Remote"); remote.revision++
            assertFailsWith<IllegalStateException> { engine.apply(p, SyncChoice.AURALIS) }
            assertEquals(0, remote.writes)
            assertFalse(store.links.value.single().interrupted, "A rejected stale preview must retain the successful baseline")
            remote.user = "different-account"
            assertFailsWith<IllegalStateException> { engine.preview(library.getPlaylist(local.id).first()!!, SyncService.SPOTIFY, "remote") }
        } finally { dir.deleteRecursively() }
    }
    @Test fun offlineAndUnmatchedSongsLeaveBothCopiesUntouched() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("playlist-sync-offline").toFile()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json")); val local = library.createPlaylist("Mix")
            library.replacePlaylistTracks(local.id, listOf(track("unmatched")))
            val remote = Remote(snap("a")); val store = PlaylistSyncStore(Memory())
            val engine = PlaylistSyncEngine(library, store, mapOf(SyncService.SPOTIFY to remote))
            assertFailsWith<IllegalStateException> { engine.preview(library.getPlaylist(local.id).first()!!, SyncService.SPOTIFY, "remote") }
            assertTrue(store.links.value.isEmpty()); assertEquals(0, remote.writes)
            remote.fail = true
            assertFailsWith<IllegalStateException> { engine.preview(library.getPlaylist(local.id).first()!!, SyncService.SPOTIFY, "remote") }
            assertEquals(listOf("unmatched"), library.getPlaylist(local.id).first()!!.tracks.map { it.id })
        } finally { dir.deleteRecursively() }
    }
    @Test fun interruptedUploadIsJournaledAndSafelyRetriesWhenTheRemoteIsStillUnchanged() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("playlist-sync-journal").toFile()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json")); val local = library.createPlaylist("Mix")
            library.replacePlaylistTracks(local.id, listOf(track("a"), track("b")))
            val remote = Remote(snap("a")); remote.failWrite = true
            val memory = Memory(); val store = PlaylistSyncStore(memory)
            val engine = PlaylistSyncEngine(library, store, mapOf(SyncService.SPOTIFY to remote))
            val p = engine.preview(library.getPlaylist(local.id).first()!!, SyncService.SPOTIFY, "remote")
            assertFailsWith<IllegalStateException> { engine.apply(p, SyncChoice.AURALIS) }
            assertTrue(PlaylistSyncStore(memory).links.value.single().interrupted)
            remote.failWrite = false
            engine.sync(local.id)
            assertEquals(2, remote.writes)
            assertEquals(listOf("a", "b"), remote.current.ids)
            assertFalse(store.links.value.single().interrupted)
            assertEquals(listOf("a", "b"), library.getPlaylist(local.id).first()!!.tracks.map { it.id })
        } finally { dir.deleteRecursively() }
    }
    @Test fun anInterruptedUploadDoesNotOverwriteUnexpectedRemoteChanges() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sync-conflicted-retry").toFile()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local = library.createPlaylist("Mix"); library.replacePlaylistTracks(local.id,listOf(track("a"),track("b")))
            val remote = Remote(snap("a")); remote.failWrite = true
            val store = PlaylistSyncStore(Memory()); val engine = PlaylistSyncEngine(library,store,mapOf(SyncService.SPOTIFY to remote))
            assertFailsWith<IllegalStateException> { engine.apply(engine.preview(library.getPlaylist(local.id).first()!!,SyncService.SPOTIFY,"remote"),SyncChoice.AURALIS) }
            assertNotNull(store.links.value.single().pendingTarget)
            assertNotNull(store.links.value.single().failure)
            remote.failWrite=false; remote.current=snap("a","outside-edit");remote.revision++
            engine.sync(local.id)
            assertEquals(1,remote.writes);assertEquals(listOf("a","outside-edit"),remote.current.ids)
            assertTrue(store.links.value.single().interrupted)
        } finally {dir.deleteRecursively()}
    }
    @Test fun artworkRefreshDuringUploadKeepsTheNewCoverAndDoesNotFalselyInterruptSync() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sync-artwork-refresh").toFile()
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.createPlaylist("Mix");library.replacePlaylistTracks(local.id,listOf(track("a"),track("b")))
            val remote=Remote(snap("a")); val store=PlaylistSyncStore(Memory())
            remote.beforeWrite={
                library.updatePlaylist(local.id,"Mix",coverUrl="new-playlist-cover")
                library.replacePlaylistTracks(local.id,listOf(track("a").copy(thumbnail="fresh-art"),track("b")))
            }
            val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.SPOTIFY to remote))
            engine.apply(engine.preview(library.getPlaylist(local.id).first()!!,SyncService.SPOTIFY,"remote"),SyncChoice.AURALIS)
            assertFalse(store.links.value.single().interrupted)
            assertEquals("fresh-art",library.getPlaylist(local.id).first()!!.tracks.first().thumbnail)
            assertEquals("new-playlist-cover",library.getPlaylist(local.id).first()!!.coverUrl)
        } finally {dir.deleteRecursively()}
    }
    @Test fun legacyInterruptedAdditionCanResumeWithoutAnUnsafeOverwrite() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("legacy-sync-retry").toFile()
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.createPlaylist("Mix");library.replacePlaylistTracks(local.id,listOf(track("a"),track("b")))
            val store=PlaylistSyncStore(Memory())
            store.put(PlaylistSyncLink(local.id,SyncService.SPOTIFY,"remote","account",snap("a"),mapOf("a" to "a","b" to "b"),interrupted=true))
            val remote=Remote(snap("a"));val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.SPOTIFY to remote))
            engine.sync(local.id)
            assertEquals(listOf("a","b"),remote.current.ids)
            assertFalse(store.links.value.single().interrupted)
        } finally {dir.deleteRecursively()}
    }
    @Test fun aSecondAdditionDuringUploadIsQueuedWithoutWaitingForTheMinutePoll() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sync-inflight-addition").toFile()
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default)
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.createPlaylist("Mix");library.replacePlaylistTracks(local.id,listOf(track("a")))
            val remote=Remote(snap("a"));val store=PlaylistSyncStore(Memory())
            val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.SPOTIFY to remote),pollIntervalMs=60000,localDebounceMs=20)
            engine.apply(engine.preview(library.getPlaylist(local.id).first()!!,SyncService.SPOTIFY,"remote"),SyncChoice.MERGE);engine.start(scope)
            withTimeout(3000){engine.status.first{it[local.id]=="Up to date"}}
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            remote.beforeWrite={if(!entered.isCompleted){entered.complete(Unit);release.await()}}
            library.addTrackToPlaylist(local.id,track("b"));withTimeout(3000){entered.await()}
            library.addTrackToPlaylist(local.id,track("c"));delay(120);release.complete(Unit)
            withTimeout(3000){store.links.first{it.single().base.ids==listOf("a","b","c")}}
            assertEquals(listOf("a","b","c"),remote.current.ids)
        } finally {job.cancelAndJoin();dir.deleteRecursively()}
    }
    @Test fun constantArtworkUpdatesCannotStarveASongAddition() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sync-artwork-debounce").toFile()
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default)
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.createPlaylist("Mix");library.replacePlaylistTracks(local.id,listOf(track("a")))
            val remote=Remote(snap("a"));val store=PlaylistSyncStore(Memory())
            val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.SPOTIFY to remote),pollIntervalMs=60000,localDebounceMs=60)
            engine.apply(engine.preview(library.getPlaylist(local.id).first()!!,SyncService.SPOTIFY,"remote"),SyncChoice.MERGE);engine.start(scope)
            withTimeout(3000){engine.status.first{it[local.id]=="Up to date"}};delay(150)
            val artwork=scope.launch{repeat(100){library.updatePlaylist(local.id,"Mix",coverUrl="image-$it");delay(40)}}
            library.addTrackToPlaylist(local.id,track("b"))
            withTimeout(2000){store.links.first{it.single().base.ids==listOf("a","b")}}
            assertTrue(artwork.isActive,"The song must upload while artwork is still refreshing")
        } finally {job.cancelAndJoin();dir.deleteRecursively()}
    }
    @Test fun transientFailureRetriesSoonWithoutAnotherEditOrMinutePoll() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sync-fast-retry").toFile()
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default)
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.createPlaylist("Mix");library.replacePlaylistTracks(local.id,listOf(track("a")))
            val remote=Remote(snap("a"));val store=PlaylistSyncStore(Memory())
            val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.SPOTIFY to remote),pollIntervalMs=60000,localDebounceMs=20,retryDelayMs=50)
            engine.apply(engine.preview(library.getPlaylist(local.id).first()!!,SyncService.SPOTIFY,"remote"),SyncChoice.MERGE);library.addTrackToPlaylist(local.id,track("b"));remote.fail=true;engine.start(scope)
            withTimeout(3000){engine.status.first{it[local.id]=="Offline"}};remote.fail=false
            withTimeout(3000){store.links.first{it.single().base.ids==listOf("a","b")}}
            assertEquals(listOf("a","b"),remote.current.ids)
        } finally {job.cancelAndJoin();dir.deleteRecursively()}
    }
    @Test fun legacyPhoneUploadRecoversWhenTheFirstAdditionAlreadyReachedYouTube() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sync-legacy-partial-add").toFile()
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.createPlaylist("Mix");library.replacePlaylistTracks(local.id,listOf(track("a"),track("a"),track("b"),track("c")))
            val store=PlaylistSyncStore(Memory())
            store.put(PlaylistSyncLink(local.id,SyncService.YOUTUBE,"remote","account",snap("a","a"),mapOf("a" to "a","b" to "b","c" to "c"),interrupted=true))
            val remote=Remote(snap("a","a","b"),SyncService.YOUTUBE)
            PlaylistSyncEngine(library,store,mapOf(SyncService.YOUTUBE to remote)).sync(local.id)
            assertEquals(listOf("a","a","b","c"),remote.current.ids)
            assertFalse(store.links.value.single().interrupted)
        } finally {dir.deleteRecursively()}
    }
    @Test fun legacyRecoveryStillPausesForARemoteDeletion() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sync-legacy-removal").toFile()
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.createPlaylist("Mix");library.replacePlaylistTracks(local.id,listOf(track("a"),track("b"),track("c")))
            val store=PlaylistSyncStore(Memory())
            store.put(PlaylistSyncLink(local.id,SyncService.YOUTUBE,"remote","account",snap("a","b"),emptyMap(),interrupted=true))
            val remote=Remote(snap("a"),SyncService.YOUTUBE)
            PlaylistSyncEngine(library,store,mapOf(SyncService.YOUTUBE to remote)).sync(local.id)
            assertEquals(0,remote.writes);assertTrue(store.links.value.single().interrupted)
        } finally {dir.deleteRecursively()}
    }
    @Test fun defaultWorkerStartsLocalUploadsWithoutTheOldOneAndAHalfSecondWait() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sync-fast-local-default").toFile()
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default)
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.restorePlaylist("imported:youtube_music:original","Mix")
            library.replacePlaylistTracks(local.id,listOf(track("a")))
            val remote=Remote(snap("a"),SyncService.YOUTUBE);val store=PlaylistSyncStore(Memory())
            val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.YOUTUBE to remote))
            engine.enable(local.id);engine.start(scope)
            withTimeout(3000){engine.status.first{it[local.id]=="Up to date"}};delay(300)
            library.addTrackToPlaylist(local.id,track("b"))
            withTimeout(1000){store.links.first{it.single().base.ids==listOf("a","b")}}
            assertEquals(listOf("a","b"),remote.current.ids)
        } finally {job.cancelAndJoin();dir.deleteRecursively()}
    }
    @Test fun defaultWorkerPullsYouTubeAdditionsWithinAShortPollingWindow() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sync-fast-remote-default").toFile()
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default)
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.restorePlaylist("imported:youtube_music:original","Mix")
            library.replacePlaylistTracks(local.id,listOf(track("a")))
            val remote=Remote(snap("a"),SyncService.YOUTUBE);val store=PlaylistSyncStore(Memory())
            val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.YOUTUBE to remote))
            engine.enable(local.id);engine.start(scope)
            withTimeout(3000){engine.status.first{it[local.id]=="Up to date"}};delay(300)
            remote.current=snap("a","b");remote.revision++
            withTimeout(8500){library.getPlaylist(local.id).first{it?.tracks?.map{t->t.id}==listOf("a","b")}}
            assertEquals(listOf("a","b"),library.getPlaylist(local.id).first()!!.tracks.map{it.id})
        } finally {job.cancelAndJoin();dir.deleteRecursively()}
    }
    @Test fun pullsReuseTheValidatedResponseInsteadOfDownloadingThePlaylistAThirdTime() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sync-pull-request-budget").toFile()
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.restorePlaylist("imported:youtube_music:original","Mix")
            library.replacePlaylistTracks(local.id,listOf(track("a")))
            val remote=Remote(snap("a"),SyncService.YOUTUBE);val store=PlaylistSyncStore(Memory())
            val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.YOUTUBE to remote))
            engine.enable(local.id);remote.reads.clear();remote.current=snap("a","b");remote.revision++
            engine.sync(local.id)
            assertEquals(2,remote.reads.size,"Read the proposal and validate it once; reuse that metadata for the local copy")
            assertEquals(0,remote.writes)
            assertEquals(listOf("a","b"),library.getPlaylist(local.id).first()!!.tracks.map{it.id})
        } finally {dir.deleteRecursively()}
    }
    @Test fun aRemoteEditRejectedBeforeUploadRetriesFromTheSuccessfulBaseline() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sync-stale-preflight-retry").toFile()
        try {
            val library=DesktopLibraryRepository(java.io.File(dir,"library.json"))
            val local=library.restorePlaylist("imported:youtube_music:original","Mix")
            library.replacePlaylistTracks(local.id,listOf(track("a")))
            val remote=Remote(snap("a"),SyncService.YOUTUBE);val store=PlaylistSyncStore(Memory())
            val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.YOUTUBE to remote))
            engine.enable(local.id);library.addTrackToPlaylist(local.id,track("b"))
            val preview=engine.preview(library.getPlaylist(local.id).first()!!,SyncService.YOUTUBE,"original")
            remote.current=snap("a","c");remote.revision++
            assertFailsWith<PlaylistSyncStalePreviewException>{engine.apply(preview,SyncChoice.MERGE)}
            assertFalse(store.links.value.single().interrupted)
            assertEquals(listOf("a"),store.links.value.single().base.ids)
            assertEquals(0,remote.writes)
            engine.sync(local.id)
            assertEquals(setOf("a","b","c"),remote.current.ids.toSet())
            assertFalse(store.links.value.single().interrupted)
        } finally {dir.deleteRecursively()}
    }

    @Test fun slowBackgroundChecksKeepTheLastCompletedStatusAndTurningOffCannotRelink() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sync-slow-status").toFile()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
            val local = library.restorePlaylist("imported:youtube_music:original", "Mix")
            library.replacePlaylistTracks(local.id, listOf(track("a")))
            val remote = Remote(snap("a"), SyncService.YOUTUBE)
            val store = PlaylistSyncStore(Memory())
            var engine = PlaylistSyncEngine(library, store, mapOf(SyncService.YOUTUBE to remote))
            engine.enable(local.id)
            // Reopening the app must use the durable completed result during its first read.
            engine = PlaylistSyncEngine(library, store, mapOf(SyncService.YOUTUBE to remote))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            remote.beforeRead = { entered.complete(Unit); release.await() }
            val checking = async { engine.sync(local.id) }
            withTimeout(3000) { entered.await() }
            assertEquals("Up to date", engine.status.value[local.id])
            // A remote change makes a stale read attempt apply unless the disable epoch is checked.
            remote.current = snap("a", "b"); remote.revision++
            engine.disable(local.id); release.complete(Unit); checking.await()
            assertTrue(store.links.value.isEmpty())
            assertEquals("Sync off. Both playlists are kept.", engine.status.value[local.id])
            assertEquals(listOf("a"), library.getPlaylist(local.id).first()!!.tracks.map { it.id })
        } finally { dir.deleteRecursively() }
    }

    @Test fun stalledCheckTimesOutAndTheWorkerRetriesWithoutLosingItsBaseline() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sync-check-timeout").toFile()
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Default)
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
            val local = library.restorePlaylist("imported:youtube_music:original", "Mix")
            library.replacePlaylistTracks(local.id, listOf(track("a")))
            val remote = Remote(snap("a"), SyncService.YOUTUBE)
            val store = PlaylistSyncStore(Memory())
            val engine = PlaylistSyncEngine(library, store, mapOf(SyncService.YOUTUBE to remote),
                pollIntervalMs = 60_000, retryDelayMs = 100, checkTimeoutMs = 200)
            engine.enable(local.id)
            remote.beforeRead = { awaitCancellation() }
            engine.start(scope)
            withTimeout(3000) { engine.status.first { it[local.id]?.contains("timed out") == true } }
            assertFalse(store.links.value.single().interrupted)
            assertEquals(listOf("a"), store.links.value.single().base.ids)
            remote.beforeRead = null
            withTimeout(3000) { engine.status.first { it[local.id] == "Up to date" } }
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }

    @Test fun cancelledCheckClearsProgressAndTheSameEngineCanRestartItsWorker() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sync-worker-restart").toFile()
        val firstJob = SupervisorJob(); val nextJob = SupervisorJob()
        try {
            val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
            val local = library.restorePlaylist("imported:youtube_music:original", "Mix")
            library.replacePlaylistTracks(local.id, listOf(track("a")))
            val remote = Remote(snap("a"), SyncService.YOUTUBE)
            val store = PlaylistSyncStore(Memory())
            val engine = PlaylistSyncEngine(library, store, mapOf(SyncService.YOUTUBE to remote))
            engine.enable(local.id)
            val entered = CompletableDeferred<Unit>()
            remote.beforeRead = { entered.complete(Unit); awaitCancellation() }
            engine.start(CoroutineScope(firstJob + Dispatchers.Default))
            withTimeout(3000) { entered.await() }
            firstJob.cancelAndJoin()
            assertEquals("Sync pending. Will check when Auralis resumes.", engine.status.value[local.id])
            remote.beforeRead = null
            engine.start(CoroutineScope(nextJob + Dispatchers.Default))
            withTimeout(3000) { engine.status.first { it[local.id] == "Up to date" } }
        } finally { firstJob.cancelAndJoin(); nextJob.cancelAndJoin(); dir.deleteRecursively() }
    }
}
