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
class TourTransitionFramesTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private lateinit var hostActivity: ComponentActivity
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    @Test fun measureAllSevenNextTransitions() {
        val samples = Collections.synchronizedList(mutableListOf<Long>())
        val draws = Collections.synchronizedList(mutableListOf<Long>())
        val layouts = Collections.synchronizedList(mutableListOf<Long>())
        val gpu = Collections.synchronizedList(mutableListOf<Long>())
        val animations = Collections.synchronizedList(mutableListOf<Long>())
        val commands = Collections.synchronizedList(mutableListOf<Long>())
        val unknown = Collections.synchronizedList(mutableListOf<Long>())
        val worker = HandlerThread("tour-frame-metrics").apply { start() }
        var window: Window? = null
        val measuring = java.util.concurrent.atomic.AtomicBoolean(false)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (measuring.get()) {
                samples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
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
            android.util.Log.i("TourTransitionFrames", "READY_FOR_UNLOCK")
            while (keyguard.isKeyguardLocked && SystemClock.uptimeMillis() < unlockDeadline) SystemClock.sleep(250)
            assertTrue("Phone remained locked", !keyguard.isKeyguardLocked)
            SystemClock.sleep(3500)
            val perPage = StringBuilder()
            repeat(7) { pageIndex ->
                val startIndex = samples.size
                measuring.set(true)
                clickTourButton("Next")
                SystemClock.sleep(1000)
                measuring.set(false)
                val pageFrames = synchronized(samples) { samples.drop(startIndex).map { it / 1_000_000.0 }.sorted() }
                if (pageFrames.isNotEmpty()) perPage.append("page_${pageIndex + 1}_p95_ms=${pageFrames[(pageFrames.lastIndex * .95).toInt()]}\npage_${pageIndex + 1}_worst_ms=${pageFrames.last()}\n")
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(hostActivity.getExternalFilesDir(null), "tour-timing-page-${pageIndex + 1}.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                SystemClock.sleep(1800)
            }
            clickTourButton("Get started")
            SystemClock.sleep(1000)
            assertTrue("Not all seven pages were reached", finished.get())
            val millis = synchronized(samples) { samples.map { it / 1_000_000.0 }.sorted() }
            assertTrue("No transition frames recorded", millis.isNotEmpty())
            val report = "frames=${millis.size}\np50_ms=${millis[millis.size / 2]}\np95_ms=${millis[(millis.lastIndex * .95).toInt()]}\nworst_ms=${millis.last()}\nover_32ms=${millis.count { it > 32 }}\nover_100ms=${millis.count { it > 100 }}\n"
            fun median(values: List<Long>): Double = synchronized(values) { values.sorted()[values.size / 2] / 1_000_000.0 }
            val detail = "draw_p50_ms=${median(draws)}\nlayout_p50_ms=${median(layouts)}\ngpu_p50_ms=${if (gpu.isEmpty()) 0.0 else median(gpu)}\n"
            val scheduling = "animation_p50_ms=${median(animations)}\ncommands_p50_ms=${median(commands)}\nunknown_p50_ms=${median(unknown)}\n"
            File(hostActivity.getExternalFilesDir(null), "tour-transition-frames.txt").writeText(report + detail + scheduling + perPage)
            android.util.Log.i("TourTransitionFrames", report)
        } finally {
            measuring.set(false)
            mainHandler.post {
                window?.removeOnFrameMetricsAvailableListener(listener)
                hostActivity.finish()
            }
            worker.quitSafely()
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
