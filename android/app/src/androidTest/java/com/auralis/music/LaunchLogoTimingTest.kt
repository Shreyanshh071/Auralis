package com.auralis.music

import android.os.SystemClock
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.ui.components.LaunchLogoView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
}
