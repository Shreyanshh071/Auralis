package com.auralis.music

import android.util.Log
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Measures the actual phone network race, without changing saved lyrics or playing music. */
class LyricsStartupSpeedDeviceTest {
    @Test fun stereoHeartsPublishesUsableLyricsBeforeSlowSourcesFinish() = runBlocking {
        val first=CompletableDeferred<LyricsData>()
        val start=System.nanoTime()
        fun offer(data: LyricsData?) {
            if(data != null && data.lines.isNotEmpty() && data.syncType != SyncType.PLAIN) first.complete(data)
        }
        val lookup=launch(Dispatchers.IO) {
            offer(LyricsClient().getLyrics("Stereo Hearts","Gym Class Heroes",210,
                durationMs=210000,onInterim={offer(it)}))
        }
        try {
            val data=withTimeout(20_000) { first.await() }
            val elapsed=(System.nanoTime()-start)/1_000_000
            Log.i("AuralisLyricsSpeed","Stereo Hearts: first timed lyrics ${elapsed}ms; provider=${data.provider}; lines=${data.lines.size}")
            assertTrue(data.lines.isNotEmpty())
        } finally { lookup.cancelAndJoin() }
    }
}
