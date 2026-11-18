package com.auralis.music.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.auralis.music.data.local.AuralisDatabase
import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.data.local.mapper.toDomain
import com.auralis.music.domain.repository.StatsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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

    private const val RESTORE_RECHECK_KEY = "restore_recheck_with_history_2"

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
                // On the account sync service, other devices' listens arrive by notification instead.
                if (cloudOn()) continue
                restoreIfNeeded()
                listenForOtherDevices(scope)
            }
        }
        scope.launch(Dispatchers.IO) { followCloud(scope) }
        scope.launch(Dispatchers.IO) {
            restoreIfNeeded()
            listenForOtherDevices(scope)
            upload()
            AuralisDatabase.getInstance(appContext).playbackEventDao()
                .getTotalPlayTimeInRange(0L, Long.MAX_VALUE)
                .distinctUntilChanged()
                .sample(if (cloudOn()) 30_000L else UPLOAD_INTERVAL_MS)
                .collect { upload() }
        }
    }

    /**
     * Adds the listens in these `listening/{day}` documents that aren't on this phone yet, and
     * grows the ones another device synced while they were still playing. All of it is one
     * transaction: written a listen at a time, Stats counted up from 00:00 for as long as the
     * restore took instead of showing the account's total at once.
     */
    private suspend fun importDays(days: List<com.google.firebase.firestore.DocumentSnapshot>, uid: String,
        backfillHistory: Boolean = false): Int =
        importListens(CloudListens.parse(days.mapNotNull { it.get("events") as? Map<*, *> }), uid, backfillHistory)

    /** The same import for listens from either Firestore or the account sync service. */
    private suspend fun importListens(incoming: List<CloudListen>, uid: String, backfillHistory: Boolean = false): Int {
        if (incoming.isEmpty()) return 0
        val db = AuralisDatabase.getInstance(appContext)
        val dao = db.playbackEventDao()
        var changed = 0
        db.withTransaction {
            if (signedInUid() != uid || !LocalDataOwner.belongsTo(uid)) return@withTransaction
            val from = incoming.minOf { it.timestamp } - CloudListens.SAME_LISTEN_WINDOW_MS
            val to = incoming.maxOf { it.timestamp } + CloudListens.SAME_LISTEN_WINDOW_MS
            val plan = CloudListens.plan(incoming, dao.getEventsSince(from).filter { it.timestamp <= to })
            db.trackDao().upsertTracksPreservingFavorite(plan.inserts.map { it.track.toEntity() })
            dao.insertEvents(plan.inserts.map { PlaybackEventEntity(trackId = it.track.id, timestamp = it.timestamp, playTimeMs = it.playTimeMs) })
            for (g in plan.grows) {
                dao.growEventsNear(g.track.id, g.timestamp - SAME_LISTEN_WINDOW_MS, g.timestamp + SAME_LISTEN_WINDOW_MS, g.playTimeMs)
            }
            changed = plan.changed
            val historyDao = db.historyDao()
            val existingHistory = historyDao.getHistoryWithTracks().map {
                com.auralis.music.domain.model.HistoryEntry(it.track.toDomain(), it.history.playedAt)
            }
            val mergedHistory = com.auralis.music.domain.model.mergeSyncedHistory(existingHistory,
                (if (backfillHistory) incoming else plan.inserts).map {
                    com.auralis.music.domain.model.HistoryEntry(it.track, it.timestamp)
                })
            if (mergedHistory != existingHistory) {
                db.trackDao().upsertTracksPreservingFavorite(mergedHistory.map { it.track.toEntity() })
                // Replace only the capped recents projection, never play counts or measured events.
                historyDao.clearHistory()
                for (entry in mergedHistory) historyDao.upsertHistory(
                    com.auralis.music.data.local.entity.HistoryEntity(entry.track.id, entry.playedAt))
            }
        }
        return changed
    }

    @Volatile private var appScope: CoroutineScope? = null

    /** After signing in: live listens from other devices now, not at the next 15-minute check. */
    fun startLiveUpdates() {
        appScope?.let { scope -> scope.launch(Dispatchers.IO) { if (!cloudOn()) listenForOtherDevices(scope) } }
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
        if (cloudKnownOn == uid) return
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
                            val added = importDays(changed, uid)
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
        if (cloudOn()) { cloudRestore(uid); return@withContext }
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
                val added = importDays(days, uid, backfillHistory = !restoredBefore)
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
        if (cloudOn()) { cloudUpload(uid); return@withContext }
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
                                // Each credited artist, best source first (the official song's for a label upload).
                                "as" to t?.let { entity ->
                                    val track = entity.toDomain()
                                    val artists = com.auralis.music.data.network.OfficialArtistResolver.cached(track.id) ?: track.artists
                                    com.auralis.music.domain.model.TrackArtists.encode(artists)
                                },
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
            // On the account sync service a plain clear is this phone only; clearing the account is
            // the separate, explicit "all my devices" choice (clearAllDevices).
            if (AccountSyncHub.accountSyncOn) return@launch
            deleteFirestoreHistory(uid)
        }
    }

    /**
     * The explicit "clear on all my devices" choice: the account's history before now goes from
     * every updated device, and from Firestore while old app versions may still read it.
     */
    suspend fun clearAllDevices() = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.withTimeout(30_000L) {
                check(::appContext.isInitialized) { "Listening history is not ready. Try again." }
                val uid = signedInUid() ?: error("Sign in before clearing account history.")
                if (AccountSyncHub.accountSyncOn) {
                    check(cloudOn()) { "Couldn't reach the account history service. Try again." }
                    listensCloud.clear(uid, System.currentTimeMillis())
                    if (cloudBridge != false) deleteFirestoreHistory(uid, strict = true)
                } else {
                    deleteFirestoreHistory(uid, strict = true)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw java.io.IOException("Account history clear timed out. Try again.", e)
        }
    }

    private suspend fun deleteFirestoreHistory(uid: String, strict: Boolean = false) {
        run {
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
                    if (e is CancellationException || strict) throw e
                    Log.w(TAG, "Clearing account stats failed: ${e.message}")
                }
            }
        }
    }

    // ---- Listening history on the account sync service (account-sync-proposal.md) -------------------
    // Used only when the build names the service (AccountSyncHub.accountSyncOn) and the service
    // serves the topic. Otherwise everything above runs exactly as before.

    private val cloudStore by lazy { FileLibraryCloudStore(java.io.File(appContext.filesDir, "library-cloud")) }
    private val listensCloud by lazy { ListensCloudSync(cloudStore) { uid, frame -> AccountSyncHub.client(appContext).call(uid, frame) } }
    private val batcher by lazy { ListenBatcher(cloudStore) }
    private val device: String by lazy {
        AccountSyncHub.client(appContext)
        appContext.getSharedPreferences("auralis_account_prefs", Context.MODE_PRIVATE).getString("library_device", null) ?: "phone"
    }
    /** The account the service was confirmed to serve listens for, and the last bridge answer. */
    @Volatile private var cloudKnownOn: String? = null
    /** Whether the clear-stats dialog may offer "all my devices" (listening history is on the service). */
    val accountWideClearAvailable = kotlinx.coroutines.flow.MutableStateFlow(false)
    @Volatile private var cloudBridge: Boolean? = null
    @Volatile private var cloudStartedFor: String? = null
    private const val CLOUD_UPLOAD_INTERVAL_MS = 30_000L
    @Volatile private var lastCloudUpload = 0L

    /** Whether this account's listens use the service. Asks once per account; a refusal or outage means no. */
    private suspend fun cloudOn(): Boolean {
        if (!AccountSyncHub.accountSyncOn || !::appContext.isInitialized) return false
        val uid = signedInUid() ?: return false
        if (cloudKnownOn == uid) return true
        val status = runCatching { listensCloud.status(uid) }.getOrNull() ?: return false
        cloudBridge = status.optBoolean("bridge", true)
        cloudKnownOn = uid
        accountWideClearAvailable.value = true
        listener?.remove(); listener = null; listenerUid = null
        return true
    }

    private fun toCloudListens(events: List<ListenEvent>, clearedBefore: Long) = events
        .filter { it.device != device && it.startedAt >= clearedBefore }
        .map { e ->
            CloudListen(com.auralis.music.domain.model.Track(id = e.trackId, title = e.title ?: "Unknown Track", artist = e.artist ?: "Unknown Artist",
                album = e.album, thumbnail = e.thumbnail.orEmpty(), duration = e.duration,
                artists = com.auralis.music.domain.model.TrackArtists.decode(e.artistsJson)), e.startedAt, e.listenedMs)
        }

    /** Another device cleared the account's stats: listens and recent history before [before] go here too. */
    private suspend fun applyClear(uid: String, before: Long) {
        if (before <= prefs.getLong("cloud_cleared_$uid", 0L)) return
        val db = AuralisDatabase.getInstance(appContext)
        db.withTransaction {
            if (signedInUid() != uid || !LocalDataOwner.belongsTo(uid)) return@withTransaction
            val dao = db.playbackEventDao()
            val old = dao.getEventsInRange(0L, before - 1).map { it.event.id }
            old.chunked(500).forEach { dao.deleteEvents(it) }
            val historyDao = db.historyDao()
            val kept = historyDao.getHistoryWithTracks().filter { it.history.playedAt >= before }
            historyDao.clearHistory()
            kept.forEach { historyDao.upsertHistory(it.history) }
        }
        prefs.edit().putLong("cloud_cleared_$uid", before).apply()
    }

    private suspend fun applyPulled(uid: String, pulled: ListensPulled) {
        if (pulled.clearedBefore > 0) applyClear(uid, pulled.clearedBefore)
        val added = importListens(toCloudListens(pulled.events, pulled.clearedBefore), uid, backfillHistory = pulled.reset)
        if (added > 0) Log.i(TAG, "Added $added listens from the account")
    }

    /**
     * Once per session: the one-time copy of the Firestore history, or, while the bridge is on, the
     * start-time check for listens uploaded by old app versions (found by upload time, so listens an
     * old phone recorded weeks ago and uploads now are included). Then what changed since this
     * phone's cursor.
     */
    private suspend fun cloudRestore(uid: String) {
        if (!LocalDataOwner.belongsTo(uid)) return
        mutex.withLock {
            if (cloudStartedFor != uid) {
                try {
                    val status = listensCloud.status(uid) ?: return@withLock
                    cloudBridge = status.optBoolean("bridge", true)
                    if (!status.optBoolean("imported")) {
                        val docs = collection(uid).get().await().documents
                        listensCloud.push(uid, firestoreEvents(docs), import = true)
                        listensCloud.markImported(uid)
                    } else if (cloudBridge == true) {
                        val started = System.currentTimeMillis()
                        val since = prefs.getLong("cloud_bridge_$uid", 0L)
                        val docs = collection(uid).whereGreaterThan("updatedAt", since - PULL_OVERLAP_MS).get().await().documents
                        val fromOldApps = firestoreEvents(docs).filter { it.device == null }
                        if (fromOldApps.isNotEmpty()) listensCloud.push(uid, fromOldApps, legacy = true)
                        prefs.edit().putLong("cloud_bridge_$uid", started).apply()
                    }
                    cloudStartedFor = uid
                } catch (e: ListensImportLater) { Log.i(TAG, "History copy continues tomorrow") }
                catch (e: Exception) { Log.w(TAG, "Listening history bridge will retry: ${e.javaClass.simpleName}") }
            }
            // After a bridge restore: copy to Firestore what updated apps recorded while it was retired.
            try {
                val status = listensCloud.status(uid)
                if (status != null && status.optBoolean("bridge", true) && status.has("backfillFrom")) {
                    val from = status.optLong("backfillFrom")
                    listensCloud.daysSince(uid, from) { _, list -> if (list.isNotEmpty()) mirror(uid, list) }
                    listensCloud.markBackfilled(uid, from)
                }
            } catch (e: Exception) { Log.w(TAG, "Listening backfill to Firestore will retry: ${e.javaClass.simpleName}") }
            try { listensCloud.pull(uid) { applyPulled(uid, it) } }
            catch (e: Exception) { Log.w(TAG, "Listening history pull failed, will retry: ${e.javaClass.simpleName}") }
        }
    }

    private fun firestoreEvents(docs: List<com.google.firebase.firestore.DocumentSnapshot>): List<ListenEvent> = docs.flatMap { doc ->
        (doc.get("events") as? Map<*, *>)?.values?.mapNotNull { raw ->
            val e = raw as? Map<*, *> ?: return@mapNotNull null
            val id = (e["t"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ts = (e["ts"] as? Number)?.toLong() ?: return@mapNotNull null
            val ms = (e["ms"] as? Number)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
            ListenEvent(id, ts, ms, e["ti"] as? String, e["ar"] as? String, e["al"] as? String, e["th"] as? String,
                (e["du"] as? Number)?.toLong() ?: 0L, e["d"] as? String, e["as"] as? String)
        }.orEmpty()
    }

    /**
     * This phone's measured listens, sending only increased time, at most every 30 seconds, and mirrored
     * to Firestore (writes only, marked with this device) while old app versions may read it.
     */
    private suspend fun cloudUpload(uid: String, force: Boolean = false) {
        if (!LocalDataOwner.belongsTo(uid)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCloudUpload < CLOUD_UPLOAD_INTERVAL_MS) return
        mutex.withLock {
            try {
                statsRepository.removeEstimatedListens()
                statsRepository.mergeChunkedListens()
                val db = AuralisDatabase.getInstance(appContext)
                val tracks = HashMap<String, com.auralis.music.data.local.entity.TrackEntity?>()
                val mine = db.playbackEventDao().getEventsSince(now - 7 * 86_400_000L).filter { it.playTimeMs > 0 }.map { ev ->
                    val t = tracks.getOrPut(ev.trackId) { db.trackDao().getTrackById(ev.trackId) }
                    ListenEvent(ev.trackId, ev.timestamp, ev.playTimeMs, t?.title, t?.artist, t?.album, t?.thumbnail, t?.duration ?: 0L, device,
                        t?.toDomain()?.let { track -> com.auralis.music.domain.model.TrackArtists.encode(
                            com.auralis.music.data.network.OfficialArtistResolver.cached(track.id) ?: track.artists) })
                }
                val ready = batcher.ready(uid, mine, now, includePlaying = true)
                lastCloudUpload = now
                if (ready.isEmpty()) return@withLock
                listensCloud.push(uid, ready)
                batcher.markSent(uid, ready, now)
                if (cloudBridge != false) runCatching { mirror(uid, ready) }
                    .onFailure { Log.w(TAG, "Listening mirror to Firestore failed: ${it.javaClass.simpleName}") }
                Log.i(TAG, "Sent ${ready.size} measured listens to the account")
            } catch (e: Exception) {
                Log.w(TAG, "Listening upload failed, will retry: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun mirror(uid: String, events: List<ListenEvent>) {
        val firestore = FirebaseFirestore.getInstance()
        for (days in events.groupBy { dayFormat.format(Instant.ofEpochMilli(it.startedAt)) }.entries.chunked(BATCH_LIMIT)) {
            val batch = firestore.batch()
            for ((day, list) in days) {
                val map = list.associate { e ->
                    e.key to mapOf("t" to e.trackId, "ts" to e.startedAt, "ms" to e.listenedMs, "ti" to e.title, "ar" to e.artist,
                        "al" to e.album, "th" to e.thumbnail, "du" to e.duration, "d" to e.device, "as" to e.artistsJson)
                }
                batch.set(collection(uid).document(day), mapOf("events" to map, "updatedAt" to System.currentTimeMillis()), SetOptions.merge())
            }
            batch.commit().await()
        }
    }

    /**
     * While the app is showing: one socket (shared with settings) brings other devices' listens at
     * once; coming back to the foreground pulls what changed while it was closed, and sends what
     * finished meanwhile. Nothing is polled.
     */
    private suspend fun followCloud(scope: CoroutineScope) {
        var seenForeground = -1L
        while (true) {
            kotlinx.coroutines.delay(2_000L)
            val uid = signedInUid() ?: continue
            if (!cloudOn() || paused) continue
            if (AccountSyncHub.inForeground) {
                AccountSyncHub.client(appContext).listen(uid, "listens") { frame ->
                    if (paused || signedInUid() != uid) return@listen
                    val applied = frame != null && mutex.withLock { listensCloud.accept(uid, frame) { applyPulled(uid, it) } }
                    if (!applied) cloudRestore(uid)
                }
            }
            if (AccountSyncHub.foregroundCount != seenForeground) {
                if (seenForeground >= 0) { cloudRestore(uid); cloudUpload(uid, force = true) }
                seenForeground = AccountSyncHub.foregroundCount
            }
        }
    }
}
