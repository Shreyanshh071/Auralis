package com.auralis.music.windows.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
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

private fun LyricsProvider.label() = LyricsClient.settingsId(this)?.displayName ?: name

private val RowHeight = 58.dp
private val RowGap = 6.dp

/** A number that sits in the optical middle of its circle (the font's line box would push it down). */
private val RankStyle = TextStyle(
    fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
)

/**
 * Settings → Playback → Lyrics providers: one row showing the current top of the order. It opens a
 * glass sheet where every provider (all of them stay on) is ranked by dragging or the arrows.
 */
@Composable
internal fun LyricsProviderOrder() {
    val prefs = DesktopPreferences.store
    val revision by prefs.changes.collectAsState()
    val order = remember(revision) { lyricsProviderOrder(prefs.text("lyricsPriority")) }
    var open by remember { mutableStateOf(false) }
    SettingsGroup("Lyrics providers") {
        SettingsHoverSurface(
            onClick = { open = true }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().settingJumpTarget("Provider order")
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(desktopText("Provider order"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(order.take(3).joinToString("  ›  ") { it.label() } + "  ›  …",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(desktopText("Rearrange"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (open) ProviderOrderSheet(onDismiss = { open = false })
}

@Composable
private fun ProviderOrderSheet(onDismiss: () -> Unit) {
    val prefs = DesktopPreferences.store
    val revision by prefs.changes.collectAsState()
    val savedText = remember(revision) { prefs.text("lyricsPriority") }
    var order by remember(savedText) { mutableStateOf(lyricsProviderOrder(savedText)) }
    fun save(next: List<LyricsProvider>) {
        order = next
        prefs.set("lyricsPriority", next.joinToString(",") { it.name })
    }
    GlassSheet(desktopText("Lyrics providers"), onDismiss, sheetWidth = 460.dp, maxSheetHeight = 640.dp, trailing = {
        if (savedText.isNotBlank()) TextButton({ prefs.set("lyricsPriority", "") }) {
            Text(desktopText("Reset"), color = Color.White.copy(alpha = .85f))
        }
    }) {
        Text(desktopText("Auralis asks providers from the top down. Drag a provider to change the order."),
            Modifier.padding(horizontal = 8.dp), fontSize = 12.5.sp, color = Color.White.copy(alpha = .62f))
        Spacer(Modifier.height(12.dp))
        val step = with(LocalDensity.current) { (RowHeight + RowGap).toPx() }
        var dragging by remember { mutableStateOf<LyricsProvider?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(RowGap)) {
            order.forEachIndexed { index, provider ->
                key(provider) {
                    val isDragged = dragging == provider
                    val lift by animateFloatAsState(if (isDragged) 1f else 0f, label = "lift")
                    ProviderRow(
                        provider = provider, rank = index + 1,
                        canMoveUp = index > 0, canMoveDown = index < order.lastIndex,
                        onMoveUp = { save(moved(order, index, index - 1)) },
                        onMoveDown = { save(moved(order, index, index + 1)) },
                        lifted = lift,
                        modifier = Modifier.zIndex(if (isDragged) 1f else 0f).graphicsLayer {
                            translationY = if (isDragged) dragOffset else 0f
                            val scale = 1f + .02f * lift
                            scaleX = scale; scaleY = scale
                        },
                        handle = Modifier.pointerInput(provider) {
                            detectDragGestures(
                                onDragStart = { dragging = provider; dragOffset = 0f },
                                onDragEnd = { dragging = null; dragOffset = 0f; save(order) },
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
    onMoveUp: () -> Unit, onMoveDown: () -> Unit, lifted: Float, modifier: Modifier, handle: Modifier
) {
    val info = LyricsClient.settingsId(provider)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(18.dp)
    val fill = .06f + (if (hovered) .03f else 0f) + .08f * lifted
    Row(
        modifier.fillMaxWidth().height(RowHeight).clip(shape).background(Color.White.copy(alpha = fill), shape)
            .hoverable(interaction).padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(handle.size(34.dp, RowHeight).pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.DragIndicator, desktopText("Drag to reorder %1\$s", provider.label()),
                Modifier.size(20.dp), tint = Color.White.copy(alpha = .55f))
        }
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Text("$rank", style = RankStyle, color = Color.White)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(provider.label(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            info?.description?.let {
                Text(desktopText(it), fontSize = 12.sp, color = Color.White.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ArrowButton(Icons.Outlined.KeyboardArrowUp, desktopText("Move up"), canMoveUp, onMoveUp)
        ArrowButton(Icons.Outlined.KeyboardArrowDown, desktopText("Move down"), canMoveDown, onMoveDown)
    }
}

@Composable
private fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, Modifier.size(20.dp), tint = Color.White.copy(alpha = if (enabled) .85f else .25f)) }
}
