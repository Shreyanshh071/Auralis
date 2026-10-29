package com.auralis.music.data.connect

import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource

// Auralis Connect: one listening session per account, one device making sound, every other
// signed-in device showing it and controlling it. Everything lives under the account's own
// Firestore document (users/{uid}/connect/..., users/{uid}/connectDevices/...), so the security
// rules keyed on request.auth.uid keep accounts apart. This file is kept identical in
// shared/ (Android) and windows/core/ (Windows); ConnectSourceParityTest checks that.

enum class ConnectPlatform { ANDROID, WINDOWS, WEB, OTHER }

enum class ConnectDeviceType { PHONE, TABLET, COMPUTER, SPEAKER, BROWSER }

/** One registered client of the account. [id] is a random install id, never the display name. */
data class ConnectDevice(
    val id: String,
    /** The name the platform reports (Android device name, Windows computer name); may be blank. */
    val name: String = "",
    val nickname: String? = null,
    val platform: ConnectPlatform = ConnectPlatform.OTHER,
    val type: ConnectDeviceType = ConnectDeviceType.PHONE,
    val appVersion: String = "",
    /** False when the client has no working audio player (e.g. Windows without libVLC). */
    val canPlay: Boolean = true,
    /** False after the client closed cleanly; it stays listed as recently seen. */
    val online: Boolean = true,
    /** Server time of the last heartbeat. */
    val lastSeenMs: Long = 0L,
    val createdMs: Long = 0L
)

/** A song as the session stores it: enough to show it and to play it on any client. */
data class ConnectTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val thumbnail: String = "",
    val durationSec: Long = 0L,
    val local: Boolean = false,
    val lyricsProvider: String? = null,
    val lyricsSpotifyId: String? = null,
    val lyricsTitle: String? = null, val lyricsArtist: String? = null, val lyricsDurationMs: Long? = null
) {
    fun toTrack(): Track = Track(
        id = id, title = title, artist = artist, album = album, albumId = albumId, duration = durationSec,
        thumbnail = thumbnail, source = if (local) TrackSource.LOCAL else TrackSource.YOUTUBE
    )

    companion object {
        fun from(track: Track) = ConnectTrack(
            id = track.id, title = track.title.take(MAX_TEXT), artist = track.artist.take(MAX_TEXT),
            album = track.album?.take(MAX_TEXT), albumId = track.albumId?.take(MAX_TEXT),
            thumbnail = track.thumbnail.take(MAX_URL), durationSec = track.duration,
            local = track.source == TrackSource.LOCAL
        )
    }
}

/** Where the queue came from (an album or playlist), when the client knows. */
data class ConnectContext(val type: String = "", val id: String = "", val title: String = "")

enum class TransferStatus { REQUESTED, READY, COMMITTED, FAILED;
    val active: Boolean get() = this == REQUESTED || this == READY
}

/**
 * A handoff of the output from [from] to [to]. [epoch] is the ownership epoch the target will hold
 * once it is committed. The session's owner (or the target, when the owner is gone) commits it.
 */
data class ConnectTransfer(
    val id: String,
    val from: String,
    val to: String,
    val toName: String,
    val toType: ConnectDeviceType,
    val epoch: Long,
    val status: TransferStatus,
    /** Server time of the last status change. */
    val atMs: Long,
    /** The client that asked for it. */
    val by: String,
    val reason: String = ""
)

enum class ConnectCommandType { PLAY, PAUSE, SEEK, NEXT, PREVIOUS, PLAY_INDEX, LOAD, SHUFFLE, REPEAT, ADD, PLAY_NEXT, MOVE, REMOVE }

/**
 * A control sent to the active output. Indices refer to the session's queue window at [queueRev];
 * [trackId] lets the output find the song again when the queue has changed since.
 */
data class ConnectCommand(
    val id: String,
    val type: ConnectCommandType,
    val from: String,
    /** Sender's estimate of server time when sent; commands apply in this order. */
    val atMs: Long,
    val positionMs: Long = 0L,
    val index: Int = -1,
    val toIndex: Int = -1,
    val trackId: String = "",
    val flag: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val queueRev: Long = 0L,
    val tracks: List<ConnectTrack> = emptyList(),
    val context: ConnectContext? = null
)

/**
 * The account's single authoritative session. [version] grows by exactly one per write (the
 * security rules enforce it), so a stale writer can never overwrite newer state. [ownerId] is the
 * one output that may make sound; [epoch] changes with every change of owner and fences writes
 * from a former owner; the owner keeps [leaseUntilMs] in the future while it is online.
 */
data class ConnectSession(
    val version: Long = 0L,
    val ownerId: String = "",
    val ownerName: String = "",
    val ownerType: ConnectDeviceType = ConnectDeviceType.PHONE,
    val epoch: Long = 0L,
    val leaseUntilMs: Long = 0L,
    val track: ConnectTrack? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    /** Server time at which [positionMs] was true. */
    val positionAtMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueRev: Long = 0L,
    /** The current song's index in the queue window. */
    val queueIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val context: ConnectContext? = null,
    val transfer: ConnectTransfer? = null,
    val commands: List<ConnectCommand> = emptyList(),
    val updatedBy: String = "",
    /** Server time of the last write (set by the server). */
    val updatedAtMs: Long = 0L
) {
    fun ownerActive(serverNowMs: Long): Boolean = ownerId.isNotEmpty() && leaseUntilMs > serverNowMs

    /** Where the song is now, from the last timestamped position (frozen when the owner is gone). */
    fun positionAt(serverNowMs: Long): Long {
        if (!playing || buffering || track == null) return positionMs.coerceAtLeast(0L)
        val until = if (leaseUntilMs > 0L) minOf(serverNowMs, leaseUntilMs) else serverNowMs
        val position = positionMs + (until - positionAtMs).coerceAtLeast(0L)
        return if (durationMs > 0L) position.coerceIn(0L, durationMs) else position.coerceAtLeast(0L)
    }
}

/** The session's queue: a window of at most [MAX_QUEUE] songs; [offset] is its start in the owner's queue. */
data class ConnectQueue(val rev: Long = 0L, val tracks: List<ConnectTrack> = emptyList(), val offset: Int = 0)

/** What a client knows of the account's session; [queue] matches [session] (same rev) or is null. */
data class ConnectSnapshot(val session: ConnectSession? = null, val queue: ConnectQueue? = null)

/** What one write puts on the server: the session, and the queue when it changed. */
data class ConnectWrite(val session: ConnectSession, val queue: ConnectQueue? = null)

/** Placeholder the platform replaces with the server's own time (FieldValue / REQUEST_TIME). */
object ConnectServerTime

const val MAX_QUEUE = 100
const val MAX_COMMANDS = 20
private const val MAX_TEXT = 300
private const val MAX_URL = 1000

/**
 * Encoding to Firestore field maps. Numbers come back as Long or Double, timestamps must be
 * converted to epoch milliseconds by the platform before [decodeSession] / [decodeDevice].
 */
object ConnectCodec {
    fun encodeSession(s: ConnectSession): Map<String, Any?> = mapOf(
        "version" to s.version,
        "ownerId" to s.ownerId,
        "ownerName" to s.ownerName.take(80),
        "ownerType" to s.ownerType.name,
        "epoch" to s.epoch,
        "leaseUntil" to s.leaseUntilMs,
        "track" to s.track?.let(::encodeTrack),
        "playing" to s.playing,
        "buffering" to s.buffering,
        "positionMs" to s.positionMs,
        "positionAt" to s.positionAtMs,
        "durationMs" to s.durationMs,
        "queueRev" to s.queueRev,
        "queueIndex" to s.queueIndex.toLong(),
        "shuffle" to s.shuffle,
        "repeat" to s.repeat.name,
        "context" to s.context?.let { mapOf("type" to it.type.take(40), "id" to it.id.take(200), "title" to it.title.take(MAX_TEXT)) },
        "transfer" to s.transfer?.let(::encodeTransfer),
        "commands" to s.commands.takeLast(MAX_COMMANDS).associate { it.id to encodeCommand(it) },
        "updatedBy" to s.updatedBy,
        "updatedAt" to ConnectServerTime
    )

    fun decodeSession(m: Map<String, Any?>): ConnectSession = ConnectSession(
        version = m.long("version"),
        ownerId = m.str("ownerId"),
        ownerName = m.str("ownerName"),
        ownerType = enumOr(m.str("ownerType"), ConnectDeviceType.PHONE),
        epoch = m.long("epoch"),
        leaseUntilMs = m.long("leaseUntil"),
        track = m.map("track")?.let(::decodeTrack),
        playing = m["playing"] == true,
        buffering = m["buffering"] == true,
        positionMs = m.long("positionMs"),
        positionAtMs = m.long("positionAt"),
        durationMs = m.long("durationMs"),
        queueRev = m.long("queueRev"),
        queueIndex = m.long("queueIndex", -1L).toInt(),
        shuffle = m["shuffle"] == true,
        repeat = enumOr(m.str("repeat"), RepeatMode.OFF),
        context = m.map("context")?.let { ConnectContext(it.str("type"), it.str("id"), it.str("title")) },
        transfer = m.map("transfer")?.let(::decodeTransfer),
        commands = m.map("commands")?.entries?.mapNotNull { (id, v) ->
            @Suppress("UNCHECKED_CAST") (v as? Map<String, Any?>)?.let { decodeCommand(id, it) }
        }.orEmpty().sortedWith(compareBy({ it.atMs }, { it.id })),
        updatedBy = m.str("updatedBy"),
        updatedAtMs = m.long("updatedAt")
    )

    fun encodeQueue(q: ConnectQueue): Map<String, Any?> = mapOf(
        "rev" to q.rev, "offset" to q.offset.toLong(), "tracks" to q.tracks.take(MAX_QUEUE).map(::encodeTrack)
    )

    fun decodeQueue(m: Map<String, Any?>): ConnectQueue = ConnectQueue(
        rev = m.long("rev"), offset = m.long("offset").toInt(),
        tracks = (m["tracks"] as? List<*>).orEmpty().mapNotNull { item ->
            @Suppress("UNCHECKED_CAST") (item as? Map<String, Any?>)?.let(::decodeTrack)
        }
    )

    fun encodeDevice(d: ConnectDevice): Map<String, Any?> = mapOf(
        "id" to d.id,
        "name" to d.name.take(80),
        "nickname" to d.nickname?.take(80),
        "platform" to d.platform.name,
        "type" to d.type.name,
        "appVersion" to d.appVersion.take(40),
        "canPlay" to d.canPlay,
        "online" to d.online,
        "createdAt" to d.createdMs,
        "seenAt" to ConnectServerTime
    )

    fun decodeDevice(m: Map<String, Any?>): ConnectDevice = ConnectDevice(
        id = m.str("id"),
        name = m.str("name"),
        nickname = m.str("nickname").takeIf { it.isNotBlank() },
        platform = enumOr(m.str("platform"), ConnectPlatform.OTHER),
        type = enumOr(m.str("type"), ConnectDeviceType.PHONE),
        appVersion = m.str("appVersion"),
        canPlay = m["canPlay"] != false,
        online = m["online"] != false,
        lastSeenMs = m.long("seenAt"),
        createdMs = m.long("createdAt")
    )

    private fun encodeTrack(t: ConnectTrack): Map<String, Any?> = mapOf(
        "id" to t.id, "title" to t.title, "artist" to t.artist, "album" to t.album, "albumId" to t.albumId,
        "thumbnail" to t.thumbnail, "duration" to t.durationSec, "local" to t.local,
        "lyricsProvider" to t.lyricsProvider, "lyricsSpotifyId" to t.lyricsSpotifyId,
        "lyricsTitle" to t.lyricsTitle?.take(MAX_TEXT), "lyricsArtist" to t.lyricsArtist?.take(MAX_TEXT),
        "lyricsDurationMs" to t.lyricsDurationMs
    )

    private fun decodeTrack(m: Map<String, Any?>): ConnectTrack? {
        val id = m.str("id").takeIf { it.isNotBlank() } ?: return null
        return ConnectTrack(
            id = id, title = m.str("title"), artist = m.str("artist"),
            album = m.str("album").takeIf { it.isNotBlank() }, albumId = m.str("albumId").takeIf { it.isNotBlank() },
            thumbnail = m.str("thumbnail"), durationSec = m.long("duration"), local = m["local"] == true,
            lyricsProvider = m.str("lyricsProvider").takeIf { name -> com.auralis.music.domain.model.LyricsProvider.entries.any { it.name == name } },
            lyricsSpotifyId = m.str("lyricsSpotifyId").takeIf { Regex("[A-Za-z0-9]{22}").matches(it) },
            lyricsTitle = m.str("lyricsTitle").take(MAX_TEXT).takeIf { it.isNotBlank() },
            lyricsArtist = m.str("lyricsArtist").take(MAX_TEXT).takeIf { it.isNotBlank() },
            lyricsDurationMs = m.long("lyricsDurationMs").takeIf { it in 1..86_400_000L }
        )
    }

    private fun encodeTransfer(t: ConnectTransfer): Map<String, Any?> = mapOf(
        "id" to t.id, "from" to t.from, "to" to t.to, "toName" to t.toName.take(80), "toType" to t.toType.name,
        "epoch" to t.epoch, "status" to t.status.name.lowercase(), "at" to t.atMs, "by" to t.by, "reason" to t.reason.take(80)
    )

    private fun decodeTransfer(m: Map<String, Any?>): ConnectTransfer? {
        val id = m.str("id").takeIf { it.isNotBlank() } ?: return null
        return ConnectTransfer(
            id = id, from = m.str("from"), to = m.str("to"), toName = m.str("toName"),
            toType = enumOr(m.str("toType"), ConnectDeviceType.PHONE), epoch = m.long("epoch"),
            status = enumOr(m.str("status").uppercase(), TransferStatus.FAILED), atMs = m.long("at"),
            by = m.str("by"), reason = m.str("reason")
        )
    }

    private fun encodeCommand(c: ConnectCommand): Map<String, Any?> = buildMap {
        put("type", c.type.name)
        put("from", c.from)
        put("at", c.atMs)
        if (c.positionMs != 0L) put("positionMs", c.positionMs)
        if (c.index >= 0) put("index", c.index.toLong())
        if (c.toIndex >= 0) put("toIndex", c.toIndex.toLong())
        if (c.trackId.isNotEmpty()) put("trackId", c.trackId)
        if (c.flag) put("flag", true)
        if (c.type == ConnectCommandType.REPEAT) put("repeat", c.repeat.name)
        if (c.queueRev != 0L) put("queueRev", c.queueRev)
        if (c.tracks.isNotEmpty()) put("tracks", c.tracks.take(MAX_QUEUE).map(::encodeTrack))
        c.context?.let { put("context", mapOf("type" to it.type.take(40), "id" to it.id.take(200), "title" to it.title.take(MAX_TEXT))) }
    }

    private fun decodeCommand(id: String, m: Map<String, Any?>): ConnectCommand? {
        val type = runCatching { ConnectCommandType.valueOf(m.str("type")) }.getOrNull() ?: return null
        return ConnectCommand(
            id = id, type = type, from = m.str("from"), atMs = m.long("at"), positionMs = m.long("positionMs"),
            index = m.long("index", -1L).toInt(), toIndex = m.long("toIndex", -1L).toInt(), trackId = m.str("trackId"),
            flag = m["flag"] == true, repeat = enumOr(m.str("repeat"), RepeatMode.OFF), queueRev = m.long("queueRev"),
            tracks = (m["tracks"] as? List<*>).orEmpty().mapNotNull { item ->
                @Suppress("UNCHECKED_CAST") (item as? Map<String, Any?>)?.let(::decodeTrack)
            },
            context = m.map("context")?.let { ConnectContext(it.str("type"), it.str("id"), it.str("title")) }
        )
    }

    private fun Map<String, Any?>.str(key: String): String = (this[key] as? String).orEmpty()
    private fun Map<String, Any?>.long(key: String, default: Long = 0L): Long = (this[key] as? Number)?.toLong() ?: default
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
