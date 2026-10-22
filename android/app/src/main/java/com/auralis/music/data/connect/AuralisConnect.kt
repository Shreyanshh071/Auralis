package com.auralis.music.data.connect

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.auralis.music.data.service.AuralisAudioPlayer
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Auralis Connect on the phone: while signed in (not as an anonymous Listen Together guest), this
 * phone is one device of the account's listening session. It follows the account through
 * FirebaseAuth, so signing out or switching accounts drops the old account's session, listeners,
 * device list and controls at once.
 */
object AuralisConnect : ConnectHook {
    private const val TAG = "AuralisConnect"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(ConnectUi())
    val ui: StateFlow<ConnectUi> = _ui.asStateFlow()
    private val _remote = MutableStateFlow<ConnectRemoteView?>(null)
    /** The session this phone shows while another device plays it. */
    val remote: StateFlow<ConnectRemoteView?> = _remote.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var player: AuralisAudioPlayer
    @Volatile private var engine: ConnectEngine? = null
    private var uid: String? = null
    private var listeners: List<ListenerRegistration> = emptyList()
    private var jobs: List<Job> = emptyList()
    private var started = false
    private val followLock = kotlinx.coroutines.sync.Mutex()

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        player = AuralisAudioPlayer.getInstance(appContext)
        player.connectHook = this
        try {
            FirebaseAuth.getInstance().addAuthStateListener { auth -> scope.launch { follow(auth.currentUser?.takeIf { !it.isAnonymous }?.uid) } }
        } catch (e: Exception) {
            Log.w(TAG, "Connect unavailable: ${e.message}")
        }
    }

    private suspend fun follow(newUid: String?) = followLock.withLock {
        if (newUid == uid) return@withLock
        // Another account (or none): the old account's session is gone from this phone right away.
        stopEngine(signOut = false)
        uid = newUid
        if (newUid != null) startEngine(newUid)
    }

    /** Before signing out: give up the output and remove this phone from the account's devices. */
    suspend fun leaveAccount() = followLock.withLock {
        withTimeoutOrNull(5_000L) { stopEngine(signOut = true) }
        uid = null
    }

    fun transferTo(deviceId: String) { engine?.transferTo(deviceId) }

    override fun intercept(intent: ConnectIntent): Boolean {
        val e = engine ?: return false
        // Picking a song from the queue on screen plays that queue item on the output.
        if (intent is ConnectIntent.Load) {
            val remote = _remote.value
            if (remote != null && remote.output.active && intent.tracks.map { it.id } == remote.queue.map { it.id }) {
                val track = intent.tracks.getOrNull(intent.index)
                if (track != null) return e.intercept(ConnectIntent.PlayIndex(intent.index, track.id))
            }
        }
        return e.intercept(intent)
    }

    override fun remote(): ConnectRemoteView? = if (engine != null) _remote.value else null

    private fun startEngine(accountUid: String) {
        val prefs = appContext.getSharedPreferences("auralis_connect", Context.MODE_PRIVATE)
        // A separate random id per account on this install; never derived from hardware.
        val idKey = "device_$accountUid"
        val deviceId = prefs.getString(idKey, null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(idKey, it).apply() }
        val createdKey = "created_$accountUid"
        val created = prefs.getLong(createdKey, 0L).takeIf { it > 0L } ?: System.currentTimeMillis().also { prefs.edit().putLong(createdKey, it).apply() }
        val device = ConnectDevice(
            id = deviceId,
            name = deviceName(appContext),
            nickname = prefs.getString("nickname_$accountUid", null),
            platform = ConnectPlatform.ANDROID,
            type = if (appContext.resources.configuration.smallestScreenWidthDp >= 600) ConnectDeviceType.TABLET else ConnectDeviceType.PHONE,
            appVersion = com.auralis.music.BuildConfig.VERSION_NAME,
            createdMs = created
        )
        val clock = ServerClock(System::currentTimeMillis)
        val db = FirebaseFirestore.getInstance()
        val backend = FirestoreConnectBackend(db, accountUid, clock)
        val e = ConnectEngine(scope, backend, AndroidConnectPlayer(appContext, player), device, clock, System::currentTimeMillis) { Log.d(TAG, it) }
        engine = e
        Log.d(TAG, "Joined the account session as ${device.name} ($deviceId)")

        var session: ConnectSession? = null
        var queue: ConnectQueue? = null
        fun deliver() { e.onSnapshot(ConnectSnapshot(session, queue)) }
        // Only server-confirmed snapshots: a reconnecting phone acts on the authoritative state,
        // never on its offline cache.
        listeners = listOf(
            backend.sessionRef.addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                if (error != null) { e.onConnectionLost(error.message); return@addSnapshotListener }
                if (snap == null || snap.metadata.isFromCache || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                session = snap.data?.let { ConnectCodec.decodeSession(FirestoreConnectBackend.fromFirestore(it)) }
                session?.let { backend.sawSession(it, deviceId) }
                deliver()
            },
            backend.queueRef.addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                if (error != null || snap == null || snap.metadata.isFromCache || snap.metadata.hasPendingWrites()) return@addSnapshotListener
                queue = snap.data?.let { ConnectCodec.decodeQueue(FirestoreConnectBackend.fromFirestore(it)) }
                if (session != null) deliver()
            },
            backend.devicesRef.addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                if (error != null) { e.onConnectionLost(error.message); return@addSnapshotListener }
                if (snap == null || snap.metadata.isFromCache) return@addSnapshotListener
                val devices = snap.documents.filter { !it.metadata.hasPendingWrites() }.mapNotNull { d ->
                    d.data?.let { ConnectCodec.decodeDevice(FirestoreConnectBackend.fromFirestore(it)) }
                }
                devices.firstOrNull { it.id == deviceId }?.let(backend::sawOwnDevice)
                e.onDevices(devices)
            }
        )
        jobs = listOf(
            // A Listen Together guest shows the room, not the account's session.
            scope.launch {
                combine(e.ui, player.isGuestListenTogether) { u, guest -> u to guest }.collect { (u, guest) ->
                    _ui.value = u
                    _remote.value = u.remote.takeIf { !guest }
                }
            },
            scope.launch {
                while (isActive) {
                    delay(1_000L)
                    e.tick()
                }
            },
            // Local changes; the position only matters for seeks and drift, so it is sampled.
            scope.launch {
                merge(
                    combine(player.currentTrack, player.isPlaying, player.isBuffering, player.queueState, player.playbackRequested) { _, _, _, _, _ -> Unit },
                    player.playbackPositionMs.sample(1_000L),
                    player.userSeekEvents
                ).collect { e.onLocalChanged() }
            }
        )
        e.start()
    }

    private suspend fun stopEngine(signOut: Boolean) {
        val e = engine ?: return
        engine = null
        _ui.value = ConnectUi()
        _remote.value = null
        listeners.forEach { it.remove() }
        listeners = emptyList()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        e.stop(signOut)
    }

    /**
     * The name people gave the phone (Settings > About > Device name, readable without any
     * permission), else maker and model, else "Android phone" (filled in by the shared fallback).
     */
    fun deviceName(context: Context): String {
        val userName = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ConnectDeviceNames.clean(userName)?.let { return it }
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        return ConnectDeviceNames.clean(name).orEmpty()
    }
}
