package com.auralis.music.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.io.File

internal data class MacVlcPaths(val libraries: File, val plugins: File, val data: File, val bundled: Boolean)

/** An explicitly supplied bundle must stand on its own; never conceal a broken release with VLC.app. */
internal fun macVlcPaths(bundled: File?, installed: File = File("/Applications/VLC.app/Contents/MacOS")): MacVlcPaths? {
    val libraries = bundled ?: File(installed, "lib")
    val plugins = File(bundled ?: installed, "plugins")
    if (!File(libraries, "libvlc.dylib").isFile || !File(libraries, "libvlccore.dylib").isFile || !plugins.isDirectory) return null
    return MacVlcPaths(libraries.canonicalFile, plugins.canonicalFile, File(bundled ?: installed, "share"), bundled != null)
}

internal fun configureMacVlc(paths: MacVlcPaths) {
    check(System.getProperty("os.name").orEmpty().lowercase().contains("mac"))
    val libc = Native.load("System.B", MacVlcEnvironment::class.java)
    check(libc.setenv("VLC_PLUGIN_PATH", paths.plugins.absolutePath, 1) == 0)
    if (paths.data.isDirectory) check(libc.setenv("VLC_DATA_PATH", paths.data.absolutePath, 1) == 0)
    System.setProperty("VLC_PLUGIN_PATH", paths.plugins.absolutePath)
    NativeLibrary.addSearchPath("vlc", paths.libraries.absolutePath)
    NativeLibrary.addSearchPath("vlccore", paths.libraries.absolutePath)
    // Absolute dlopen plus relocated @loader_path links prevents discovery of a build-host VLC.
    NativeLibrary.getInstance(File(paths.libraries, "libvlccore.dylib").absolutePath)
    NativeLibrary.getInstance(File(paths.libraries, "libvlc.dylib").absolutePath)
}

private interface MacVlcEnvironment : Library {
    fun setenv(name: String, value: String, overwrite: Int): Int
}
