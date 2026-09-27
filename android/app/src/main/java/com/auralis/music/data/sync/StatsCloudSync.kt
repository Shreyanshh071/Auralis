package com.auralis.music.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.auralis.music.data.local.AuralisDatabase
import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.StatsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Keeps listening stats on the signed-in account, so reinstalling the app (or a new phone)
 * doesn't lose them.
 *
 * Firestore: `users/{uid}/listening/{yyyy-MM-dd}` (UTC day), field `events` = map of
 * "<startMs>_<trackId>" -> one listen with its song's metadata. A listen's key never changes
 * while it grows, so re-uploading an in-progress listen overwrites it instead of duplicating.
 */
object StatsCloudSync {
    private const val TAG = "StatsCloudSync"
    private const val COLLECTION = "listening"
    /** Re-send listens this far behind the last upload: one may still have been growing then. */
    private const val IN_PROGRESS_OVERLAP_MS = 30L * 60_000L
    /** At most one upload per this interval while listening. */
    private const val UPLOAD_INTERVAL_MS = 120_000L
    /** Two listens of one song starting this close together are the same listen. */
    private const val SAME_LISTEN_WINDOW_MS = 2_000L
    private const val BATCH_LIMIT = 400

    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private val mutex = Mutex()

    private lateinit var appContext: Context
    private lateinit var statsRepository: StatsRepository
    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences("auralis_stats_cloud", Context.MODE_PRIVATE)

    fun init(context: Context, repository: StatsRepository) {
        appContext = context.applicationContext
        statsRepository = repository
    }

    private fun signedInUid(): String? {
        val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        return user?.takeIf { !it.isAnonymous }?.uid
    }

    private fun collection(uid: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection(COLLECTION)

    /** Restores once for this account on this install, then uploads whenever stats change. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!::appContext.isInitialized) return
        scope.launch(Dispatchers.IO) {
            restoreIfNeeded()
            upload()
            AuralisDatabase.getInstance(appContext).playbackEventDao()
                .getTotalPlayTimeInRange(0L, Long.MAX_VALUE)
                .distinctUntilChanged()
                .sample(UPLOAD_INTERVAL_MS)
                .collect { upload() }
        }
    }

    /** Pulls the account's listens into this phone, once per account per install. */
    suspend fun restoreIfNeeded() = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext
        val uid = signedInUid() ?: return@withContext
        if (prefs.getBoolean("restored_$uid", false)) return@withContext
        mutex.withLock {
            try {
                val db = AuralisDatabase.getInstance(appContext)
                val dao = db.playbackEventDao()
                var added = 0
                for (doc in collection(uid).get().await().documents) {
                    val events = doc.get("events") as? Map<*, *> ?: continue
                    for (raw in events.values) {
                        val e = raw as? Map<*, *> ?: continue
                        val trackId = e["t"] as? String ?: continue
                        val ts = (e["ts"] as? Number)?.toLong() ?: continue
                        val ms = (e["ms"] as? Number)?.toLong() ?: continue
                        if (trackId.isBlank() || ms <= 0L) continue
                        if (dao.countEventsNear(trackId, ts - SAME_LISTEN_WINDOW_MS, ts + SAME_LISTEN_WINDOW_MS) > 0) continue
                        val track = Track(
                            id = trackId,
                            title = e["ti"] as? String ?: "Unknown Track",
                            artist = e["ar"] as? String ?: "Unknown Artist",
                            album = e["al"] as? String,
                            thumbnail = e["th"] as? String ?: "",
                            duration = (e["du"] as? Number)?.toLong() ?: 0L
                        )
                        db.trackDao().upsertTrackPreservingFavorite(track.toEntity())
                        dao.insertEvent(PlaybackEventEntity(trackId = trackId, timestamp = ts, playTimeMs = ms))
                        added++
                    }
                }
                prefs.edit().putBoolean("restored_$uid", true).apply()
                Log.i(TAG, "Restored $added listens from the account")
            } catch (e: Exception) {
                Log.w(TAG, "Restore failed, will retry next start: ${e.message}")
            }
        }
    }

    /** Sends listens recorded since the last upload (plus any that were still growing). */
    suspend fun upload() = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext
        val uid = signedInUid() ?: return@withContext
        mutex.withLock {
            try {
                // Never upload the old 10s pieces or estimated listens; fix them locally first.
                statsRepository.removeEstimatedListens()
                statsRepository.mergeChunkedListens()

                val db = AuralisDatabase.getInstance(appContext)
                val watermarkKey = "watermark_$uid"
                val watermark = prefs.getLong(watermarkKey, 0L)
                val events = db.playbackEventDao().getEventsSince((watermark - IN_PROGRESS_OVERLAP_MS).coerceAtLeast(0L))
                if (events.isEmpty()) return@withLock

                val tracks = HashMap<String, com.auralis.music.data.local.entity.TrackEntity?>()
                val byDay = events.groupBy { dayFormat.format(Instant.ofEpochMilli(it.timestamp)) }
                val firestore = FirebaseFirestore.getInstance()
                for (days in byDay.entries.chunked(BATCH_LIMIT)) {
                    val batch = firestore.batch()
                    for ((day, dayEvents) in days) {
                        val map = dayEvents.associate { ev ->
                            val t = tracks.getOrPut(ev.trackId) { db.trackDao().getTrackById(ev.trackId) }
                            "${ev.timestamp}_${ev.trackId}" to mapOf(
                                "t" to ev.trackId,
                                "ts" to ev.timestamp,
                                "ms" to ev.playTimeMs,
                                "ti" to t?.title,
                                "ar" to t?.artist,
                                "al" to t?.album,
                                "th" to t?.thumbnail,
                                "du" to t?.duration
                            )
                        }
                        batch.set(
                            collection(uid).document(day),
                            mapOf("events" to map, "updatedAt" to System.currentTimeMillis()),
                            SetOptions.merge()
                        )
                    }
                    batch.commit().await()
                }
                prefs.edit().putLong(watermarkKey, events.maxOf { it.timestamp }).apply()
                Log.i(TAG, "Uploaded ${events.size} listens across ${byDay.size} days")
            } catch (e: Exception) {
                Log.w(TAG, "Upload failed, will retry: ${e.message}")
            }
        }
    }

    /** "Clear stats" must clear the account copy too, or the next restore brings it all back. */
    fun clearAccountStats() {
        if (!::appContext.isInitialized) return
        val uid = signedInUid() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                try {
                    val docs = collection(uid).get().await().documents
                    for (chunk in docs.chunked(BATCH_LIMIT)) {
                        val batch = FirebaseFirestore.getInstance().batch()
                        chunk.forEach { batch.delete(it.reference) }
                        batch.commit().await()
                    }
                    prefs.edit().remove("watermark_$uid").apply()
                    Log.i(TAG, "Cleared ${docs.size} days of listening from the account")
                } catch (e: Exception) {
                    Log.w(TAG, "Clearing account stats failed: ${e.message}")
                }
            }
        }
    }
}
