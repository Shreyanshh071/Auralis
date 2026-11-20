package com.auralis.music.windows.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.auralis.music.data.network.YouTubeShortsFilter
import com.auralis.music.desktop.library.DesktopListeningStats
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun musicListens(stats: DesktopListeningStats): State<List<DesktopListeningStats.Listen>> =
    produceState(emptyList(), stats) {
        stats.listens.collectLatest { listens ->
            val ids = YouTubeShortsFilter.filter(listens.map { it.track }.distinctBy { it.id }).map { it.id }.toSet()
            value = listens.filter { it.track.id in ids }
        }
    }
