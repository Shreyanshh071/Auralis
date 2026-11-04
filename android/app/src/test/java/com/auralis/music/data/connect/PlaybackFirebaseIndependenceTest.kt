package com.auralis.music.data.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaybackFirebaseIndependenceTest {
    private val firebaseBacked = Regex("com\\.google\\.firebase|StatsCloudSync|GoogleAccountSyncManager|AndroidAccountPreferences|OnboardingStore")

    @Test fun streamingAndDownloadCodeNeverReachesFirebase() {
        val roots = listOf("data/service", "data/network", "data/download")
            .map { File("src/main/java/com/auralis/music/$it") }
            .onEach { assertTrue("$it moved: point this check at the playback code", it.isDirectory) }
        val offenders = roots.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .filter { f -> firebaseBacked.containsMatchIn(f.readText()) }
        assertEquals("Playback must not depend on Firebase", emptyList<File>(), offenders)
    }

    @Test fun theMediaServiceOnlyReadsListenTogethersRoomState() {
        val service = File("src/main/java/com/auralis/music/service/AuralisMediaService.kt").readText()
        assertTrue(!firebaseBacked.containsMatchIn(service))
    }
}
