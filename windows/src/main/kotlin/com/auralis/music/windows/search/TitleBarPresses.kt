package com.auralis.music.windows.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Counts presses on the title bar's drag area, which Windows handles before Compose sees them. */
internal object TitleBarPresses {
    var count by mutableIntStateOf(0)
}
