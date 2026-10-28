package com.auralis.music.windows.library

import java.io.File
import com.auralis.music.ui.components.WindowsFilePicker

/** Explorer folder chooser shared with every other desktop file dialog. Cancellation stays cancelled. */
internal object NativeFolderPicker {
    suspend fun chooseFolder(initialDirectory: File?, title: String = "Choose download folder"): File? =
        WindowsFilePicker.choose(title, folder = true, initialDirectory = initialDirectory)
}