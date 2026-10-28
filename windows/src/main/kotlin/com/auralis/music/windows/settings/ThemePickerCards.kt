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
                        SettingsHoverSurface(onClick = { onSelect(name) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
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
