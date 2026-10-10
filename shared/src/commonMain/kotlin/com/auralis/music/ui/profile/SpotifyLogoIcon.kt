package com.auralis.music.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SpotifyLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val radius = w / 2f

        // Green Circle Background
        drawCircle(
            color = Color(0xFF1DB954),
            radius = radius,
            center = Offset(w / 2f, h / 2f)
        )

        // 3 Soundwave Arcs (Top, Middle, Bottom)
        // Top Arc
        val topPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.24f, h * 0.38f)
            quadraticTo(w * 0.49f, h * 0.24f, w * 0.75f, h * 0.33f)
        }
        drawPath(
            path = topPath,
            color = Color.Black,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.088f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )

        // Middle Arc
        val midPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.28f, h * 0.52f)
            quadraticTo(w * 0.49f, h * 0.40f, w * 0.72f, h * 0.47f)
        }
        drawPath(
            path = midPath,
            color = Color.Black,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.078f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )

        // Bottom Arc
        val botPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.32f, h * 0.65f)
            quadraticTo(w * 0.49f, h * 0.55f, w * 0.68f, h * 0.61f)
        }
        drawPath(
            path = botPath,
            color = Color.Black,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.068f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
    }
}
