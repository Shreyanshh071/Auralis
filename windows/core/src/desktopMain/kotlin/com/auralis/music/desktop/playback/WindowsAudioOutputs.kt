package com.auralis.music.desktop.playback

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

data class WindowsAudioOutput(val id: String, val name: String, val isDefault: Boolean)

/** Active render endpoints from MMDevice, independent of a running VLC stream. */
object WindowsAudioOutputs {
    fun read(): List<WindowsAudioOutput> {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return emptyList()
        val initialized = Ole32.INSTANCE.CoInitializeEx(null, 0).toInt()
        check(initialized >= 0 || initialized == 0x80010106.toInt()) { "Audio COM initialization failed" }
        try {
            val reference = PointerByReference()
            check(Ole32.INSTANCE.CoCreateInstance(Guid.CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}"), null, 1,
                Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}"), reference).toInt() >= 0)
            val enumerator = reference.value
            try {
                val default = PointerByReference()
                val defaultId = if (call(enumerator, 4, 0, 1, default) >= 0) {
                    try { deviceId(default.value) } finally { call(default.value, 2) }
                } else null
                val collection = PointerByReference()
                check(call(enumerator, 3, 0, 1, collection) >= 0) // eRender, DEVICE_STATE_ACTIVE
                try {
                    val count = IntByReference()
                    check(call(collection.value, 3, count) >= 0)
                    return (0 until count.value).mapNotNull { index ->
                        val device = PointerByReference()
                        if (call(collection.value, 4, index, device) < 0) null else try {
                            val id = deviceId(device.value)
                            WindowsAudioOutput(id, friendlyName(device.value), id == defaultId)
                        } finally { call(device.value, 2) }
                    }
                } finally { call(collection.value, 2) }
            } finally { call(enumerator, 2) }
        } finally { if (initialized >= 0) Ole32.INSTANCE.CoUninitialize() }
    }

    private fun deviceId(device: Pointer): String {
        val result = PointerByReference()
        check(call(device, 5, result) >= 0)
        return try { result.value.getWideString(0) } finally { Ole32.INSTANCE.CoTaskMemFree(result.value) }
    }

    private fun friendlyName(device: Pointer): String {
        val store = PointerByReference()
        check(call(device, 4, 0, store) >= 0) // STGM_READ
        try {
            Memory(20).use { key ->
                val guid = Guid.GUID("{A45C254E-DF1C-4EFD-8020-67D146A850E0}")
                guid.write()
                key.write(0, guid.pointer.getByteArray(0, 16), 0, 16)
                key.setInt(16, 14) // PKEY_Device_FriendlyName
                Memory(24).use { value ->
                    value.clear()
                    check(call(store.value, 5, key, value) >= 0)
                    try {
                        check(value.getShort(0).toInt() == 31) { "Audio endpoint has no friendly name" } // VT_LPWSTR
                        return value.getPointer(8).getWideString(0)
                    } finally { NativeLibrary.getInstance("ole32").getFunction("PropVariantClear").invokeInt(arrayOf(value)) }
                }
            }
        } finally { call(store.value, 2) }
    }

    private fun call(target: Pointer, slot: Int, vararg args: Any?): Int = Function.getFunction(
        target.getPointer(0).getPointer(slot * Native.POINTER_SIZE.toLong()), Function.ALT_CONVENTION
    ).invokeInt(arrayOf(target, *args))

    /** The blank id is VLC's real follow-Windows-default route, labelled with its endpoint. */
    fun menu(devices: List<WindowsAudioOutput>, vlcDevices: List<Pair<String, String>>): List<Pair<String, String>> {
        val default = devices.firstOrNull { it.isDefault }
        return listOf("" to (default?.let { "Windows default — ${it.name}" } ?: "Windows default")) +
            (if (devices.isNotEmpty()) devices.filterNot { it.isDefault }.map { it.id to it.name }
                else vlcDevices.filter { it.first.isNotBlank() }).distinctBy { it.first }
    }
}
