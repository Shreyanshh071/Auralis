package com.auralis.music.windows.settings

import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A desktop-only background preference; independent of the phone's appearance settings. */
object DesktopLiquidify {
    private val store by lazy { PropertiesFileKeyValueStore(java.io.File(desktopAppDataDir(), "desktop-background.properties")) }
    private val value by lazy { MutableStateFlow(store.getString("liquidify", "false") == "true") }
    val enabled get() = value.asStateFlow()
    fun setEnabled(enabled: Boolean) {
        store.putStrings(mapOf("liquidify" to enabled.toString()))
        value.value = enabled
    }
}
