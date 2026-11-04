package com.auralis.music.data.connect

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.auralis.music.data.service.AuralisAudioPlayer
import com.google.firebase.auth.FirebaseAuth
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Auralis Connect on the phone: while signed in (not as an anonymous Listen Together guest), this
 * phone is one device of the account's listening session. It follows the account through
 * FirebaseAuth, so signing out or switching accounts drops the old account's session, socket,
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
    private val remotePresentation = ConnectRemotePresentation()

    /** Closing a historical mini player affects this phone only. Live outputs remain protected. */
    fun dismissInactiveRemote(): Boolean {
        if (engine?.ui?.value?.remote?.output?.active == true) return false
        if (!remotePresentation.dismiss(_remote.value)) return false
        _remote.value = null
        return true
    }

    private lateinit var appContext: Context
    private lateinit var player: AuralisAudioPlayer
    @Volatile private var engine: ConnectEngine? = null
    private var realtime: ConnectRealtimeBackend? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    val deviceSyncAvailable: Boolean get() = com.auralis.music.BuildConfig.CONNECT_REALTIME_URL.isNotBlank()
    private val _deviceSyncEnabled = MutableStateFlow(false)
    val deviceSyncEnabled: StateFlow<Boolean> = _deviceSyncEnabled.asStateFlow()

    fun setDeviceSyncEnabled(enabled: Boolean) {
        if (enabled && !deviceSyncAvailable) return
        scope.launch {
            followLock.withLock {
                if (_deviceSyncEnabled.value == enabled) return@withLock
                appContext.getSharedPreferences("auralis_connect", Context.MODE_PRIVATE)
                    .edit().putBoolean("realtime_transport", enabled).apply()
                _deviceSyncEnabled.value = enabled
                if (enabled) uid?.takeIf { presence.taskPresent }?.let(::startEngine)
                else stopEngine(signOut = false)
                syncPresenceService()
            }
        }
    }

    private var uid: String? = null
    private var jobs: List<Job> = emptyList()
    private var started = false
    private val presence = ConnectTaskPresence()

    fun setForeground(visible: Boolean) {
        presence.foreground(visible)
        if (visible) scope.launch {
            followLock.withLock {
                uid?.takeIf { engine == null && presence.taskPresent }?.let(::startEngine)
                syncPresenceService()
            }
        }
        updatePresence()
    }

    // Retained so an older service instance stops cleanly after an in-place update.
    internal fun shouldRunPresenceService(): Boolean = false

    internal fun presenceServiceChanged(running: Boolean) {
        updatePresence()
    }

    private fun syncPresenceService() {
        if (!::appContext.isInitialized) return
        val intent = android.content.Intent(appContext, com.auralis.music.service.ConnectPresenceService::class.java)
        // Idle availability no longer starts its own foreground service/notification.
        // Active playback already has a media service and its normal music notification.
        appContext.stopService(intent)
    }

    /** Removing the task is an explicit close, independently of music-on-task-clear settings. */
    fun onTaskRemoved() {
        presence.removeTask()
        updatePresence()
        scope.launch {
            followLock.withLock {
                if (!presence.taskPresent) {
                    // Remove registration, not authentication. Reopening joins with the same id.
                    stopEngine(signOut = true)
                    syncPresenceService()
                }
            }
        }
    }

    private fun updatePresence() {
        if (!started) return
        presence.service(::player.isInitialized &&
            (player.isPlaying.value || player.isBuffering.value || player.playbackRequested.value))
        engine?.setDeviceAvailable(presence.available)
    }
    private val followLock = kotlinx.coroutines.sync.Mutex()

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        _deviceSyncEnabled.value = appContext.getSharedPreferences("auralis_connect", Context.MODE_PRIVATE)
            .getBoolean("realtime_transport", false)
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
        if (newUid != null && presence.taskPresent) startEngine(newUid)
        syncPresenceService()
    }

    /** Before signing out: give up the output and remove this phone from the account's devices. */
    suspend fun leaveAccount() = followLock.withLock {
        withTimeoutOrNull(5_000L) { stopEngine(signOut = true) }
        uid = null
        syncPresenceService()
    }

    /** Detach now; best-effort server cleanup belongs to the captured old engine. */
    fun disconnectLocally() {
        val oldEngine = engine
        val oldRealtime = realtime
        engine = null
        realtime = null
        stopNetworkWatch()
        uid = null
        syncPresenceService()
        _ui.value = ConnectUi()
        _remote.value = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        if (oldEngine?.isOutput == true && ::player.isInitialized) {
            player.fromConnect { player.pause() }
        }
        if (oldEngine != null) scope.launch {
            try {
                withTimeoutOrNull(4_000L) { oldEngine.stop(signOut = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Signed out locally; server cleanup unavailable: ${e.message}")
            } finally {
                oldRealtime?.stop()
            }
        }
    }

    fun transferTo(deviceId: String) { engine?.transferTo(deviceId) }

    /** Reconcile immediately when returning from Android background suspension. */
    fun refresh() { realtime?.nudge() }

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

    override fun remote(): ConnectRemoteView? = engine?.ui?.value?.remote
        ?.takeUnless { player.isGuestListenTogether.value }
        ?.let(remotePresentation::present)

    private fun startEngine(accountUid: String) {
        if (!_deviceSyncEnabled.value || !deviceSyncAvailable) return
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
        val clock = ServerClock(System::currentTimeMillis, SystemClock::elapsedRealtime)
        val realtimeUrl = com.auralis.music.BuildConfig.CONNECT_REALTIME_URL.takeIf { it.isNotBlank() } ?: return
        val sink = object : ConnectRealtimeSink {
            @Volatile var engine: ConnectEngine? = null
            override fun onSnapshot(snapshot: ConnectSnapshot) { engine?.onSnapshot(snapshot) }
            override fun onDevices(devices: List<ConnectDevice>) { engine?.onDevices(devices) }
            override fun onConnectionLost(message: String?) { engine?.onConnectionLost(message) }
        }
        val realtime = realtimeUrl.let { url ->
            ConnectRealtimeBackend(url, deviceId, clock, scope, sink, okhttp3.OkHttpClient(), log = { Log.d(TAG, it) }) { refresh ->
                // Only the signed-in account's own token: never an anonymous Listen Together guest's.
                FirebaseAuth.getInstance().currentUser?.takeIf { !it.isAnonymous && it.uid == accountUid }
                    ?.getIdToken(refresh)?.await()?.token
            }
        }
        this.realtime = realtime
        val e = ConnectEngine(scope, realtime, AndroidConnectPlayer(appContext, player), device, clock, System::currentTimeMillis,
            SystemClock::elapsedRealtime, savedControls(prefs, accountUid)) { Log.d(TAG, it) }
        sink.engine = e
        engine = e
        updatePresence()
        Log.d(TAG, "Joined the account session as ${device.name} ($deviceId) over WebSocket")

        jobs = listOf(
            // A Listen Together guest shows the room, not the account's session.
            scope.launch {
                combine(e.ui, player.isGuestListenTogether) { u, guest -> u to guest }.collect { (u, guest) ->
                    _ui.value = u
                    _remote.value = remotePresentation.present(u.remote).takeIf { !guest }
                }
            },
            scope.launch {
                while (isActive) {
                    delay(1_000L)
                    e.tick()
                }
            },
            // Seeks are discrete controls, never delayed by the periodic drift sample.
            scope.launch { player.userSeekEvents.collect { e.onLocalSeek() } },
            // Routine position changes are sampled; controls above publish immediately.
            scope.launch {
                merge(
                    combine(player.currentTrack, player.isPlaying, player.isBuffering, player.queueState, player.playbackRequested) { _, _, _, _, _ -> Unit },
                    player.playbackPositionMs.sample(1_000L)
                ).collect { updatePresence(); e.onLocalChanged() }
            }
        )
        e.start()
        realtime.start()
        watchNetwork(realtime)
    }

    /** A new network (Wi-Fi to mobile data, or back) reconnects now instead of after a backoff. */
    private fun watchNetwork(realtime: ConnectRealtimeBackend) {
        val manager = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { realtime.nudge() }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun stopNetworkWatch() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { appContext.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun savedControls(prefs: android.content.SharedPreferences, accountUid: String) = object : ConnectControlStore {
        private val key = "ws_control_$accountUid"
        override fun load() = ConnectSavedControl.decode(prefs.getString(key, null))
        override fun save(control: ConnectSavedControl?) { prefs.edit().putString(key, control?.encode()).apply() }
    }

    private suspend fun stopEngine(signOut: Boolean) {
        val e = engine ?: return
        val oldRealtime = realtime
        engine = null
        realtime = null
        stopNetworkWatch()
        _ui.value = ConnectUi()
        _remote.value = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        // The old transport gives up the output first, then closes.
        try { e.stop(signOut) } finally { oldRealtime?.stop() }
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
