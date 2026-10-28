package com.auralis.music.windows.profile

import com.auralis.music.data.playlistsync.*
import com.auralis.music.desktop.library.*
import com.auralis.music.domain.model.Track
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.windows.together.FirestoreRest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import java.io.File
import kotlin.test.*

class AccountServicePlaylistRestoreTest {
    @Test fun staleCloudBackupCannotReplaceAServicesPendingLocalAddition() = runBlocking<Unit> {
        val dir=java.nio.file.Files.createTempDirectory("cloud-service-protection").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val id="imported:youtube_music:playlist"
        val cloud=JSONObject().put("playlists",JSONArray().put(JSONObject().put("id",id).put("title","Old cloud name")
            .put("tracks",JSONArray().put(JSONObject().put("id","a").put("title","Original").put("artist","Artist")))))
        val api=FirestoreRest("test","key") { url,_,_,_ -> when {
            url.contains("signInWithPassword") -> JSONObject().put("idToken","id").put("refreshToken","refresh").put("localId","uid").put("expiresIn","3600")
            url.contains("accounts:lookup") -> JSONObject("""{"users":[{"email":"test@example.com","displayName":"Listener"}]}""")
            url.contains("/listening?") -> JSONObject().put("documents",JSONArray())
            url.endsWith("/users/uid") -> JSONObject().put("fields",FirestoreRest.encodeFields(cloud))
            else -> error("Unexpected test request")
        } }
        try {
            val library=DesktopLibraryRepository(File(dir,"library.json"))
            library.restorePlaylist(id,"Local name")
            library.replacePlaylistTracks(id,listOf(Track("a","Original","Artist"),Track("b","Pending addition","Artist")))
            PlaylistSyncStore(PropertiesFileKeyValueStore(File(dir,"playlist-sync.properties"),synchronous=true)).put(
                PlaylistSyncLink(id,SyncService.YOUTUBE,"playlist","account",SyncSnapshot("Old cloud name","",listOf("a")),emptyMap(),interrupted=true))
            val account=DesktopAccount(File(dir,"session.bin"),scope,api)
            account.signIn("test@example.com","test-password",false)
            withTimeout(5000) {account.state.first{it.profile!=null}}
            account.pullCloudBackup(library,DesktopListeningStats(File(dir,"listens.json")))
            assertEquals("Local name",library.getPlaylist(id).first()!!.title)
            assertEquals(listOf("a","b"),library.getPlaylist(id).first()!!.tracks.map{it.id})
        } finally {scope.cancel();dir.deleteRecursively()}
    }
}
