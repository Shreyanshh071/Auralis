package com.auralis.music

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.ui.i18n.AppLanguage
import com.auralis.music.ui.onboarding.AppTour
import com.auralis.music.ui.theme.LocalReducedMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import java.io.File
import java.util.Collections

/** Real display frames and real-time animations, independent of Espresso's idle clock. */
@RunWith(AndroidJUnit4::class)
class TourFingerSwipeTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private lateinit var hostActivity: ComponentActivity
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    @Test fun fingerDragsReachEveryPageAndCanReverseAndCancel() {
        val samples = Collections.synchronizedList(mutableListOf<Long>())
        val draws = Collections.synchronizedList(mutableListOf<Long>())
        val layouts = Collections.synchronizedList(mutableListOf<Long>())
        val gpu = Collections.synchronizedList(mutableListOf<Long>())
        val animations = Collections.synchronizedList(mutableListOf<Long>())
        val commands = Collections.synchronizedList(mutableListOf<Long>())
        val unknown = Collections.synchronizedList(mutableListOf<Long>())
        val intervals = Collections.synchronizedList(mutableListOf<Long>())
        val previousVsync = java.util.concurrent.atomic.AtomicLong(0L)
        val worker = HandlerThread("tour-finger-metrics").apply { start() }
        var window: Window? = null
        val measuring = java.util.concurrent.atomic.AtomicBoolean(false)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (measuring.get()) {
                samples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                val previous = previousVsync.getAndSet(vsync)
                if (previous > 0 && vsync > previous) intervals.add(vsync - previous)
                draws.add(metrics.getMetric(FrameMetrics.DRAW_DURATION))
                animations.add(metrics.getMetric(FrameMetrics.ANIMATION_DURATION))
                commands.add(metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION))
                unknown.add(metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION))
                layouts.add(metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
                if (android.os.Build.VERSION.SDK_INT >= 31) gpu.add(metrics.getMetric(FrameMetrics.GPU_DURATION))
            }
        }
        try {
            activity.scenario.onActivity { hostActivity = it }
            val ready = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                val host = hostActivity
                AppLanguage.init(host.applicationContext)
                AppLanguage.attach(host)
                host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window = host.window
                host.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper))
                host.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        CompositionLocalProvider(LocalReducedMotion provides false) {
                            AppTour(onFinish = { finished.set(true) })
                        }
                    }
                }
                ready.countDown()
            }
            assertTrue("Tour setup did not finish", ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val keyguard = hostActivity.getSystemService(android.app.KeyguardManager::class.java)
            val unlockDeadline = SystemClock.uptimeMillis() + 120_000L
            android.util.Log.i("TourFingerSwipe", "READY_FOR_UNLOCK")
            while (keyguard.isKeyguardLocked && SystemClock.uptimeMillis() < unlockDeadline) SystemClock.sleep(250)
            assertTrue("Phone remained locked", !keyguard.isKeyguardLocked)
            SystemClock.sleep(3500)
            // Verify the first, previously unseen incoming preview while the finger
            // is still down, before any page has been visited and captured on screen.
            drag(forward = true, durationMs = 750L, fraction = .45f, holdMs = 300L, retrace = true)
            SystemClock.sleep(700)
            assertTitleVisible("Your music. Your Auralis.")
            val perPage = StringBuilder()
            repeat(7) { pageIndex ->
                val startIndex = samples.size
                previousVsync.set(0L)
                measuring.set(true)
                drag(forward = true, durationMs = if (pageIndex % 2 == 0) 750L else 350L)
                SystemClock.sleep(400)
                measuring.set(false)
                val pageFrames = synchronized(samples) { samples.drop(startIndex).map { it / 1_000_000.0 }.sorted() }
                if (pageFrames.isNotEmpty()) perPage.append("page_${pageIndex + 1}_p95_ms=${pageFrames[(pageFrames.lastIndex * .95).toInt()]}\npage_${pageIndex + 1}_worst_ms=${pageFrames.last()}\n")
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(hostActivity.getExternalFilesDir(null), "tour-finger-page-${pageIndex + 1}.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                SystemClock.sleep(600)
            }
            clickTourButton("Get started")
            SystemClock.sleep(1000)

            val millis = synchronized(samples) { samples.map { it / 1_000_000.0 }.sorted() }
            assertTrue("No transition frames recorded", millis.isNotEmpty())
            val report = "frames=${millis.size}\np50_ms=${millis[millis.size / 2]}\np95_ms=${millis[(millis.lastIndex * .95).toInt()]}\nworst_ms=${millis.last()}\nover_32ms=${millis.count { it > 32 }}\nover_100ms=${millis.count { it > 100 }}\n"
            fun median(values: List<Long>): Double = synchronized(values) { values.sorted()[values.size / 2] / 1_000_000.0 }
            val detail = "draw_p50_ms=${median(draws)}\nlayout_p50_ms=${median(layouts)}\ngpu_p50_ms=${if (gpu.isEmpty()) 0.0 else median(gpu)}\n"
            val spacing = synchronized(intervals) { intervals.map { it / 1_000_000.0 }.sorted() }
            val cadence = "interval_p50_ms=${spacing[spacing.size / 2]}\ninterval_p95_ms=${spacing[(spacing.lastIndex * .95).toInt()]}\nintervals_over_32ms=${spacing.count { it > 32 }}\n"
            val scheduling = "animation_p50_ms=${median(animations)}\ncommands_p50_ms=${median(commands)}\nunknown_p50_ms=${median(unknown)}\n"
            File(hostActivity.getExternalFilesDir(null), "tour-finger-frames.txt").writeText(report + detail + scheduling + cadence + perPage)
            android.util.Log.i("TourFingerSwipe", report + cadence)
            assertTrue("Seven finger swipes did not reach the final page", finished.get())
            // Return using real drag input, rather than Back or the Next button.
            repeat(7) { index ->
                drag(forward = false, durationMs = if (index % 2 == 0) 350L else 750L)
                SystemClock.sleep(1000)
            }
            assertTitleVisible("Your music. Your Auralis.")
            // Interrupt the settling fling with the next finger drag, in both directions.
            drag(forward = true, durationMs = 350L)
            SystemClock.sleep(80)
            drag(forward = true, durationMs = 350L)
            SystemClock.sleep(1000)
            assertTitleVisible("A little more glass.")
            drag(forward = false, durationMs = 350L)
            SystemClock.sleep(80)
            drag(forward = false, durationMs = 350L)
            SystemClock.sleep(1000)
            assertTitleVisible("Your music. Your Auralis.")
            // Hold halfway through a drag, then return with the same finger. This
            // records the actual touched intermediate frame, not a programmatic slide.
            drag(forward = true, durationMs = 750L, fraction = .45f, holdMs = 300L, retrace = true)
            SystemClock.sleep(700)
            assertTitleVisible("Your music. Your Auralis.")
            // Also drag through the previews themselves, not only their headings.
            drag(forward = true, durationMs = 350L)
            SystemClock.sleep(700)
            drag(forward = true, durationMs = 750L, yFraction = .50f)
            SystemClock.sleep(700)
            assertTitleVisible("A little more glass.")
            drag(forward = false, durationMs = 750L, yFraction = .27f)
            SystemClock.sleep(700)
            assertTitleVisible("Make it yours.")
            drag(forward = false, durationMs = 350L)
            SystemClock.sleep(700)
            assertTitleVisible("Your music. Your Auralis.")
            // A short interrupted touch must cancel back to the first page.
            drag(forward = true, durationMs = 250L, fraction = .10f, holdMs = 300L, cancel = true)
            SystemClock.sleep(600)
            assertTitleVisible("Your music. Your Auralis.")
        } finally {
            measuring.set(false)
            mainHandler.post {
                window?.removeOnFrameMetricsAvailableListener(listener)
                hostActivity.finish()
            }
            worker.quitSafely()
        }
    }
    private fun assertTitleVisible(title: String) {
        val root = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        // Compose virtual nodes do not implement Android's find-by-text provider API.
        // Walk the actual accessibility tree instead of interpreting an empty search as
        // a failed swipe (the failure screenshot can already show the correct page).
        fun containsTitle(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && (node.text?.toString() == title || node.contentDescription?.toString() == title)) return true
            return (0 until node.childCount).any { index -> node.getChild(index)?.let(::containsTitle) == true }
        }
        val visible = root?.let(::containsTitle) == true
        if (!visible) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(hostActivity.getExternalFilesDir(null), "tour-finger-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        assertTrue("Expected visible page: $title; root=${root?.packageName}", visible)
    }

    /** Sustained touchscreen input through Android's InputDispatcher, not View dispatch. */
    private fun drag(forward: Boolean, durationMs: Long, fraction: Float = .76f, holdMs: Long = 0L, cancel: Boolean = false, retrace: Boolean = false, yFraction: Float = .135f) {
        val ready = java.util.concurrent.CountDownLatch(1)
        var width = 0
        var height = 0
        mainHandler.post {
            width = hostActivity.window.decorView.width
            height = hostActivity.window.decorView.height
            ready.countDown()
        }
        assertTrue("Display dimensions unavailable", ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        // The heading/hint region belongs to the tour. Home shelves retain their
        // own horizontal scrolling and must not be mistaken for a page gesture.
        val y = height * yFraction
        val startX = width * if (forward) .88f else .12f
        val distance = width * fraction * if (forward) -1f else 1f
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int, time: Long, x: Float, wait: Boolean = false) {
            val event = android.view.MotionEvent.obtain(down, time, action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Touch injection failed", ui.injectInputEvent(event, wait))
            event.recycle()
        }
        dispatch(android.view.MotionEvent.ACTION_DOWN, down, startX)
        val steps = (durationMs / 8L).toInt()
        repeat(steps) { index ->
            val progress = (index + 1f) / steps
            val time = down + ((index + 1L) * durationMs / steps)
            val remaining = time - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(remaining)
            dispatch(android.view.MotionEvent.ACTION_MOVE, time, startX + distance * progress)
        }
        var incomingInk = 0
        var incomingPreview = 0
        if (retrace) {
            ui.takeScreenshot()?.let { bitmap ->
                for (x in (bitmap.width * .65f).toInt() until bitmap.width step 3) {
                    for (ySample in (bitmap.height * .075f).toInt() until (bitmap.height * .15f).toInt() step 3) {
                        val pixel = bitmap.getPixel(x, ySample)
                        if (android.graphics.Color.red(pixel) > 190 && android.graphics.Color.green(pixel) > 190 && android.graphics.Color.blue(pixel) > 190) incomingInk++
                    }
                }
                for (x in (bitmap.width * .80f).toInt() until bitmap.width step 6) {
                    for (ySample in (bitmap.height * .20f).toInt() until (bitmap.height * .80f).toInt() step 6) {
                        val pixel = bitmap.getPixel(x, ySample)
                        val red = android.graphics.Color.red(pixel)
                        val green = android.graphics.Color.green(pixel)
                        val blue = android.graphics.Color.blue(pixel)
                        if (blue > 10 && blue > red * .85f && blue > green * .90f) incomingPreview++
                    }
                }
                File(hostActivity.getExternalFilesDir(null), "tour-finger-held.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        if (holdMs > 0) SystemClock.sleep(holdMs)
        if (retrace) {
            val returnStart = SystemClock.uptimeMillis()
            repeat(40) { index ->
                val time = returnStart + (index + 1L) * 8L
                val remaining = time - SystemClock.uptimeMillis()
                if (remaining > 0) SystemClock.sleep(remaining)
                dispatch(android.view.MotionEvent.ACTION_MOVE, time, startX + distance * (1f - (index + 1f) / 40))
            }
        }
        dispatch(if (cancel) android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), if (retrace) startX else startX + distance, wait = true)
        if (retrace) {
            assertTrue("Incoming heading was blank during held drag: ink=$incomingInk", incomingInk > 20)
            assertTrue("Incoming player was blank during held drag: pixels=$incomingPreview", incomingPreview > 50)
        }
    }

    private fun clickTourButton(label: String) {
        // Accessibility queries wait for a globally idle window; this screen intentionally
        // animates continuously. Inject into the known tour footer instead.
        mainHandler.post {
            val decor = hostActivity.window.decorView
            val density = hostActivity.resources.displayMetrics.density
            val bottom = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            val x = decor.width - 76f * density
            val y = decor.height - bottom - 37f * density
            val down = SystemClock.uptimeMillis()
            val press = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            decor.dispatchTouchEvent(press)
            press.recycle()
            decor.postDelayed({
                val release = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_UP, x, y, 0)
                decor.dispatchTouchEvent(release)
                release.recycle()
            }, 60)
        }
    }
}
