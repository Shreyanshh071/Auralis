package com.auralis.music.windows.library

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Invokes the native Windows Explorer IFileOpenDialog to choose a folder.
 * Falls back to system-styled Swing JFileChooser if native COM fails or on non-Windows.
 */
internal object NativeFolderPicker {
    private val ole32 by lazy { NativeLibrary.getInstance("ole32") }
    private val shell32 by lazy { NativeLibrary.getInstance("shell32") }

    private const val FOS_PICKFOLDERS = 0x00000020
    private const val FOS_FORCEFILESYSTEM = 0x00000040
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()

    fun chooseFolder(initialDirectory: File?, title: String = "Choose download folder"): File? {
        if (System.getProperty("os.name", "").contains("windows", ignoreCase = true)) {
            val nativeChosen = runCatching { chooseFolderWindows(initialDirectory, title) }.getOrNull()
            if (nativeChosen != null) return nativeChosen
        }
        return chooseFolderSwing(initialDirectory, title)
    }

    private fun chooseFolderWindows(initialDirectory: File?, title: String): File? {
        val hrInit = runCatching { Ole32.INSTANCE.CoInitializeEx(null, 2 /* COINIT_APARTMENTTHREADED */).toInt() }.getOrDefault(-1)
        val needsUninit = hrInit >= 0

        try {
            val pDialog = PointerByReference()
            val clsid = Guid.CLSID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}")
            val iid = Guid.IID("{42F85136-DB7E-439C-85F1-E40F45CD35FC}")

            val hrCreate = Ole32.INSTANCE.CoCreateInstance(clsid, null, 1 /* CLSCTX_INPROC_SERVER */, iid, pDialog).toInt()
            if (hrCreate < 0) return null

            val dialog = pDialog.value ?: return null
            try {
                // Set Title (slot 17)
                call(dialog, 17, WString(title))

                // Get current options (slot 10) & Set options (slot 9)
                val pOptions = Memory(4)
                if (call(dialog, 10, pOptions) >= 0) {
                    val opts = pOptions.getInt(0) or FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM
                    call(dialog, 9, opts)
                }

                // Initial folder (slot 12: SetFolder)
                if (initialDirectory != null && initialDirectory.exists()) {
                    val pInitialItem = PointerByReference()
                    val iidShellItem = Guid.IID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}")
                    val hrItem = shell32.getFunction("SHCreateItemFromParsingName").invokeInt(
                        arrayOf(WString(initialDirectory.absolutePath), null, iidShellItem, pInitialItem)
                    )
                    if (hrItem >= 0 && pInitialItem.value != null) {
                        val initialItem = pInitialItem.value
                        try {
                            call(dialog, 12, initialItem)
                        } finally {
                            call(initialItem, 2 /* Release */)
                        }
                    }
                }

                // Find active window HWND if possible
                val hwndOwner = runCatching {
                    val active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                        ?: java.awt.Window.getWindows().firstOrNull { it.isVisible && it.isFocused }
                    active?.let { com.sun.jna.Native.getWindowPointer(it) }
                }.getOrNull() ?: Pointer.NULL

                // Show (slot 3: Show)
                val hrShow = call(dialog, 3, hwndOwner)
                if (hrShow != 0) {
                    // Cancelled (e.g. 0x800704C7) or closed
                    return null
                }

                // GetResult (slot 20)
                val pResultItem = PointerByReference()
                if (call(dialog, 20, pResultItem) < 0) return null
                val resultItem = pResultItem.value ?: return null

                try {
                    // GetDisplayName (slot 5)
                    val ppszName = PointerByReference()
                    if (call(resultItem, 5, SIGDN_FILESYSPATH, ppszName) < 0) return null
                    val pszName = ppszName.value ?: return null
                    try {
                        val path = pszName.getWideString(0)
                        return if (path.isNotBlank()) File(path) else null
                    } finally {
                        ole32.getFunction("CoTaskMemFree").invokeVoid(arrayOf(pszName))
                    }
                } finally {
                    call(resultItem, 2 /* Release */)
                }
            } finally {
                call(dialog, 2 /* Release */)
            }
        } finally {
            if (needsUninit) runCatching { Ole32.INSTANCE.CoUninitialize() }
        }
    }

    private fun chooseFolderSwing(initialDirectory: File?, title: String): File? {
        return runCatching {
            var result: File? = null
            val action = Runnable {
                runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                val chooser = JFileChooser(initialDirectory).apply {
                    dialogTitle = title
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    result = chooser.selectedFile
                }
            }
            if (SwingUtilities.isEventDispatchThread()) {
                action.run()
            } else {
                SwingUtilities.invokeAndWait(action)
            }
            result
        }.getOrNull()
    }

    private fun call(obj: Pointer, slot: Int, vararg args: Any?): Int = Function.getFunction(
        obj.getPointer(0).getPointer(slot * Native.POINTER_SIZE.toLong()),
        Function.ALT_CONVENTION
    ).invokeInt(arrayOf(obj, *args))
}
