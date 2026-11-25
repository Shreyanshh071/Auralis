package com.auralis.music.windows.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.auralis.music.ui.components.reportTypingFocus
import com.auralis.music.ui.i18n.desktopText
import com.auralis.music.windows.LiquidPopupSurface

/** The phone dock's selected-tab bead: a solid 14% white fill with a faint 10% white edge. */
private val BeadFill = Color.White.copy(alpha = .14f)
private val BeadEdge = Color.White.copy(alpha = .10f)

/**
 * A liquid-glass sheet over the window, the same glass as the app's other popups: what is behind
 * it shows through, bent at the edges. [content] lays out under the title.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun GlassSheet(
    title: String,
    onDismiss: () -> Unit,
    sheetWidth: Dp = 420.dp,
    maxSheetHeight: Dp = 560.dp,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    com.auralis.music.windows.MacWindowChrome.ModalOverlayEffect()
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val width = with(density) { size.width.toDp() }
    val height = with(density) { size.height.toDp() }
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .42f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
            LiquidPopupSurface(
                Modifier.widthIn(max = (width - 48.dp).coerceAtLeast(1.dp)).width(sheetWidth)
                    .heightIn(max = minOf(maxSheetHeight, (height - 48.dp).coerceAtLeast(1.dp))),
                tint = Color.White.copy(alpha = .09f), cornerRadius = 28.dp,
                blurDp = 8f, lensHeightDp = 24f, lensAmountDp = 24f, lensBand = .3f, chromaticAberration = 0f,
                shadowElevation = 12.dp
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Column(
                        Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                            .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 14.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            trailing()
                        }
                        Spacer(Modifier.height(14.dp))
                        content()
                    }
                }
            }
        }
    }
}

/** A settings choice in a [GlassSheet]: rows plain on the glass, the chosen one on the bead. [searchable] adds a filter. */
@Composable
internal fun GlassPickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    searchable: Boolean = false,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val languageRevision = com.auralis.music.ui.i18n.StringLookup.revision.intValue
    val shown = remember(filter, options, languageRevision) {
        if (filter.isBlank()) options
        else options.filter { (key, label) ->
            label.contains(filter, true) || key.contains(filter, true) || desktopText(label).contains(filter, true)
        }
    }
    GlassSheet(title, onDismiss) {
        if (searchable) {
            GlassSearchField(filter) { filter = it }
            Spacer(Modifier.height(10.dp))
        }
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = options.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0).let { maxOf(0, it - 2) })
        LazyColumn(Modifier.weight(1f, fill = false), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown, key = { it.first }) { (key, label) ->
                GlassOptionRow(label, key == selectedKey) { onSelect(key); onDismiss() }
            }
        }
        if (shown.isEmpty()) {
            Text(desktopText("No matches"), Modifier.padding(12.dp), color = Color.White.copy(alpha = .6f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun GlassSearchField(value: String, onValue: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(Color.White.copy(alpha = .08f))
            .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(21.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = .6f))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(desktopText("Search"), color = Color.White.copy(alpha = .5f), fontSize = 14.sp)
            BasicTextField(value, onValue, Modifier.fillMaxWidth().focusRequester(focus).reportTypingFocus(), singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Color.White))
        }
    }
}

@Composable
private fun GlassOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape)
            .then(
                when {
                    selected -> Modifier.background(BeadFill, shape).border(1.dp, BeadEdge, shape)
                    hovered -> Modifier.background(Color.White.copy(alpha = .06f), shape)
                    else -> Modifier
                }
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), color = Color.White.copy(alpha = if (selected) 1f else .86f), fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = Color.White)
    }
}
