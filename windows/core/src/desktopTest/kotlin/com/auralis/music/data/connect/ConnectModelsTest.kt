package com.auralis.music.data.connect

import com.auralis.music.domain.model.RepeatMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConnectModelsTest {
    @Test
    fun sessionSurvivesTheFirestoreRoundTrip() {
        val s = ConnectSession(
            version = 7, ownerId = "a", ownerName = "Pixel", ownerType = ConnectDeviceType.PHONE, epoch = 3, leaseUntilMs = 99,
            track = ConnectTrack("id", "T", "A", "Al", null, "https://x", 200), playing = true, positionMs = 5, positionAtMs = 6,
            durationMs = 200_000, queueRev = 2, queueIndex = 1, shuffle = true, repeat = RepeatMode.ONE,
            context = ConnectContext("playlist", "p1", "Mix"),
            transfer = ConnectTransfer("t", "a", "b", "Laptop", ConnectDeviceType.COMPUTER, 4, TransferStatus.READY, 10, "c"),
            commands = listOf(ConnectCommand("c1", ConnectCommandType.SEEK, "b", 11, positionMs = 42, queueRev = 2)),
            updatedBy = "a"
        )
        val encoded = ConnectCodec.encodeSession(s).mapValues { (_, v) -> if (v === ConnectServerTime) 1234L else v }
        assertEquals(s.copy(updatedAtMs = 1234L), ConnectCodec.decodeSession(encoded))
    }

    @Test
    fun positionExtrapolatesAndFreezesWhenTheOwnerIsGone() {
        val s = ConnectSession(ownerId = "a", leaseUntilMs = 50_000, track = ConnectTrack("i", "t", "a"), playing = true,
            positionMs = 10_000, positionAtMs = 20_000, durationMs = 300_000)
        assertEquals(15_000, s.positionAt(25_000))
        assertEquals(40_000, s.positionAt(90_000), "frozen at the lease end")
        assertEquals(10_000, s.copy(playing = false).positionAt(90_000))
        assertEquals(10_000, s.copy(buffering = true).positionAt(90_000))
    }

    @Test
    fun deviceNamesFallBackAndStayDistinct() {
        val devices = listOf(
            ConnectDevice("1", "", platform = ConnectPlatform.ANDROID, type = ConnectDeviceType.PHONE, createdMs = 1),
            ConnectDevice("2", "  ", platform = ConnectPlatform.ANDROID, type = ConnectDeviceType.PHONE, createdMs = 2),
            ConnectDevice("3", "", platform = ConnectPlatform.WINDOWS, type = ConnectDeviceType.COMPUTER),
            ConnectDevice("4", "Pixel\n8", platform = ConnectPlatform.ANDROID, type = ConnectDeviceType.PHONE),
            ConnectDevice("5", "pixel 8", nickname = "Kitchen", platform = ConnectPlatform.ANDROID, type = ConnectDeviceType.PHONE),
            ConnectDevice("6", "", platform = ConnectPlatform.WEB, type = ConnectDeviceType.BROWSER)
        )
        val labels = ConnectDeviceNames.labels(devices)
        assertEquals("Android phone", labels["1"])
        assertEquals("Android phone (2)", labels["2"])
        assertEquals("Windows computer", labels["3"])
        assertEquals("Pixel 8", labels["4"])
        assertEquals("Kitchen", labels["5"])
        assertEquals("Web browser", labels["6"])
        assertEquals(devices.size, labels.values.toSet().size)
    }

    @Test fun matchingLaptopModelsHaveShortLabelsWithoutIdsOrAccountNames() {
        val first = ConnectDevice("a1b2c3d4", "LOQ 15IRX9 (SHREYANSHH)", platform = ConnectPlatform.WINDOWS, type = ConnectDeviceType.COMPUTER, createdMs = 1)
        val second = first.copy(id = "e5f6a7b8", createdMs = 2)
        val labels = ConnectDeviceNames.labels(listOf(first, second))
        assertEquals("LOQ 15IRX9", labels[first.id])
        assertEquals("LOQ 15IRX9 (2)", labels[second.id])
        assertEquals(labels, ConnectDeviceNames.labels(listOf(second, first)))
        assertTrue(labels.values.none { it.contains("SHREYANSHH") || it.contains("a1b2c3d4") })
    }
    @Test
    fun serverClockUsesTheTightestRoundTrip() {
        var local = 1_000L
        val clock = ServerClock { local }
        clock.sample(serverMs = 6_000, sentLocalMs = 900, ackLocalMs = 1_100) // offset 5000 +- 100
        clock.sample(serverMs = 9_000, sentLocalMs = 0, ackLocalMs = 1_000) // looser: ignored
        assertEquals(5_000, clock.offsetMs)
        local = 2_000
        assertEquals(7_000, clock.now())
        assertNotEquals(0, clock.offsetMs)
    }

    /** The Android app builds the same files from shared/; the two copies must not drift apart. */
    @Test
    fun windowsAndAndroidCopiesAreIdentical() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val names = listOf("ConnectModels.kt", "ConnectEngine.kt", "ConnectDeviceNames.kt")
        for (name in names) {
            val android = File(root, "shared/src/commonMain/kotlin/com/auralis/music/data/connect/$name").readText()
            val windows = File(root, "windows/core/src/commonMain/kotlin/com/auralis/music/data/connect/$name").readText()
            assertTrue(android == windows, "$name differs between shared/ and windows/core/")
        }
    }
}
