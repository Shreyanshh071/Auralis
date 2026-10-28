package com.auralis.music.ui.components

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Explorer's current file-open UI, with thumbnails, places and a real image-type filter. */
internal object NativeImagePicker {
    suspend fun choose(owner: Pointer?): File? = WindowsFilePicker.choose("Choose a photo",
        filterName = "Images (JPG, PNG, WebP, GIF, BMP)",
        pattern = "*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp", owner = owner)
}

/** One modern Windows dialog implementation for all file, save and folder choices. */
object WindowsFilePicker {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Auralis photo picker").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    @Structure.FieldOrder("name", "pattern")
    class ImageFilter : Structure() {
        @JvmField var name: WString = WString("Images (JPG, PNG, WebP, GIF, BMP)")
        @JvmField var pattern: WString = WString("*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp")
    }

    suspend fun choose(title: String, folder: Boolean = false, save: Boolean = false,
        filterName: String = "All files", pattern: String = "*.*", defaultName: String? = null,
        defaultExtension: String? = null, initialDirectory: File? = null,
        owner: Pointer? = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?.let { runCatching { Native.getWindowPointer(it) }.getOrNull() }): File? = withContext(dispatcher) {
        val initialized = Ole32.INSTANCE.CoInitializeEx(null, 2).toInt()
        check(initialized >= 0) { "Cannot initialize the Windows photo picker" }
        try {
            val result = PointerByReference()
            check(Ole32.INSTANCE.CoCreateInstance(
                Guid.CLSID(if (save) "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}" else "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"), null, 1,
                Guid.IID(if (save) "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}" else "{D57C7288-D4AD-4768-BE02-9D969532D960}"), result
            ).toInt() >= 0) { "Cannot open the Windows photo picker" }
            val dialog = checkNotNull(result.value)
            try {
                check(call(dialog, 17, WString(title)) >= 0)
                // FOS_NOCHANGEDIR | FORCEFILESYSTEM | PATHMUSTEXIST | FILEMUSTEXIST.
                val options = 0x00000848 or (if (folder) 0x20 else if (save) 0x2 else 0x1000)
                check(call(dialog, 9, options) >= 0)
                val filter = ImageFilter().apply { name = WString(filterName); this.pattern = WString(pattern); write() }
                if (!folder) check(call(dialog, 4, 1, filter.pointer) >= 0)
                if (defaultName != null) check(call(dialog, 15, WString(defaultName)) >= 0)
                if (defaultExtension != null) check(call(dialog, 22, WString(defaultExtension)) >= 0)
                if (initialDirectory?.isDirectory == true) {
                    val item = PointerByReference()
                    val hr = com.sun.jna.NativeLibrary.getInstance("shell32").getFunction("SHCreateItemFromParsingName")
                        .invokeInt(arrayOf(WString(initialDirectory.absolutePath), null,
                            Guid.IID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}"), item))
                    if (hr >= 0 && item.value != null) {
                        try { call(dialog, 12, item.value) } finally { call(item.value, 2) }
                    }
                }
                val shown = call(dialog, 3, owner ?: Pointer.NULL)
                // Cancellation is a normal result; never open a second chooser after it.
                if (shown == 0x800704C7.toInt()) return@withContext null
                check(shown >= 0) { "The Windows photo picker could not open" }
                java.lang.ref.Reference.reachabilityFence(filter)
                val itemResult = PointerByReference()
                check(call(dialog, 20, itemResult) >= 0)
                val item = checkNotNull(itemResult.value)
                try {
                    val pathResult = PointerByReference()
                    check(call(item, 5, 0x80058000.toInt(), pathResult) >= 0)
                    val path = checkNotNull(pathResult.value)
                    try { File(path.getWideString(0)).takeIf { if (folder) it.isDirectory else save || it.isFile } }
                    finally { Ole32.INSTANCE.CoTaskMemFree(path) }
                } finally { call(item, 2) }
            } finally { call(dialog, 2) }
        } finally { Ole32.INSTANCE.CoUninitialize() }
    }

    private fun call(obj: Pointer, slot: Int, vararg args: Any?): Int = Function.getFunction(
        obj.getPointer(0).getPointer(slot * Native.POINTER_SIZE.toLong()), Function.ALT_CONVENTION
    ).invokeInt(arrayOf(obj, *args))
}
