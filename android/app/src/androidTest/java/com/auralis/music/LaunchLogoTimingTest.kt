package com.auralis.music

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.ui.components.LaunchLogoView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchLogoTimingTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    private fun duration(revealDelayMs: Long): Long {
        val removed = CountDownLatch(1)
        val elapsed = AtomicLong()
        activity.scenario.onActivity { host ->
            val root = FrameLayout(host)
            host.setContentView(root)
            val start = SystemClock.uptimeMillis()
            val logo = LaunchLogoView(host) {
                elapsed.set(SystemClock.uptimeMillis() - start)
                removed.countDown()
            }
            root.addView(logo, FrameLayout.LayoutParams(-1, -1))
            root.postDelayed({ logo.reveal() }, revealDelayMs)
        }
        assertTrue("Launch overlay remained attached", removed.await(4, TimeUnit.SECONDS))
        return elapsed.get()
    }

    @Test fun originalDrawingAnimationFinishesBeforeHomeIsRevealed() {
        val elapsed = duration(revealDelayMs = 0L)
        assertTrue("Animation was cut off at ${elapsed}ms", elapsed >= 1_000L)
        assertTrue("Logo took ${elapsed}ms", elapsed <= 1_800L)
    }

    @Test fun delayedSystemSplashExitDoesNotConsumeAnimationTime() {
        val elapsed = duration(revealDelayMs = 700L)
        assertTrue("Animation was removed before its delayed start finished: ${elapsed}ms", elapsed >= 1_700L)
        assertTrue("Delayed logo took ${elapsed}ms", elapsed <= 2_500L)
    }

    @Test fun revealRequestedBeforeAttachmentStillRuns() {
        val removed = CountDownLatch(1)
        val elapsed = AtomicLong()
        activity.scenario.onActivity { host ->
            val root = FrameLayout(host)
            host.setContentView(root)
            val start = SystemClock.uptimeMillis()
            val logo = LaunchLogoView(host) {
                elapsed.set(SystemClock.uptimeMillis() - start)
                removed.countDown()
            }
            logo.reveal()
            root.postDelayed({ root.addView(logo, FrameLayout.LayoutParams(-1, -1)) }, 200L)
        }
        assertTrue("An early reveal request was lost", removed.await(4, TimeUnit.SECONDS))
        assertTrue("Drawing was skipped after attachment", elapsed.get() >= 1_200L)
    }

    @Test fun completedMarkStaysVisibleUntilAppFrameIsReady() {
        val drawn = CountDownLatch(1)
        val removed = CountDownLatch(1)
        lateinit var logo: LaunchLogoView
        activity.scenario.onActivity { host ->
            val root = FrameLayout(host)
            host.setContentView(root)
            logo = LaunchLogoView(host, onDrawingComplete = { drawn.countDown() }) { removed.countDown() }
            root.addView(logo, FrameLayout.LayoutParams(-1, -1))
            logo.reveal()
        }
        assertTrue("Logo never finished drawing", drawn.await(3, TimeUnit.SECONDS))
        // Cross the previous two-second removal timer while the app is still preparing.
        SystemClock.sleep(1_500L)
        activity.scenario.onActivity { assertTrue("The completed logo was cut off", logo.isAttachedToWindow) }
        assertEquals(1L, removed.count)
        assertCompleteMarkVisible(logo)
        activity.scenario.onActivity { logo.contentReady() }
        assertTrue("App-ready handoff did not finish", removed.await(2, TimeUnit.SECONDS))
    }

    @Test fun reducedMotionShowsTheCompleteMark() {
        val drawn = CountDownLatch(1)
        val removed = CountDownLatch(1)
        lateinit var logo: LaunchLogoView
        activity.scenario.onActivity { host ->
            val root = FrameLayout(host)
            host.setContentView(root)
            logo = LaunchLogoView(host, onDrawingComplete = { drawn.countDown() }, animationScale = { 0f }) {
                removed.countDown()
            }
            root.addView(logo, FrameLayout.LayoutParams(-1, -1))
            logo.reveal()
        }
        assertTrue("Reduced-motion launch did not complete", drawn.await(2, TimeUnit.SECONDS))
        assertCompleteMarkVisible(logo)
        activity.scenario.onActivity { logo.contentReady() }
        assertTrue("Reduced-motion overlay stayed on screen", removed.await(2, TimeUnit.SECONDS))
    }

    /** Check real rendered pixels, not an animator's potentially stale UI-thread properties. */
    private fun assertCompleteMarkVisible(logo: LaunchLogoView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val copied = CountDownLatch(1)
        val result = AtomicInteger(-1)
        val expectedInk = AtomicInteger()
        var background = 0
        lateinit var bitmap: Bitmap
        activity.scenario.onActivity { host ->
            val side = (288f * host.resources.displayMetrics.density).toInt()
            val location = IntArray(2)
            logo.getLocationInWindow(location)
            val left = location[0] + (logo.width - side) / 2
            val top = location[1] + (logo.height - side) / 2
            background = host.getColor(R.color.launch_background)
            bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val expected = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(expected)
            canvas.drawColor(background)
            host.getDrawable(R.drawable.launch_mark_complete)!!.apply {
                setBounds(0, 0, side, side)
                draw(canvas)
            }
            expectedInk.set(inkPixels(expected, background))
            expected.recycle()
            PixelCopy.request(host.window, Rect(left, top, left + side, top + side), bitmap, {
                result.set(it)
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Could not capture the drawn logo", copied.await(2, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result.get())
        val actual = inkPixels(bitmap, background)
        bitmap.recycle()
        assertTrue("Only part of the final mark was visible ($actual / ${expectedInk.get()})",
            actual >= expectedInk.get() * 0.95)
        assertTrue("Unexpected pixels covered the logo", actual <= expectedInk.get() * 1.05)
    }

    private fun inkPixels(bitmap: Bitmap, background: Int): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count {
            kotlin.math.abs(Color.red(it) - Color.red(background)) +
                kotlin.math.abs(Color.green(it) - Color.green(background)) +
                kotlin.math.abs(Color.blue(it) - Color.blue(background)) > 96
        }
    }
}
