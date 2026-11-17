package com.auralis.music.windows.player

import java.io.File

/** Tests load the same bundled Mac runtime as the release, rather than a Windows installation. */
internal fun testVlcDirectory(): File = System.getProperty("auralis.test.vlcDir")?.let(::File)
    ?: File(System.getenv("LOCALAPPDATA") ?: File(System.getProperty("user.home"), "AppData/Local").path,
        "Auralis/app/resources/vlc")
