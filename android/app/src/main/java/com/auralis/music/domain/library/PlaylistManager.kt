package com.auralis.music.domain.library

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encapsulates full backup schema for library export/import.
 */
data class BackupData(
    val playlists: List<Playlist> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val exportTimestamp: Long = System.currentTimeMillis()
)

/**
 * Domain engine for playlist operations, track reordering, and lossless JSON backups.
 */
object PlaylistManager {

    /**
     * Reorders tracks in a playlist by moving a track from [fromIndex] to [toIndex].
     */
    fun reorderTracks(tracks: List<Track>, fromIndex: Int, toIndex: Int): List<Track> {
        if (fromIndex !in tracks.indices || toIndex !in tracks.indices || fromIndex == toIndex) {
            return tracks
        }
        val mutable = tracks.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable.toList()
    }

    /**
     * Serializes complete playlist collections and favorited tracks into a formatted JSON string.
     */
    fun exportBackupJson(playlists: List<Playlist>, favorites: List<Track>): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        val playlistsArray = JSONArray()
        for (pl in playlists) {
            val plObj = JSONObject().apply {
                put("id", pl.id)
                put("title", pl.title)
                put("description", pl.description ?: "")
                put("coverUrl", pl.coverUrl ?: "")

                val tracksArr = JSONArray()
                for (t in pl.tracks) {
                    val tObj = JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("album", t.album ?: "")
                        put("thumbnail", t.thumbnail)
                        put("duration", t.duration)
                        put("source", t.source.name)
                    }
                    tracksArr.put(tObj)
                }
                put("tracks", tracksArr)
            }
            playlistsArray.put(plObj)
        }
        root.put("playlists", playlistsArray)

        val favsArr = JSONArray()
        for (f in favorites) {
            val fObj = JSONObject().apply {
                put("id", f.id)
                put("title", f.title)
                put("artist", f.artist)
                put("album", f.album ?: "")
                put("thumbnail", f.thumbnail)
                put("duration", f.duration)
                put("source", f.source.name)
            }
            favsArr.put(fObj)
        }
        root.put("favorites", favsArr)

        return root.toString(2)
    }

    /**
     * Parses a JSON backup string into a typed [BackupData] model.
     */
    fun parseBackupJson(jsonString: String): BackupData {
        if (jsonString.isBlank()) return BackupData()

        return try {
            val root = JSONObject(jsonString)
            val parsedPlaylists = mutableListOf<Playlist>()
            val parsedFavorites = mutableListOf<Track>()

            val playlistsArr = root.optJSONArray("playlists")
            if (playlistsArr != null) {
                for (i in 0 until playlistsArr.length()) {
                    val plObj = playlistsArr.optJSONObject(i) ?: continue
                    val id = plObj.optString("id", System.currentTimeMillis().toString())
                    val title = plObj.optString("title", "Imported Playlist")
                    val desc = plObj.optString("description")
                    val cover = plObj.optString("coverUrl")

                    val trackList = mutableListOf<Track>()
                    val tracksArr = plObj.optJSONArray("tracks")
                    if (tracksArr != null) {
                        for (j in 0 until tracksArr.length()) {
                            val tObj = tracksArr.optJSONObject(j) ?: continue
                            trackList.add(parseTrackObj(tObj))
                        }
                    }

                    parsedPlaylists.add(
                        Playlist(
                            id = id,
                            title = title,
                            description = if (desc.isNotBlank()) desc else null,
                            coverUrl = if (cover.isNotBlank()) cover else null,
                            tracks = trackList
                        )
                    )
                }
            }

            val favsArr = root.optJSONArray("favorites")
            if (favsArr != null) {
                for (i in 0 until favsArr.length()) {
                    val fObj = favsArr.optJSONObject(i) ?: continue
                    parsedFavorites.add(parseTrackObj(fObj))
                }
            }

            BackupData(
                playlists = parsedPlaylists,
                favorites = parsedFavorites,
                exportTimestamp = root.optLong("timestamp", System.currentTimeMillis())
            )
        } catch (_: Exception) {
            BackupData()
        }
    }

    private fun parseTrackObj(tObj: JSONObject): Track {
        val srcStr = tObj.optString("source", "YOUTUBE")
        val source = try { TrackSource.valueOf(srcStr) } catch (_: Exception) { TrackSource.YOUTUBE }

        return Track(
            id = tObj.optString("id"),
            title = tObj.optString("title"),
            artist = tObj.optString("artist"),
            album = tObj.optString("album").ifBlank { null },
            thumbnail = tObj.optString("thumbnail"),
            duration = tObj.optLong("duration", 210L),
            source = source
        )
    }
}
