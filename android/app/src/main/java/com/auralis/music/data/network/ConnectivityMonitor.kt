package com.auralis.music.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one answer to "is there a network?" for the whole app.
 *
 * Follows the system's default network through a [ConnectivityManager.NetworkCallback].
 * A default network with the INTERNET capability counts as online; VALIDATED is not required,
 * since some working networks (VPNs, blocked probe hosts) never validate.
 */
object ConnectivityMonitor {
    /** Brief drops while the phone hands over between Wi-Fi and mobile data do not count as offline. */
    const val OFFLINE_SETTLE_MS = 1_500L

    private val raw = MutableStateFlow(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    private val settled = MutableStateFlow(true)

    /** True while a usable network is up. Starts true so nothing is hidden before [start] runs. */
    val isOnline: StateFlow<Boolean> = settled.asStateFlow()

    /** Emits once each time the network comes back after the app saw it go away. */
    val reconnects: Flow<Unit> get() = isOnline.reconnects()

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        // The state at launch applies at once (a cold start in airplane mode opens offline).
        val initial = hasInternet(cm.getNetworkCapabilities(cm.activeNetwork))
        raw.value = initial
        settled.value = initial
        scope.launch { raw.settleOffline(OFFLINE_SETTLE_MS).collect { settled.value = it } }
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Capabilities follow in onCapabilitiesChanged; a new default network is usable until they say otherwise.
                    raw.value = true
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    raw.value = hasInternet(caps)
                }

                override fun onLost(network: Network) {
                    raw.value = false
                }

                override fun onUnavailable() {
                    raw.value = false
                }
            })
        } catch (e: Exception) {
            android.util.Log.w("ConnectivityMonitor", "Network callback unavailable: ${e.message}")
            started = false
            raw.value = true
        }
    }

    private fun hasInternet(caps: NetworkCapabilities?): Boolean =
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

/**
 * The first value and every "online" pass through at once; a later "offline" is reported
 * only once it has lasted [settleMs].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun Flow<Boolean>.settleOffline(settleMs: Long): Flow<Boolean> = flow {
    var first = true
    emitAll(distinctUntilChanged().mapLatest { online ->
        if (!online && !first) delay(settleMs)
        first = false
        online
    }.distinctUntilChanged())
}

/** One emission per offline → online transition; the initial value never counts as a reconnect. */
fun Flow<Boolean>.reconnects(): Flow<Unit> = flow {
    var wasOffline = false
    distinctUntilChanged().collect { online ->
        if (online && wasOffline) emit(Unit)
        wasOffline = !online
    }
}
