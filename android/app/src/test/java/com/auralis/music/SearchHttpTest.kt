package com.auralis.music

import com.auralis.music.data.network.searchBody
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchHttpTest {
    @Test
    fun deadlineCancelsAnHttpCallThatNeverResponds() = runTest {
        val call = mockk<Call>(relaxed = true)
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } returns call
        every { call.timeout() } returns Timeout()
        var failure: Throwable? = null
        launch {
            try { client.searchBody(Request.Builder().url("https://example.com").build(), 800L) }
            catch (e: Exception) { failure = e }
        }
        advanceUntilIdle()
        assertTrue(failure is TimeoutCancellationException)
        verify(exactly = 1) { call.cancel() }
    }

    @Test
    fun abandoningQueryCancelsHttpImmediately() = runTest {
        val call = mockk<Call>(relaxed = true)
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } returns call
        every { call.timeout() } returns Timeout()
        val job = launch { client.searchBody(Request.Builder().url("https://example.com").build(), 6_000L) }
        runCurrent()
        job.cancel()
        runCurrent()
        verify(exactly = 1) { call.cancel() }
    }
}
