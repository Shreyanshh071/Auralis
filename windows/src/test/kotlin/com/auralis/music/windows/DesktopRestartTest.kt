package com.auralis.music.windows

import kotlin.test.*

class DesktopRestartTest {
    @Test fun packagedRestartUsesSameExecutableAndPreservesArguments() {
        val launcher = "C:/Users/Listener's PC/Desktop/Auralis-App/Auralis.exe"
        assertEquals(listOf(launcher, "--custom-option", "--restart-after=42"),
            desktopRestartCommand(launcher, null, emptyList(),
                listOf("--custom-option", "--restart-after=12"), 42))
    }

    @Test fun developmentRestartPreservesJvmOptionsAndClasspath() {
        assertEquals(listOf("java.exe", "-Dauralis.dataDir=custom profile", "-cp", "app.jar;core.jar",
            "com.auralis.music.windows.MainKt", "--restart-after=42"),
            desktopRestartCommand(null, "java.exe", listOf("-Dauralis.dataDir=custom profile",
                "-cp", "app.jar;core.jar", "com.auralis.music.windows.MainKt", "--restart-after=12"), emptyList(), 42))
    }

    @Test fun restartWaitsForOldProcessToExitBeforeContinuing() {
        val parent = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-Command", "Start-Sleep -Milliseconds 500")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            assertTrue(parent.isAlive)
            awaitRestartParent(arrayOf("$RESTART_ARGUMENT${parent.pid()}"))
            assertFalse(parent.isAlive)
        } finally { parent.destroyForcibly() }
    }

    @Test fun normalStartupDoesNotWaitAndSelfWaitIsRejected() {
        awaitRestartParent(emptyArray())
        assertFailsWith<IllegalArgumentException> {
            awaitRestartParent(arrayOf("$RESTART_ARGUMENT${ProcessHandle.current().pid()}"))
        }
    }
}
