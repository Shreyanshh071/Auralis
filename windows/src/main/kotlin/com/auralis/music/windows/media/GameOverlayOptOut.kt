package com.auralis.music.windows.media

import com.auralis.music.util.Log
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * Keeps gaming overlays off Auralis, which they take for a game because it draws with OpenGL:
 *  - NVIDIA's overlay showed "Press Alt+Z to open NVIDIA Overlay" at every start (its log called each
 *    start OnGameLaunched). NVIDIA's driver profiles mark apps that aren't games with one flag (Chrome,
 *    VS Code and Blender carry it); Auralis.exe gets a driver profile with the same flag, through
 *    NVAPI's driver settings. NVIDIA then classifies it "StatsShouldNotBeReported" and stays quiet.
 *  - RivaTuner Statistics Server (with MSI Afterburner) hooks every OpenGL app and draws its frame
 *    counter on it; an Auralis.exe profile there turns its hooking off.
 *
 * Both need an administrator (NVAPI_INVALID_USER_PRIVILEGE; RivaTuner's profiles are in Program
 * Files), so it is done while installing: the installer runs Auralis.exe with [SETUP_ARGUMENT]
 * (scripts/add-msi-overlay-step.ps1), which when something is left to change starts itself elevated
 * with [ARGUMENT] (Windows asks, as installers do) and waits for it. Nothing in the app.
 * Windows Game Bar, Steam, Epic and Discord only treat listed games as games, and don't list Auralis.
 */
internal object GameOverlayOptOut {
    const val ARGUMENT = "--game-overlays-off"
    const val SETUP_ARGUMENT = "--game-overlays-setup"
    private const val TAG = "GameOverlays"
    private const val EXE = "Auralis.exe"
    private const val OVERLAY_SETTING = 0x809D5F60.toInt()
    private const val NOT_A_GAME = 0x10000000
    private const val PROFILE_NAME = "Auralis"
    private const val RIVATUNER_PROFILE = "[Hooking]\r\nEnableHooking=0\r\n"

    private const val PROFILE_SIZE = 4116      // NVDRS_PROFILE_V1
    private const val APPLICATION_SIZE = 12296 // NVDRS_APPLICATION_V1
    private const val SETTING_SIZE = 12320     // NVDRS_SETTING_V1
    private const val SETTING_ID = 4100L
    private const val SETTING_CURRENT = 8220L

    private class Session(val handle: Pointer, private val nvapi: (Int, Array<Any?>) -> Int) {
        fun call(id: Int, vararg args: Any?): Int = nvapi(id, arrayOf(*args))
    }

    private fun text(value: String) = Memory(4096).apply { clear(); value.forEachIndexed { i, c -> setChar(i * 2L, c) } }
    private fun struct(size: Int) = Memory(size.toLong()).apply { clear(); setInt(0, size or (1 shl 16)) }

    /** Runs [block] in a loaded driver-settings session; null without an NVIDIA driver. */
    private fun <T> session(block: (Session) -> T): T? {
        val nvapi = runCatching { NativeLibrary.getInstance("nvapi64") }.getOrNull() ?: return null
        val query = nvapi.getFunction("nvapi_QueryInterface")
        val call: (Int, Array<Any?>) -> Int = { id, args ->
            val pointer = query.invokePointer(arrayOf(id)) ?: error("NVAPI function %08X missing".format(id))
            Function.getFunction(pointer).invokeInt(args)
        }
        if (call(0x0150E828, emptyArray()) != 0) return null // NvAPI_Initialize
        val handle = PointerByReference()
        check(call(0x0694D52E, arrayOf(handle)) == 0) { "DRS session unavailable" }
        val session = Session(handle.value, call)
        try {
            check(session.call(0x375DBD6B, handle.value) == 0) { "DRS settings unreadable" }
            return block(session)
        } finally {
            session.call(0xDAD9CFF8.toInt(), handle.value)
        }
    }

    private fun Session.profile(): Pointer? {
        val profile = PointerByReference()
        return if (call(0xEEE566B2.toInt(), handle, text(EXE), profile, struct(APPLICATION_SIZE)) == 0) profile.value else null
    }

    private fun Session.flagSet(profile: Pointer): Boolean {
        val setting = struct(SETTING_SIZE)
        return call(0x73BF8338, handle, profile, OVERLAY_SETTING, setting) == 0 && setting.getInt(SETTING_CURRENT) == NOT_A_GAME
    }

    /** True when an NVIDIA driver is present and Auralis.exe isn't marked yet (read only). */
    private fun nvidiaNeeded(): Boolean = runCatching {
        session { s -> s.profile()?.let { !s.flagSet(it) } ?: true } ?: false
    }.getOrDefault(false)

    /** RivaTuner's profile for Auralis.exe, when RivaTuner is installed. */
    private fun rivaTunerProfile(): java.io.File? = runCatching {
        val dir = com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
            com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\WOW6432Node\\Unwinder\\RTSS", "InstallDir")
        java.io.File(dir, "Profiles").takeIf { it.isDirectory }?.let { java.io.File(it, "$EXE.cfg") }
    }.getOrNull()

    private fun rivaTunerNeeded(): Boolean = rivaTunerProfile()?.let { profile ->
        !(profile.isFile && profile.readText().contains("EnableHooking=0"))
    } ?: false

    /** Something is left to change (read only). */
    fun needed(): Boolean = nvidiaNeeded() || rivaTunerNeeded()

    /** From the installer: when needed, runs the elevated copy and waits for it. Always exits 0. */
    fun setupFromInstaller(): Int {
        runCatching {
            if (!needed()) return 0
            val exe = ProcessHandle.current().info().command().orElse(null)
                ?.takeIf { java.io.File(it).name.equals(EXE, ignoreCase = true) } ?: return 0
            val info = com.sun.jna.platform.win32.ShellAPI.SHELLEXECUTEINFO().apply {
                fMask = 0x00000040 // SEE_MASK_NOCLOSEPROCESS
                lpVerb = "runas"; lpFile = exe; lpParameters = ARGUMENT; nShow = 0
            }
            if (!com.sun.jna.platform.win32.Shell32.INSTANCE.ShellExecuteEx(info)) {
                Log.i(TAG, "Overlay change not approved"); return 0
            }
            com.sun.jna.platform.win32.Kernel32.INSTANCE.WaitForSingleObject(info.hProcess, 60_000)
            com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(info.hProcess)
        }.onFailure { Log.w(TAG, "Overlay setup failed: ${it.message}") }
        return 0
    }

    /** In the elevated copy: every overlay that needs it. Returns the exit code (0 when all done). */
    fun applyElevated(): Int {
        val nvidia = applyNvidia()
        val rivaTuner = runCatching {
            rivaTunerProfile()?.takeIf { rivaTunerNeeded() }?.let { profile ->
                // A profile holds only what differs from RivaTuner's global one.
                profile.writeText(RIVATUNER_PROFILE)
                Log.i(TAG, "RivaTuner hooking off for $EXE")
            }
            0
        }.onFailure { Log.w(TAG, "RivaTuner profile not written: ${it.message}") }.getOrDefault(1)
        return if (nvidia == 0 && rivaTuner == 0) 0 else 1
    }

    /** Marks Auralis.exe in NVIDIA's driver settings and saves them (0 also without an NVIDIA driver). */
    private fun applyNvidia(): Int = runCatching {
        session { s ->
            val profile = s.profile() ?: run {
                val existing = PointerByReference()
                val profile = if (s.call(0x7E4A9A0B, s.handle, text(PROFILE_NAME), existing) == 0) existing.value else {
                    val created = struct(PROFILE_SIZE).apply {
                        PROFILE_NAME.forEachIndexed { i, c -> setChar(4 + i * 2L, c) }
                        setInt(4100, 0x3) // GeForce and Quadro.
                    }
                    val made = PointerByReference()
                    check(s.call(0xCC176068.toInt(), s.handle, created, made) == 0) { "Profile not created" }
                    made.value
                }
                val application = struct(APPLICATION_SIZE).apply { EXE.forEachIndexed { i, c -> setChar(8 + i * 2L, c) } }
                check(s.call(0x4347A9DE, s.handle, profile, application) == 0) { "Application not added" }
                profile
            }
            if (s.flagSet(profile)) return@session 0
            val setting = struct(SETTING_SIZE).apply { setInt(SETTING_ID, OVERLAY_SETTING); setInt(SETTING_CURRENT, NOT_A_GAME) }
            val set = s.call(0x577DD202, s.handle, profile, setting)
            check(set == 0) { "Setting not set (NVAPI status $set)" }
            val saved = s.call(0xFCBC7E14.toInt(), s.handle)
            check(saved == 0) { "Settings not saved (NVAPI status $saved)" }
            Log.i(TAG, "NVIDIA overlay marked off for $EXE")
            0
        } ?: 0
    }.onFailure { Log.w(TAG, "NVIDIA overlay change failed: ${it.message}") }.getOrDefault(1)
}
