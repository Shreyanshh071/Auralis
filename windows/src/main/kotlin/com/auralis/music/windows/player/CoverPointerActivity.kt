package com.auralis.music.windows.player

import androidx.compose.ui.geometry.Offset

/** Layout/cursor changes can emit synthetic Move events at an unchanged position. */
internal class CoverPointerActivity {
    private var last = Offset.Unspecified
    fun moved(position: Offset): Boolean {
        if (!position.x.isFinite() || !position.y.isFinite()) return false
        if (last == position) return false
        last = position
        return true
    }
    fun exited() { last = Offset.Unspecified }
}
