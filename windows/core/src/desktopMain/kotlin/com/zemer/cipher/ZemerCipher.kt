package com.zemer.cipher

import okhttp3.OkHttpClient
import java.io.File
import java.net.Proxy

/**
 * Desktop configuration of the vendored zemer-cipher (see resources/zemer-cipher/NOTICE.md): where
 * its caches live, which Chromium runs YouTube's player script, and its one HTTP client.
 */
object ZemerCipher {
    var proxy: Proxy? = null

    /** Verbose logging; off in the app because zemer's debug lines print signatures and tokens. */
    var debugLogging: Boolean = false

    /** Player-script and config caches (set by [initialize]). */
    @Volatile var dataDir: File = File(System.getProperty("java.io.tmpdir"), "auralis-zemer")
        private set

    /** The PoToken API key, from the build's generated resource; blank when the build has none. */
    val poTokenApiKey: String by lazy {
        runCatching {
            java.util.Properties().apply {
                ZemerCipher::class.java.getResourceAsStream("/zemer-cipher/potoken.properties")?.use { load(it) }
            }.getProperty("POTOKEN_API_KEY", "").trim()
        }.getOrDefault("")
    }

    @Volatile
    private var cachedHttpClient: OkHttpClient? = null

    @Volatile
    private var cachedHttpClientProxy: Proxy? = null

    internal val httpClient: OkHttpClient
        @Synchronized get() {
            val p = proxy
            cachedHttpClient?.let { if (cachedHttpClientProxy === p) return it }
            return OkHttpClient.Builder()
                .apply { p?.let { proxy(it) } }
                .build()
                .also {
                    cachedHttpClient = it
                    cachedHttpClientProxy = p
                }
        }

    @Volatile private var initialized = false

    /** Call once at startup: [dataDir] for caches, [browser] supplies the Chromium to run. */
    @Synchronized
    fun initialize(dataDir: File, browser: () -> File?) {
        if (initialized) return
        initialized = true
        this.dataDir = dataDir.apply { mkdirs() }
        HeadlessChromium.workDir = dataDir
        HeadlessChromium.browserExe = browser
        PlayerConfigStore.initialize()
    }

    @Volatile private var refreshed = false

    /**
     * Refreshes the remote player-config table on first real use (upstream does it at app start;
     * here nothing is fetched for listeners who never play a restricted song).
     */
    fun onFirstUse() {
        if (refreshed) return
        refreshed = true
        PlayerConfigStore.scheduleStartupRefresh()
    }

    val isInitialized: Boolean get() = initialized

    /** Closes the unseen browser now (it starts again when needed). */
    suspend fun shutdown() = HeadlessChromium.shutdown()
}
