package com.auralis.music.windows.dev

import com.auralis.music.windows.media.GameOverlayOptOut
import com.sun.jna.Memory
import com.sun.jna.Pointer

/** Read-only diagnostics for this executable's effective driver overrides. Never saves settings. */
fun main() {
    val owner = GameOverlayOptOut
    val sessionType = Class.forName("com.auralis.music.windows.media.GameOverlayOptOut\$Session")
    val profileMethod = owner.javaClass.getDeclaredMethod("profile", sessionType).apply { isAccessible = true }
    val sessionMethod = owner.javaClass.getDeclaredMethod("session", Function1::class.java).apply { isAccessible = true }
    val result = sessionMethod.invoke(owner, { session: Any ->
        val handle = sessionType.getDeclaredMethod("getHandle").apply { isAccessible = true }.invoke(session) as Pointer
        val profile = profileMethod.invoke(owner, session) as? Pointer
        if (profile == null) println("No Auralis driver profile") else {
            val call = sessionType.getDeclaredMethod("call", Int::class.javaPrimitiveType, Array<Any>::class.java).apply { isAccessible = true }
            val settings = linkedMapOf("AA selector" to 0x107EFC5B, "AA method" to 0x10D773D2,
                "AA replay" to 0x10D48A85, "FXAA" to 0x1074C972, "MFAA" to 0x0098C1AC,
                "G-SYNC override" to 0x10A879CF, "VSync mode" to 0x00A879CF)
            for ((label, id) in settings) Memory(12320).use { setting ->
                setting.clear(); setting.setInt(0, 12320 or (1 shl 16))
                val status = call.invoke(session, 0x73BF8338, arrayOf(handle, profile, id, setting)) as Int
                println("$label: status=$status value=${if (status == 0) setting.getInt(8220).toUInt().toString(16) else "unavailable"}")
            }
        }
        true
    })
    if (result == null) println("No NVIDIA driver")
}
