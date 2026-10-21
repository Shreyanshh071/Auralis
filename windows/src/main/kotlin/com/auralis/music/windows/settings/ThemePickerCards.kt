package com.auralis.music.windows.settings

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.Switch

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.CuratedPalettes

@Composable
private fun ChoiceGrid(names: List<String>, selected: String, onSelect: (String) -> Unit,
    preview: @Composable (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when { maxWidth >= 680.dp -> 4; maxWidth >= 450.dp -> 3; else -> 2 }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            names.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { name ->
                        val active = selected == name
                        Surface(onClick = { onSelect(name) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(if (active) 2.dp else 1.dp,
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                preview(name)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, minLines = 2)
                                    if (active) Icon(Icons.Rounded.CheckCircle, desktopText("Selected"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun ThemeModeCards(selected: String, onSelect: (String) -> Unit) {
    // Two modes; anything older (Follow system, AMOLED, Midnight Velvet) is shown, and stored, as Dark.
    val current = if (selected == "Light Mode" || selected == "Light") "Light Mode" else "Dark Mode"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(desktopText("Theme mode"), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (mode in listOf("Light Mode", "Dark Mode")) {
                val active = current == mode
                val border by androidx.compose.animation.animateColorAsState(
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "modeBorder")
                Surface(onClick = { onSelect(mode) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(if (active) 2.dp else 1.dp, border)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ModePreview(dark = mode == "Dark Mode", Modifier.fillMaxWidth().height(132.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(desktopText(if (mode == "Light Mode") "Light" else "Dark"), Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium)
                            if (active) Icon(Icons.Rounded.CheckCircle, desktopText("Selected"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A small Auralis window in that mode: sidebar, a row of covers and the player bar. */
@Composable
private fun ModePreview(dark: Boolean, modifier: Modifier) {
    val window = if (dark) Color(0xFF141217) else Color(0xFFF4F2EC)
    val panel = if (dark) Color(0xFF221F26) else Color(0xFFE6E2DA)
    val line = if (dark) Color(0xFF3A3640) else Color(0xFFCFCAC0)
    val cover = listOf(Color(0xFF984EB5), Color(0xFFD39448), Color(0xFF3A647D))
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(window).padding(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.width(56.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(panel).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(4) { Box(Modifier.fillMaxWidth(if (it == 0) .8f else .6f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(line)) }
                }
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(panel).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(.45f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(line))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cover.forEach { Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(it)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(6.dp)).background(panel).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(cover[0]))
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(line))
            }
        }
    }
}

@Composable
internal fun PaletteCards(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(desktopText("Colour palette"), style = MaterialTheme.typography.titleLarge)
        Text(desktopText("Dynamic follows the playing artwork. Choose a swatch for a fixed palette."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceGrid(listOf("White", "Dynamic") + CuratedPalettes.map { it.id }, selected, onSelect) { name ->
            val palette = CuratedPalettes.firstOrNull { it.id == name }
            val colours = if (name == "White") listOf(Color.White, Color(0xFFBDBDBD), Color(0xFF616161)) else palette?.let { listOf(it.previewPrimary, it.previewSecondary, it.previewTertiary) }
                ?: listOf(Color(0xFFBA8BDC), Color(0xFFB4D96B), Color(0xFF71BCCA))
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    drawArc(colours[0], 180f, 180f, true)
                    drawArc(colours[1], 0f, 90f, true)
                    drawArc(colours[2], 90f, 90f, true)
                }
            }
        }
    }
}

@Composable
internal fun BackgroundCards(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(desktopText("Player background"), style = MaterialTheme.typography.titleLarge)
        ChoiceGrid(listOf("Dynamic", "Live Mesh", "Glow motion", "Blur", "Gradient", "Follow theme"), selected, onSelect) { name ->
            val colours = when (name) {
                "Dynamic" -> listOf(Color(0xFF2E5C8A), Color(0xFF8A3A5C), Color(0xFFB0703A), Color(0xFF2E5C8A))
                "Live Mesh" -> listOf(Color(0xFF3B6E9A), Color(0xFFB0563F), Color(0xFF5A2E6E))
                "Glow motion" -> listOf(Color(0xFF050505), Color(0xFF7E4FB5), Color(0xFF050505))
                "Blur" -> listOf(Color(0xFF896E9A), Color(0xFF74595D), Color(0xFF463B58))
                "Gradient" -> listOf(Color(0xFF9979BF), Color(0xFF25222E))
                else -> listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(colours)))
        }
    }
}
