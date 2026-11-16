package com.auralis.music.data.network

import java.io.File
import java.net.UnknownHostException
import kotlin.random.Random
import kotlin.test.*

private class FakeClock(var mono: Long = 1_000_000L, var wall: Long = 1_800_000_000_000L) : CooldownClock {
    override fun monotonicMs() = mono
    override fun wallMs() = wall
    fun advance(ms: Long) { mono += ms; wall += ms }
}

/** A stand-in with NewPipe's class name: the classifier matches by name, not by import. */
private class SignInConfirmNotBotException(message: String) : Exception(message)

class RetryAfterTest {
    private val now = 1_800_000_000_000L

    @Test fun parsesSecondsAndHttpDates() {
        assertEquals(120_000L, RetryAfter.parseMs("120", now))
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now + 90_000L).atZone(java.time.ZoneOffset.UTC))
        assertEquals(90_000L, RetryAfter.parseMs(date, now))
    }

    @Test fun malformedPastAndOverflowingValuesAreBounded() {
        assertNull(RetryAfter.parseMs("soon", now))
        assertNull(RetryAfter.parseMs("", now))
        assertNull(RetryAfter.parseMs(null, now))
        assertEquals(0L, RetryAfter.parseMs("Wed, 21 Oct 2015 07:28:00 GMT", now), "a date in the past means now")
        assertEquals(RetryAfter.MAX_MS, RetryAfter.parseMs("99999999999999999999999", now))
        assertEquals(RetryAfter.MAX_MS, RetryAfter.parseMs("9000000", now))
    }
}

class FailureClassifierTest {
    @Test fun botChallengeGenericLoginAndAgeGateStayDistinct() {
        assertEquals(FailureCategory.VERIFICATION_CHALLENGE,
            FailureClassifier.classify(SignInConfirmNotBotException("Sign in to confirm that you're not a bot")).category)
        assertEquals(FailureCategory.LOGIN_REQUIRED,
            FailureClassifier.classify(Exception("got error LOGIN_REQUIRED: \"This video may be inappropriate\"")).category)
        assertEquals(FailureCategory.RESTRICTED,
            FailureClassifier.classify(org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException("age")).category)
        assertEquals(FailureCategory.CONTENT_UNAVAILABLE,
            FailureClassifier.classify(org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException("gone")).category)
    }

    @Test fun causesAreFollowedToTheSpecificReason() {
        val wrapped = RuntimeException("extraction failed", java.io.IOException("io", RateLimitedException(30_000L)))
        val failure = FailureClassifier.classify(wrapped)
        assertEquals(FailureCategory.RATE_LIMITED, failure.category)
        assertEquals(30_000L, failure.retryAfterMs)
        assertEquals(FailureCategory.NETWORK_UNAVAILABLE, FailureClassifier.classify(UnknownHostException("www.youtube.com")).category)
        assertEquals(FailureCategory.TRANSIENT, FailureClassifier.classify(java.net.SocketTimeoutException("read")).category)
    }

    @Test fun mediaRefusalIsExpiryOnlyWithEvidence() {
        val now = 1_800_000_000_000L
        val expired = "https://rr1---sn-x.googlevideo.com/videoplayback?expire=${now / 1000 - 10}&itag=251"
        val fresh = "https://rr1---sn-x.googlevideo.com/videoplayback?expire=${now / 1000 + 3600}&itag=251"
        assertEquals(FailureCategory.URL_EXPIRED, FailureClassifier.classifyMedia(403, expired, now).category)
        assertEquals(FailureCategory.MEDIA_FORBIDDEN, FailureClassifier.classifyMedia(403, fresh, now).category)
        assertEquals(FailureCategory.RATE_LIMITED, FailureClassifier.classifyMedia(429, fresh, now, "5").category)
        assertEquals(5_000L, FailureClassifier.classifyMedia(429, fresh, now, "5").retryAfterMs)
        assertFalse(FailureCategory.MEDIA_FORBIDDEN.networkWide, "a media 403 must not become a network-wide block")
    }
}

class YouTubeCooldownTest {
    private val clock = FakeClock()
    private fun cooldown(file: File? = null) = YouTubeCooldown(clock, { file }, Random(1))

    @Test fun retryAfterIsHonouredAndOnlyOneTrialFollows() {
        val c = cooldown()
        c.trip(YouTubeCooldown.Cause.RATE_LIMITED, retryAfterMs = 120_000L)
        assertTrue(c.active)
        assertFalse(c.permit())
        clock.advance(119_000L)
        assertFalse(c.permit(), "Retry-After longer than the backoff schedule wins")
        clock.advance(2_000L)
        assertTrue(c.permit(), "one trial when the pause ends")
        repeat(5) { assertFalse(c.permit(), "every other caller keeps waiting while the trial runs") }
        assertTrue(c.active)
        c.recordSuccess()
        assertFalse(c.active)
        assertTrue(c.permit())
    }

    @Test fun anAbandonedTrialFreesTheSlotAfterItsLease() {
        val c = cooldown()
        c.trip(YouTubeCooldown.Cause.TRANSIENT)
        clock.advance(70_000L)
        assertTrue(c.permit())
        assertFalse(c.permit())
        clock.advance(YouTubeCooldown.TRIAL_LEASE_MS + 1)
        assertTrue(c.permit())
    }

    @Test fun repeatedFailuresBackOffWithinTheirCap() {
        val c = cooldown()
        var previous = 0L
        repeat(6) {
            c.trip(YouTubeCooldown.Cause.RATE_LIMITED)
            val wait = c.retryInMs()
            assertTrue(wait <= 66_000L, "rate-limit backoff is capped near 60s (was $wait)")
            assertTrue(wait >= previous * 9 / 10 || wait >= 54_000L)
            previous = wait
            clock.advance(wait + 1)
            assertTrue(c.permit())
        }
        c.trip(YouTubeCooldown.Cause.VERIFICATION)
        assertTrue(c.retryInMs() >= 4 * 60_000L, "a verification challenge has its own, longer pause")
    }

    @Test fun transientFailuresPauseOnlyAfterARun() {
        val c = cooldown()
        c.noteTransient(); c.noteTransient()
        assertFalse(c.active)
        c.noteTransient()
        assertTrue(c.active)
    }

    @Test fun aSearchAnsweringDoesNotEndAVerificationPause() {
        val c = cooldown()
        c.trip(YouTubeCooldown.Cause.VERIFICATION)
        c.recordSuccess(fromPlayer = false)
        assertTrue(c.active)
        c.recordSuccess(fromPlayer = true)
        assertFalse(c.active)
    }

    @Test fun pausePersistsAcrossRestartAndClockJumpsCannotLockForever() {
        val file = File.createTempFile("cooldown", ".properties").apply { delete(); deleteOnExit() }
        cooldown(file).trip(YouTubeCooldown.Cause.RATE_LIMITED, retryAfterMs = 600_000L)
        assertTrue(file.isFile)
        assertFalse(file.readText().contains("http"), "nothing but cause, step and deadline is stored")
        // A new process: monotonic time restarts, wall time continues.
        clock.mono = 5L
        val restored = cooldown(file)
        assertTrue(restored.active)
        assertTrue(restored.retryInMs() in 590_000L..600_000L)
        // The wall clock set back by a day: the remaining wait is bounded, not a day long.
        clock.wall -= 24 * 3600_000L
        val bounded = cooldown(file)
        assertTrue(bounded.retryInMs() <= YouTubeCooldown.MAX_PERSISTED_MS)
        // In-process waits use the monotonic clock: a wall jump forward doesn't end them.
        clock.wall += 48 * 3600_000L
        assertTrue(restored.active)
        restored.recordSuccess()
        assertFalse(file.exists())
    }

    @Test fun corruptOrForeignStateIsIgnored() {
        val file = File.createTempFile("cooldown", ".properties").apply { deleteOnExit() }
        file.writeText("version=1\nscope=youtube-control\ncause=NOT_A_CAUSE\nuntilWall=abc\n")
        assertFalse(cooldown(file).active)
        assertFalse(file.exists(), "an unreadable state file is removed")
        file.writeText("version=99\nscope=youtube-control\ncause=RATE_LIMITED\nuntilWall=${clock.wall + 60_000}\nstep=0\n")
        assertFalse(cooldown(file).active)
    }
}
