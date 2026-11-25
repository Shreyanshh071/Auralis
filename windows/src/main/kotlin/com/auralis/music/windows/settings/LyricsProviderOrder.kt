package com.auralis.music.windows.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.ui.i18n.desktopText
import kotlin.math.roundToInt

/** Providers the order covers: the ones the race asks (local files and video captions aren't sources you rank). */
internal val orderableLyricsProviders: List<LyricsProvider> =
    LyricsClient.PICK_ORDER.filter { it != LyricsProvider.LOCAL && it != LyricsProvider.YOUTUBE_CAPTIONS }

/** The saved order completed with any provider it doesn't name yet, in the default order. */
internal fun lyricsProviderOrder(saved: String): List<LyricsProvider> {
    val named = saved.split(',').mapNotNull { name -> runCatching { LyricsProvider.valueOf(name.trim()) }.getOrNull() }
        .filter { it in orderableLyricsProviders }.distinct()
    return named + orderableLyricsProviders.filterNot { it in named }
}

/** [list] with the item at [from] moved to [to]. */
internal fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in list.indices || to !in list.indices) return list
    return list.toMutableList().apply { add(to, removeAt(from)) }
}

private val RowHeight = 64.dp
private val RowGap = 8.dp

/**
 * Lyrics providers, ranked: no switches, every provider stays on and Auralis asks them from the top
 * down. Drag a row by its handle, or use the arrows.
 */
@Composable
internal fun LyricsProviderOrder() {
    val prefs = DesktopPreferences.store
    val revision by prefs.changes.collectAsState()
    val savedText = remember(revision) { prefs.text("lyricsPriority") }
    val custom = savedText.isNotBlank()
    var order by remember(savedText) { mutableStateOf(lyricsProviderOrder(savedText)) }
    fun save(next: List<LyricsProvider>) {
        order = next
        prefs.set("lyricsPriority", next.joinToString(",") { it.name })
    }

    SettingsGroup("Lyrics providers") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(desktopText("Auralis asks providers from the top down. Drag a provider to change the order."),
                Modifier.weight(1f).padding(start = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (custom) TextButton({ prefs.set("lyricsPriority", "") }) { Text(desktopText("Reset order")) }
        }
        val step = with(LocalDensity.current) { (RowHeight + RowGap).toPx() }
        var dragging by remember { mutableStateOf<LyricsProvider?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
            order.forEachIndexed { index, provider ->
                key(provider) {
                    val isDragged = dragging == provider
                    val lift by animateFloatAsState(if (isDragged) 1f else 0f, label = "lift")
                    ProviderRow(
                        provider = provider,
                        rank = index + 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        onMoveUp = { save(moved(order, index, index - 1)) },
                        onMoveDown = { save(moved(order, index, index + 1)) },
                        modifier = Modifier
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragged) dragOffset else 0f
                                val scale = 1f + .02f * lift
                                scaleX = scale; scaleY = scale
                                shadowElevation = 12f * lift
                                shape = RoundedCornerShape(16.dp)
                                clip = false
                            },
                        handle = Modifier.pointerInput(provider) {
                            detectDragGestures(
                                onDragStart = { dragging = provider; dragOffset = 0f },
                                onDragEnd = {
                                    dragging = null; dragOffset = 0f
                                    prefs.set("lyricsPriority", order.joinToString(",") { it.name })
                                },
                                onDragCancel = { dragging = null; dragOffset = 0f; order = lyricsProviderOrder(savedText) }
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                // Crossing half a row swaps with the neighbour; the offset keeps the row under the pointer.
                                val from = order.indexOf(provider)
                                val to = (from + (dragOffset / step).roundToInt()).coerceIn(0, order.lastIndex)
                                if (to != from) {
                                    order = moved(order, from, to)
                                    dragOffset -= (to - from) * step
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: LyricsProvider, rank: Int, canMoveUp: Boolean, canMoveDown: Boolean,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit, modifier: Modifier, handle: Modifier
) {
    val info = LyricsClient.settingsId(provider)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().height(RowHeight)) {
        Row(Modifier.fillMaxSize().padding(start = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(handle.size(36.dp, RowHeight).pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DragIndicator, desktopText("Drag to reorder %1\$s", info?.displayName ?: provider.name),
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)),
                contentAlignment = Alignment.Center) {
                Text("$rank", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(info?.displayName ?: provider.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                info?.description?.let {
                    Text(desktopText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onMoveUp, enabled = canMoveUp, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.KeyboardArrowUp, desktopText("Move up"), Modifier.size(20.dp))
            }
            IconButton(onMoveDown, enabled = canMoveDown, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.KeyboardArrowDown, desktopText("Move down"), Modifier.size(20.dp))
            }
        }
    }
}
