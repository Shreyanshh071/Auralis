package com.auralis.music.data.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.auralis.music.data.datastore.DownloadMetadataDataStore
import com.auralis.music.data.local.AuralisDatabase
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LyricsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Puts the chosen metadata in the public copy of a download. The private file Auralis plays is only read. */
object DownloadMetadataExport {
    private const val TAG = "AuralisDownload"
    private const val LYRICS_TIMEOUT_MS = 20_000L
    private const val MAX_COVER_BYTES = 1_500_000
    private const val MAX_COVER_SIDE = 1200
    private const val COVER_QUALITY = 90

    @Volatile private var attachedLyrics: LyricsRepository? = null

    /** A playlist download can outlive the activity that owns the app's repository. */
    fun attachLyrics(repository: LyricsRepository) {
        attachedLyrics = repository
    }

    /** Publish only after mandatory metadata is written; never silently export an untagged copy. */
    suspend fun publish(
        context: Context,
        files: PlaylistDownloadFiles,
        source: File,
        folderName: String,
        fileName: String,
        track: Track,
        choice: DownloadMetadataChoice
    ): Pair<String, Long> {
        withContext(Dispatchers.IO) {
            AuralisDownloadManager.recordDownloadLyricsChoice(track.id, choice.lyrics)
            AuralisDownloadManager.recordDownloadCoverChoice(track.id, choice.coverArt)
        }
        val container = checkNotNull(AudioTagger.detect(source)) { "Cannot embed song details in this audio format" }
        val tags = collectTags(context, track, choice)
        check(!tags.isEmpty) { "No song details available for the exported audio file" }

        val tagged = File(context.cacheDir, "tagged_${UUID.randomUUID()}.${container.extension}")
        try {
            withContext(Dispatchers.IO) { AudioTagger.write(source, container, tags, tagged) }
            return files.publish(tagged, folderName, PlaylistDownloadPaths.withExtension(fileName, container.extension), container.mimeType)
        } finally {
            tagged.delete()
        }
    }

    private suspend fun collectTags(context: Context, track: Track, choice: DownloadMetadataChoice): AudioTags = choice.tagsFor(
        track = track,
        cover = if (choice.coverArt) withContext(Dispatchers.IO) { coverBytes(track) } else null,
        lyricsText = if (choice.lyrics) lyricsText(context, track) else null
    )

    private fun coverBytes(track: Track): ByteArray? = try {
        AuralisDownloadManager.getDownloadedArtworkFile(track.id)?.readBytes()?.let { image ->
            val embeddable = AudioTagger.isJpeg(image) || AudioTagger.isPng(image)
            if (embeddable && image.size <= MAX_COVER_BYTES) image else shrinkCover(image)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the cover of '${track.title}': ${e.message}")
        null
    }

    private fun shrinkCover(image: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((longest / MAX_COVER_SIDE).coerceAtLeast(1))
        }
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, options) ?: return null
        val scale = MAX_COVER_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val sized = if (scale >= 1f) decoded
        else Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        return ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, out)
            out.toByteArray()
        }
    }

    /** Timed the way the player shows them. Stored lyrics answer first; a miss gets one bounded search. */
    private suspend fun lyricsText(context: Context, track: Track): String? = try {
        withTimeoutOrNull(LYRICS_TIMEOUT_MS) {
            val repository = lyricsRepository(context)
            val durationMs = track.duration * 1000L
            val durationSec = track.duration.takeIf { it > 0L }
            val lyrics = repository.getCachedLyrics(
                title = track.title, artist = track.artist, durationSec = durationSec, videoId = track.id,
                album = track.album, channelTitle = track.channelTitle, durationMs = durationMs
            ) ?: repository.getLyrics(
                title = track.title, artist = track.artist, durationSec = durationSec, videoId = track.id,
                forceRefresh = false, album = track.album, channelTitle = track.channelTitle, durationMs = durationMs
            )
            lyrics?.let {
                if (durationMs > 0L && it.provider != LyricsProvider.LOCAL) LyricsAlignmentEngine.alignToPlayback(it, durationMs, null) else it
            }?.let(::lrcText)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No lyrics to embed for '${track.title}': ${e.message}")
        null
    }

    private fun lyricsRepository(context: Context): LyricsRepository = attachedLyrics ?: synchronized(this) {
        attachedLyrics ?: run {
            val appContext = context.applicationContext
            com.auralis.music.data.local.ImportedLyricsStore.init(appContext)
            val database = AuralisDatabase.getInstance(appContext)
            LyricsRepositoryImpl(LyricsClient(), database.lyricsDao(), database.negativeLyricsDao()).also { attachedLyrics = it }
        }
    }
}

/** The options a playlist was started with, so songs fetched on a retry or a later sync match the rest. */
object PlaylistDownloadMetadata {
    private const val PREFERENCES = "playlist_download_metadata"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(context: Context, playlistId: String, choice: DownloadMetadataChoice) {
        preferences(context).edit().putInt(playlistId, choice.storedFlags()).apply()
    }

    fun contains(context: Context, playlistId: String): Boolean = preferences(context).contains(playlistId)

    /** Older choices keep their cover/lyrics bits; song details are now always embedded. */
    fun choice(context: Context, playlistId: String): DownloadMetadataChoice {
        val flags = preferences(context).getInt(playlistId, 0)
        return DownloadMetadataChoice.fromStoredFlags(flags)
    }

    fun remove(context: Context, playlistId: String) {
        preferences(context).edit().remove(playlistId).apply()
    }
}

/** Asks what to embed before a download starts, unless the user chose to stop being asked. */
object DownloadMetadataPrompt {
    class Request(val initial: DownloadMetadataChoice, val onChoice: (DownloadMetadataChoice) -> Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()
    @Volatile private var hosts = 0

    /** The popup can only appear while the app's UI is up; anything else uses the saved options. */
    fun attachHost() { hosts++ }
    fun detachHost() { hosts-- }

    fun ask(context: Context, onChoice: (DownloadMetadataChoice) -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            val settings = DownloadMetadataDataStore(appContext).settingsFlow.first()
            if (settings.askEveryDownload && hosts > 0) _request.value = Request(settings.choice, onChoice)
            else onChoice(settings.choice)
        }
    }

    fun answer(context: Context, request: Request, choice: DownloadMetadataChoice, remember: Boolean) {
        _request.value = null
        val appContext = context.applicationContext
        scope.launch {
            if (remember) {
                val store = DownloadMetadataDataStore(appContext)
                store.setChoice(choice)
                store.setAskEveryDownload(false)
            }
            request.onChoice(choice)
        }
    }

    fun cancel() {
        _request.value = null
    }
}
