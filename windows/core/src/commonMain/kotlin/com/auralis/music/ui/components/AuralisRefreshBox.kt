package com.auralis.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Windows pages scroll normally without a pull-to-refresh gap or indicator. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AuralisRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    indicatorTopInset: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier, content = content)
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun AuralisRefreshBox(
    refresh: suspend () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    indicatorTopInset: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier, content = content)
}
