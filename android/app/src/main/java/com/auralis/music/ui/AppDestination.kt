package com.auralis.music.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.ui.graphics.vector.ImageVector
import com.auralis.music.ui.components.MoodGenreNavIcon

enum class AppDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Explore),
    EXPLORE("Search", Icons.Default.Search),
    LIBRARY("Library", Icons.Default.GridView)
}

val AppDestinations = AppDestination.values()
