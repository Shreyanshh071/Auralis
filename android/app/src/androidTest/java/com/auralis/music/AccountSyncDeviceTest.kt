package com.auralis.music

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.sync.*
import com.auralis.music.domain.auth.GoogleAccountSyncManager
import com.auralis.music.domain.repository.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt-in real-phone checks against synthetic staging identities. Uses the isolated test runner,
 * private fixture storage, and no Firebase/YouTube session. Configuration is supplied privately
 * after installing the test APK, never packaged into the app or committed.
 */
@RunWith(AndroidJUnit4::class)
class AccountSyncDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config by lazy { JSONObject(File(context.filesDir, "sync-device-test.json").readText()).also {
        require(it.getString("base") == "https://auralis-connect-staging.auralis018.workers.dev")
        require(it.getString("uid").startsWith("staging-phone-"))
    } }
    private val clients = mutableListOf<LibraryCloudClient>()
    private val dirs = mutableListOf<File>()
    private fun uid(topic: String) = config.getString("uid") + "-" + topic
    private fun token(topic: String) = "staging:${config.getString("secret")}:${uid(topic).removePrefix("staging-")}"
    private fun dir(name: String) = File(context.cacheDir, "sync-device-${System.nanoTime()}-$name").also { it.mkdirs(); dirs += it }
    private fun client(topic: String, name: String, storage: File = dir(name)) = LibraryCloudClient(
        config.getString("base"), name, FileLibraryCloudStore(storage), OkHttpClient(), scope,
        token = { account, _ -> check(account == uid(topic)); token(topic) }, libraryEnabled = false
    ).also { clients += it }
    private suspend fun until(check: () -> Boolean) = withTimeout(45_000) { while (!check()) delay(100) }

    @Before fun requireOptInStagingFixture() {
        Assume.assumeTrue("Provide the private synthetic staging fixture to opt into network tests",
            File(context.filesDir, "sync-device-test.json").isFile)
    }

    @After fun close() {
        clients.forEach { it.stopAll() }; scope.cancel(); dirs.forEach { it.deleteRecursively() }
    }

    @Test fun phoneReceivesSettingFromMacWithoutPolling() = runBlocking {
        val c = client("settings", "phone")
        val backend = CloudPreferencesBackend("android") { u, f -> c.call(u, f) }
        val values = ConcurrentHashMap<String, AccountPreference>()
        backend.exchange(uid("settings"), emptyMap(), emptyMap())
        c.listen(uid("settings"), "settings") {
            values.putAll(backend.exchange(uid("settings"), emptyMap(), emptyMap()))
        }
        backend.exchange(uid("settings"), mapOf("a.pure_black_mini_player" to AccountPreference("true", 1, "phone")), emptyMap())
        // The companion on the Mac observes true and writes false at revision 2.
        until { values["a.pure_black_mini_player"]?.let { it.value == "false" && it.device == "mac-device-test" } == true }
        assertEquals(2L, values["a.pure_black_mini_player"]!!.revision)
    }

    @Test fun activityBackgroundClosesSocketAndForegroundCatchesMissedSettings() = runBlocking {
        val c = client("lifecycle", "phone-lifecycle")
        val peer = client("lifecycle", "remote-lifecycle")
        val backend = CloudPreferencesBackend("android") { u, f -> c.call(u, f) }
        val remote = CloudPreferencesBackend("android") { u, f -> peer.call(u, f) }
        val received = ConcurrentHashMap<String, AccountPreference>()
        val catchups = AtomicInteger()
        val hubField = AccountSyncHub::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = hubField.get(AccountSyncHub)
        hubField.set(AccountSyncHub, c)
        AccountSyncHub.inForeground = true
        // Register the actual app lifecycle callback without starting account restore or polling.
        fun <T : Any> stub(type: Class<T>): T = requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) { "toString" -> "isolated-device-test"; "hashCode" -> 1; "equals" -> false; else -> error("Unexpected repository call: ${method.name}") }
        }))
        val app = context.applicationContext as Application
        val added = mutableListOf<Application.ActivityLifecycleCallbacks>()
        val registration = object : Application() {
            override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
                added += callback
                app.registerActivityLifecycleCallbacks(callback)
            }
        }
        val fixtureContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = registration
        }
        val manager = GoogleAccountSyncManager(fixtureContext, stub(LibraryRepository::class.java), stub(HistoryRepository::class.java), stub(SearchRepository::class.java))
        GoogleAccountSyncManager::class.java.getDeclaredMethod("followForeground").apply { isAccessible = true }.invoke(manager)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            backend.exchange(uid("lifecycle"), emptyMap(), emptyMap())
            fun listen() { c.listen(uid("lifecycle"), "settings") {
                received.putAll(backend.exchange(uid("lifecycle"), emptyMap(), emptyMap())); catchups.incrementAndGet()
            } }
            listen(); until { catchups.get() > 0 }
            scenario.moveToState(Lifecycle.State.CREATED)
            until { !AccountSyncHub.inForeground }
            val count = catchups.get()
            delay(500)
            remote.exchange(uid("lifecycle"), mapOf("a.pure_black_mini_player" to AccountPreference("true", 1, "remote")), emptyMap())
            delay(1000)
            assertEquals("background stops the notification socket", count, catchups.get())
            assertFalse(received.containsKey("a.pure_black_mini_player"))
            val foreground = AccountSyncHub.foregroundCount
            scenario.moveToState(Lifecycle.State.RESUMED)
            until { AccountSyncHub.inForeground && AccountSyncHub.foregroundCount > foreground }
            listen() // Same reattachment performed by settings/listens foreground followers.
            until { received["a.pure_black_mini_player"]?.value == "true" }
        } finally {
            scenario.close()
            added.forEach { app.unregisterActivityLifecycleCallbacks(it) }
            hubField.set(AccountSyncHub, previous)
            AccountSyncHub.inForeground = true
        }
    }

    @Test fun finishedListensPersistAcrossClientRestartAndClearOnlySyntheticAccount() = runBlocking {
        val storage = dir("listens-phone")
        val c = client("listens", "phone-listens", storage)
        val remote = client("listens", "mac-listens")
        val phone = ListensCloudSync(FileLibraryCloudStore(storage)) { u, f -> c.call(u, f) }
        val other = ListensCloudSync(FileLibraryCloudStore(dir("listens-other"))) { u, f -> remote.call(u, f) }
        val now = System.currentTimeMillis()
        val event = ListenEvent("synthetic-song", now - 180_000, 120_000, "Synthetic song", "Fixture", device = "phone")
        val batcher = ListenBatcher(FileLibraryCloudStore(storage))
        val ready = batcher.ready(uid("listens"), listOf(event), now)
        assertEquals(1, ready.size)
        phone.push(uid("listens"), ready)
        batcher.markSent(uid("listens"), ready, now)
        assertTrue(ListenBatcher(FileLibraryCloudStore(storage)).ready(uid("listens"), listOf(event), now + 1000).isEmpty())
        val received = ConcurrentHashMap<String, ListenEvent>()
        other.pull(uid("listens")) { p -> p.events.forEach { received[it.key] = it } }
        assertEquals(120_000L, received[event.key]!!.listenedMs)
        c.stopAll()
        val restarted = client("listens", "phone-listens-restarted", storage)
        val resumed = ListensCloudSync(FileLibraryCloudStore(storage)) { u, f -> restarted.call(u, f) }
        resumed.clear(uid("listens"), now)
        var clear = 0L
        other.pull(uid("listens")) { clear = it.clearedBefore; received.values.removeIf { e -> e.startedAt < clear } }
        assertTrue(clear >= now)
        assertTrue(received.isEmpty())
        restarted.erase(uid("listens"))
        try {
            resumed.pull(uid("listens")) { }
            fail("an erased synthetic account must refuse further history access")
        } catch (_: LibraryAuthorityChanged) { /* expected */ }
    }
}
