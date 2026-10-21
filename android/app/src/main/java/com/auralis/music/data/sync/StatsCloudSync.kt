package com.auralis.music.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.auralis.music.data.local.AuralisDatabase
import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.domain.repository.StatsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.room.withTransaction
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
    private const val SAME_LISTEN_WINDOW_MS = CloudListens.SAME_LISTEN_WINDOW_MS
    private const val BATCH_LIMIT = 400
    /** How often a running app checks the account for listens from other devices. */
    private const val PULL_INTERVAL_MS = 15L * 60_000L
    private const val PULL_OVERLAP_MS = 10L * 60_000L

    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private val mutex = Mutex()

    /** Set while an account is being deleted, so no upload re-creates the stats being removed. */
    @Volatile var paused: Boolean = false

    private lateinit var appContext: Context
    private lateinit var statsRepository: StatsRepository
    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences("auralis_stats_cloud", Context.MODE_PRIVATE)

    fun init(context: Context, repository: StatsRepository) {
        appContext = context.applicationContext
        statsRepository = repository
        // One-time: databases wiped before forgetRestores existed still had "restored" set, so
        // their listens never came back. Restoring again is safe; it skips listens already here.
        if (!prefs.getBoolean(RESTORE_RECHECK_KEY, false)) {
            forgetRestores(appContext)
            prefs.edit().putBoolean(RESTORE_RECHECK_KEY, true).apply()
        }
    }

    private const val RESTORE_RECHECK_KEY = "restore_recheck_after_db_reset_1"

    /**
     * Called when the local database is created or wiped (a destructive migration, e.g. installing
     * a build with an older schema). The "restored" flags live outside the database, so without
     * this a wiped phone never pulled its listens back from the account.
     */
    fun forgetRestores(context: Context) {
        val p = context.applicationContext.getSharedPreferences("auralis_stats_cloud", Context.MODE_PRIVATE)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith("restored_") || it.startsWith("pulled_at_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun signedInUid(): String? {
        val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        return user?.takeIf { !it.isAnonymous }?.uid
    }

    private fun collection(uid: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection(COLLECTION)

    /**
     * Pulls the account's listens (in full the first time, then what changed), uploads whenever
     * stats change, and checks the account again every [PULL_INTERVAL_MS] while the app runs, so
     * listening on the Windows app shows up here too.
     */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!::appContext.isInitialized) return
        appScope = scope
        scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(PULL_INTERVAL_MS)
                restoreIfNeeded()
                listenForOtherDevices(scope)
            }
        }
        scope.launch(Dispatchers.IO) {
            restoreIfNeeded()
            listenForOtherDevices(scope)
            upload()
            AuralisDatabase.getInstance(appContext).playbackEventDao()
                .getTotalPlayTimeInRange(0L, Long.MAX_VALUE)
                .distinctUntilChanged()
                .sample(UPLOAD_INTERVAL_MS)
                .collect { upload() }
        }
    }

    /**
     * Adds the listens in these `listening/{day}` documents that aren't on this phone yet, and
     * grows the ones another device synced while they were still playing. All of it is one
     * transaction: written a listen at a time, Stats counted up from 00:00 for as long as the
     * restore took instead of showing the account's total at once.
     */
    private suspend fun importDays(days: List<com.google.firebase.firestore.DocumentSnapshot>): Int {
        val incoming = CloudListens.parse(days.mapNotNull { it.get("events") as? Map<*, *> })
        if (incoming.isEmpty()) return 0
        val db = AuralisDatabase.getInstance(appContext)
        val dao = db.playbackEventDao()
        var changed = 0
        db.withTransaction {
            val from = incoming.minOf { it.timestamp } - CloudListens.SAME_LISTEN_WINDOW_MS
            val to = incoming.maxOf { it.timestamp } + CloudListens.SAME_LISTEN_WINDOW_MS
            val plan = CloudListens.plan(incoming, dao.getEventsSince(from).filter { it.timestamp <= to })
            db.trackDao().upsertTracksPreservingFavorite(plan.inserts.map { it.track.toEntity() })
            dao.insertEvents(plan.inserts.map { PlaybackEventEntity(trackId = it.track.id, timestamp = it.timestamp, playTimeMs = it.playTimeMs) })
            for (g in plan.grows) {
                dao.growEventsNear(g.track.id, g.timestamp - SAME_LISTEN_WINDOW_MS, g.timestamp + SAME_LISTEN_WINDOW_MS, g.playTimeMs)
            }
            changed = plan.changed
        }
        return changed
    }

    @Volatile private var appScope: CoroutineScope? = null

    /** After signing in: live listens from other devices now, not at the next 15-minute check. */
    fun startLiveUpdates() {
        appScope?.let { listenForOtherDevices(it) }
    }

    @Volatile private var listenerUid: String? = null
    private var listener: com.google.firebase.firestore.ListenerRegistration? = null

    /**
     * Live updates from the account while the app runs: days written by the Windows app (or
     * another phone) arrive within seconds instead of at the next check. Its own uploads also
     * fire here and are skipped as duplicates.
     */
    private fun listenForOtherDevices(scope: CoroutineScope) {
        val uid = signedInUid() ?: return
        if (!LocalDataOwner.belongsTo(uid) || listenerUid == uid) return
        listener?.remove()
        listenerUid = uid
        listener = collection(uid)
            .whereGreaterThan("updatedAt", System.currentTimeMillis() - PULL_OVERLAP_MS)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    if (error != null) Log.w(TAG, "Live listening updates stopped: ${error.message}")
                    listenerUid = null
                    return@addSnapshotListener
                }
                val changed = snapshot.documentChanges.map { it.document }
                if (changed.isEmpty() || paused) return@addSnapshotListener
                scope.launch(Dispatchers.IO) {
                    if (signedInUid() != uid || !LocalDataOwner.belongsTo(uid)) return@launch
                    mutex.withLock {
                        try {
                            val added = importDays(changed)
                            if (added > 0) Log.i(TAG, "Added $added listens from another device")
                        } catch (e: Exception) {
                            Log.w(TAG, "Live listening update failed: ${e.message}")
                        }
                    }
                }
            }
    }

    /**
     * Pulls the account's listens into this phone: every day the first time for this account on
     * this install, afterwards only days updated since the last pull (by this phone or by the
     * Windows app). Listens already here are skipped.
     */
    suspend fun restoreIfNeeded() = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext
        val uid = signedInUid() ?: return@withContext
        // Another account's listens are on this phone until the switch clears them.
        if (!LocalDataOwner.belongsTo(uid)) return@withContext
        val restoredBefore = prefs.getBoolean("restored_$uid", false)
        val pulledAt = prefs.getLong("pulled_at_$uid", 0L)
        mutex.withLock {
            try {
                val db = AuralisDatabase.getInstance(appContext)
                val dao = db.playbackEventDao()
                val pullStarted = System.currentTimeMillis()
                val days = if (restoredBefore && pulledAt > 0L) {
                    // A margin for clock differences between devices.
                    collection(uid).whereGreaterThan("updatedAt", pulledAt - PULL_OVERLAP_MS).get().await().documents
                } else collection(uid).get().await().documents
                val added = importDays(days)
                prefs.edit().putBoolean("restored_$uid", true).putLong("pulled_at_$uid", pullStarted).apply()
                Log.i(TAG, "Restored $added listens from the account (${days.size} days checked)")
            } catch (e: Exception) {
                Log.w(TAG, "Restore failed, will retry next start: ${e.message}")
            }
        }
    }

    /** Sends listens recorded since the last upload (plus any that were still growing). */
    suspend fun upload() = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized || paused) return@withContext
        val uid = signedInUid() ?: return@withContext
        // Never send one account's listening history to another account.
        if (!LocalDataOwner.belongsTo(uid)) return@withContext
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

    /**
     * Account switch: removes the previous account's listening data from this phone only (it's
     * in that account's cloud backup), and lets [newUid]'s own history restore here again.
     */
    suspend fun clearLocalForAccountSwitch(newUid: String) = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext
        mutex.withLock {
            val db = AuralisDatabase.getInstance(appContext)
            db.historyDao().clearHistory()
            db.playCountDao().clearPlayCounts()
            db.playbackEventDao().clearAllEvents()
            db.searchHistoryDao().clearSearchHistory()
            prefs.edit().remove("restored_$newUid").remove("watermark_$newUid").apply()
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
