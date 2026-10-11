package com.auralis.music.windows.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
                                    if (active) Icon(Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleLarge)
        ChoiceGrid(listOf("Follow system", "Light Mode", "Dark Mode", "Pure AMOLED Black", "Midnight Velvet Dark"), selected, onSelect) { name ->
            val background = when (name) { "Light Mode" -> Color(0xFFF4F2EC); "Pure AMOLED Black" -> Color.Black; "Midnight Velvet Dark" -> Color(0xFF191326); else -> Color(0xFF242327) }
            Row(Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(8.dp)).background(background).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.width(18.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (name == "Follow system") Color(0xFFD7D4D0) else Color(0xFF6C6A73)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(.7f).height(7.dp).background(Color(0xFFBDD66A), RoundedCornerShape(3.dp)))
                    repeat(3) { Box(Modifier.fillMaxWidth().height(7.dp).background(if (name == "Light Mode") Color(0xFFCECBCD) else Color(0xFF4C4653), RoundedCornerShape(3.dp))) }
                }
            }
        }
    }
}

@Composable
internal fun LiquidifyCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF984EB5), Color(0xFFD39448), Color(0xFF3A647D)))))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Liquidify", style = MaterialTheme.typography.titleMedium)
                Text("The song's artwork flows across the whole app background, sidebar and player bar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (enabled) "On · changes with each song" else "Off · use your selected theme", style = MaterialTheme.typography.labelSmall)
            }
            Switch(enabled, onChange)
        }
    }
}

@Composable
internal fun PaletteCards(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Colour palette", style = MaterialTheme.typography.titleLarge)
        Text("Dynamic follows the playing artwork. Choose a swatch for a fixed palette.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceGrid(listOf("Dynamic") + CuratedPalettes.map { it.id }, selected, onSelect) { name ->
            val palette = CuratedPalettes.firstOrNull { it.id == name }
            val colours = palette?.let { listOf(it.previewPrimary, it.previewSecondary, it.previewTertiary) }
                ?: listOf(Color(0xFFBA8BDC), Color(0xFFB4D96B), Color(0xFF71BCCA))
            Row(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))) {
                colours.forEach { Box(Modifier.weight(1f).fillMaxHeight().background(it)) }
            }
        }
    }
}

@Composable
internal fun BackgroundCards(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Player background", style = MaterialTheme.typography.titleLarge)
        ChoiceGrid(listOf("Blur", "Gradient", "Follow theme"), selected, onSelect) { name ->
            val colours = when (name) {
                "Blur" -> listOf(Color(0xFF896E9A), Color(0xFF74595D), Color(0xFF463B58))
                "Gradient" -> listOf(Color(0xFF9979BF), Color(0xFF25222E))
                else -> listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(colours)))
        }
    }
}
