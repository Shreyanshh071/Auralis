package com.auralis.music.windows.dev

import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.data.playlistsync.*
import com.auralis.music.desktop.library.DesktopLibraryRepository
import com.auralis.music.util.*
import com.auralis.music.windows.profile.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import org.json.JSONObject
import com.auralis.music.domain.model.Track

/** Read-only by default. The separate repair task can restore one snapshot-verified addition. */
fun main(args: Array<String>) = runBlocking {
    val repair=args.firstOrNull()=="--restore-addition"
    val requested=if(repair) args.getOrNull(1) else args.firstOrNull()
    if(repair) require(!requested.isNullOrBlank() && !args.getOrNull(2).isNullOrBlank()) { "A playlist ID and its recovery snapshot are required." }
    val data = desktopAppDataDir()
    YouTubeSession.init(ProtectedYouTubeStore(File(data,"youtube-session.properties")))
    val headers=YouTubeSession.authHeaders(YouTubeMusicClient.ORIGIN)
    check(YouTubeSession.isSignedIn) { "YouTube Music is not connected." }
    val client=YouTubeMusicClient(headers["Cookie"].orEmpty(),headers["X-Goog-AuthUser"] ?: "0")
    client.resolveScope()
    val store=PlaylistSyncStore(PropertiesFileKeyValueStore(File(data,"playlist-sync.properties"), synchronous=true))
    val library=DesktopLibraryRepository(File(data,"library.json"))
    val links=store.links.value.filter { it.service==SyncService.YOUTUBE && (requested==null || it.remoteId==requested) }
    for(link in links) {
        val remote=YouTubePlaylistSync(identity={link.account},post={endpoint,body ->
            check(endpoint=="browse" || repair && endpoint=="browse/edit_playlist") { "Unexpected playlist operation." }
            if(endpoint!="browse") {
                val actions=body.getJSONArray("actions")
                for(i in 0 until actions.length()) check(actions.getJSONObject(i).getString("action") in
                    setOf("ACTION_ADD_VIDEO","ACTION_MOVE_VIDEO_BEFORE")) { "Recovery may only add the confirmed missing song and place it in order." }
            }
            client.post(endpoint,body).also { result ->
                if(endpoint!="browse") println("REMOTE_EDIT status=${result.optString("status")} added=${YouTubePlaylistSync.nodes(result,"playlistEditVideoAddedResultData").size}")
            }
        })
        try {
            val online=remote.read(link.remoteId)
            val local=library.getPlaylist(link.localId).first() ?: continue
            println("PLAYLIST local=${local.title} localCount=${local.tracks.size} remote=${online.snapshot.title} remoteCount=${online.snapshot.ids.size} editable=${online.editable} interrupted=${link.interrupted} baseline=${link.base.ids.size}")
            val engine=PlaylistSyncEngine(library,store,mapOf(SyncService.YOUTUBE to remote))
            val preview=engine.preview(local,SyncService.YOUTUBE,link.remoteId)
            val l=preview.localSnapshot.ids.groupingBy{it}.eachCount()
            val r=online.snapshot.ids.groupingBy{it}.eachCount()
            (l.keys+r.keys).filter { l[it]!=r[it] }.forEach { id ->
                println("DIFFERENCE id=$id local=${l[id] ?: 0} remote=${r[id] ?: 0} title="+(local.tracks.find { it.id==id }?.title ?: online.tracks.find { it.id==id }?.title))
            }
            println("SAME_CONTENT ${preview.localSnapshot==online.snapshot} INITIAL_MERGE ${preview.merged?.ids?.size}")
            if(repair) {
                val backup=JSONObject(File(args[2]).readText()).getJSONArray("playlists")
                val saved=(0 until backup.length()).map {backup.getJSONObject(it)}.single {it.getString("id")==link.localId}
                val rows=saved.getJSONArray("tracks")
                val savedIds=(0 until rows.length()).map{rows.getJSONObject(it).getString("id")}
                if (online.snapshot.ids != savedIds) {
                val identity=client.identity()
                val accounts=YouTubeAccountsStore(ProtectedYouTubeStore(File(data,"youtube-accounts.properties")))
                val selected=accounts.summaries().singleOrNull { it.identity.key==identity.key }
                check(selected?.id==link.account) { "The selected YouTube account is different from this playlist's linked account." }
                }
                val missing=savedIds.filter { it !in link.base.ids }
                check(missing.size==1 && savedIds.filter { it!=missing.single() }==link.base.ids) {
                    "The snapshot does not describe exactly one added song. No repair was applied."
                }
                check(online.editable && (online.snapshot.ids==link.base.ids || online.snapshot.ids==savedIds)) {
                    "The online playlist changed. No automatic repair was applied."
                }
                val current=library.getPlaylist(link.localId).first()!!
                check(current.tracks.map{it.id}==link.base.ids || current.tracks.map{it.id}==savedIds) {
                    "The local playlist changed. No automatic repair was applied."
                }
                if(current.tracks.none{it.id==missing.single()}) {
                    val index=savedIds.indexOf(missing.single()); val row=rows.getJSONObject(index)
                    val song=Track(row.getString("id"),row.getString("title"),row.getString("artist"),
                        thumbnail=row.optString("thumbnail"),album=row.optString("album").takeIf{it.isNotBlank() && it!="null"},duration=row.optLong("duration"))
                    val tracks=current.tracks.toMutableList();tracks.add(index,song)
                    library.replacePlaylistTracks(current.id,tracks)
                    println("RESTORED_ADDITION ${song.title}")
                }
                val p=engine.preview(library.getPlaylist(link.localId).first()!!,link.service,link.remoteId)
                check(p.merged?.ids==savedIds) { "The proposed repair changed more than the confirmed addition." }
                engine.apply(p,SyncChoice.MERGE)
                val verified=remote.read(link.remoteId)
                check(verified.snapshot.ids==savedIds && library.getPlaylist(link.localId).first()!!.tracks.map{it.id}==savedIds)
                println("REPAIR_VERIFIED local=${savedIds.size} remote=${verified.snapshot.ids.size} interrupted=${store.links.value.single{it.localId==link.localId}.interrupted}")
            }
        } catch(e:Exception) { println("PLAYLIST_ERROR ${e.javaClass.simpleName}: ${e.message}"); if(repair) throw e }
    }
}
