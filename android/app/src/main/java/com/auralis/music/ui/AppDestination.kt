package com.auralis.music.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.ui.graphics.vector.ImageVector
import com.auralis.music.ui.components.MoodGenreNavIcon

enum class AppDestination(@androidx.annotation.StringRes private val labelRes: Int, val icon: ImageVector) {
    HOME(com.auralis.music.R.string.home, Icons.Outlined.Explore),
    EXPLORE(com.auralis.music.R.string.search, Icons.Default.Search),
    LIBRARY(com.auralis.music.R.string.library, Icons.Default.GridView);

    /** Read at display time so it follows the app language. */
    val label: String get() = com.auralis.music.ui.i18n.str(labelRes)
}

val AppDestinations = AppDestination.values()
