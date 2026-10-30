package com.auralis.music.windows.media

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.test.*

class MacMediaControlsTest {

    @Test
    fun testMacMediaControlsIntegrationWithBoringNotchAdapter() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        var lastButtonPressed: DesktopMediaControls.Button? = null
        var lastSeekPosition: Long? = null

        val controls = MacMediaControls.create(
            onButton = { button -> lastButtonPressed = button },
            onSeek = { positionMs -> lastSeekPosition = positionMs }
        )

        assertNotNull(controls, "MacMediaControls should initialize successfully on macOS")

        try {
            // 1. Update metadata & status
            controls.setMetadata(
                title = "Starlight",
                artist = "Muse",
                artworkUrl = null,
                durationSeconds = 240.0,
                elapsedSeconds = 15.0
            )
            controls.setStatus(DesktopMediaControls.Status.PLAYING)
            controls.setCanGoNext(true)
            controls.setCanGoPrevious(true)

            // 2. Query Boring Notch's MediaRemoteAdapter
            val adapterFile = java.io.File("/Applications/boringNotch.app/Contents/Resources/mediaremote-adapter.pl")
            if (adapterFile.exists()) {
                val getProcess = ProcessBuilder(
                    "/usr/bin/perl",
                    adapterFile.absolutePath,
                    "/Applications/boringNotch.app/Contents/Frameworks/MediaRemoteAdapter.framework",
                    "get"
                ).start()

                val output = BufferedReader(InputStreamReader(getProcess.inputStream)).readText()
                getProcess.waitFor()

                println("Boring Notch Adapter Output (Playing): $output")
                assertTrue(output.contains("Starlight"), "Output must contain track title: $output")
                assertTrue(output.contains("Muse"), "Output must contain artist: $output")
                assertTrue(output.contains("\"playing\":true") || output.contains("\"playbackRate\":1"), "Output must indicate playing: $output")

                // 3. Test sending command from Boring Notch
                val sendProcess = ProcessBuilder(
                    "/usr/bin/perl",
                    adapterFile.absolutePath,
                    "/Applications/boringNotch.app/Contents/Frameworks/MediaRemoteAdapter.framework",
                    "send",
                    "2" // kMRATogglePlayPause
                ).start()
                sendProcess.waitFor()

                Thread.sleep(300)
                assertNotNull(lastButtonPressed, "Boring Notch command should trigger onButton callback")

                // 4. Test paused state
                controls.setStatus(DesktopMediaControls.Status.PAUSED)
                val getPausedProcess = ProcessBuilder(
                    "/usr/bin/perl",
                    adapterFile.absolutePath,
                    "/Applications/boringNotch.app/Contents/Frameworks/MediaRemoteAdapter.framework",
                    "get"
                ).start()
                val pausedOutput = BufferedReader(InputStreamReader(getPausedProcess.inputStream)).readText()
                getPausedProcess.waitFor()

                println("Boring Notch Adapter Output (Paused): $pausedOutput")
                assertTrue(pausedOutput.contains("\"playing\":false") || pausedOutput.contains("\"playbackRate\":0"), "Output must indicate paused: $pausedOutput")
            }
        } finally {
            controls.clear()
            controls.close()
        }
    }
}
