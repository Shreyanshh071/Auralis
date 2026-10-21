package com.auralis.music.windows.dev

import com.auralis.music.windows.settings.DesktopReleaseCheck

/** Read-only check of the real shared release feed using the desktop updater. */
fun main() {
    val result = DesktopReleaseCheck.check(false)
    check(result.second == null || result.second!!.contains("/releases/tag/windows-v", ignoreCase = true))
    println("Windows version: ${DesktopReleaseCheck.version}")
    println(result.first)
    println("Windows release link: ${result.second ?: "none"}")
}
