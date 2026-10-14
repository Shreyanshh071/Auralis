package com.auralis.music.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable as foundationClickable
import androidx.compose.foundation.selection.selectable as foundationSelectable
import androidx.compose.foundation.selection.toggleable as foundationToggleable

/*
 * Desktop: anything you can click shows the hand cursor while the pointer is over it, as in a
 * browser. Each control only reports that it is hovered (from the hover signal its own click
 * handling already produces); one cursor setting at the window root shows the hand while any
 * is. Nothing is added to a control's pointer handling, so a small button keeps its enlarged
 * touch area (a cursor modifier on the control itself shrank it and let clicks fall through to
 * the card behind). These keep Compose's and Material's signatures and defaults; the app
 * imports them in place of the originals.
 */

/** How many enabled clickable things the pointer is over right now. */
object HandCursor {
    private val hovered = mutableIntStateOf(0)
    val active: Boolean get() = hovered.intValue > 0
    internal fun enter() { hovered.intValue++ }
    internal fun exit() { hovered.intValue = (hovered.intValue - 1).coerceAtLeast(0) }
}

/** The window root's cursor: the hand while something clickable is hovered. Children that set their own (text fields) keep theirs. */
fun Modifier.handCursorRoot(): Modifier = composed { pointerHoverIcon(if (HandCursor.active) PointerIcon.Hand else PointerIcon.Default) }

/** Reports hover on [source] to [HandCursor] while [enabled]. */
@Composable
internal fun TrackHover(source: MutableInteractionSource, enabled: Boolean) {
    val hovered by source.collectIsHoveredAsState()
    val on = hovered && enabled
    DisposableEffect(on) {
        if (on) HandCursor.enter()
        onDispose { if (on) HandCursor.exit() }
    }
}

/** For controls with their own hover source: shows the hand while [source] is hovered. */
fun Modifier.handCursor(source: MutableInteractionSource, enabled: Boolean = true): Modifier = composed {
    TrackHover(source, enabled); this
}

fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    foundationClickable(source, LocalIndication.current, enabled, onClickLabel, role, onClick)
}

fun Modifier.clickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    foundationClickable(source, indication, enabled, onClickLabel, role, onClick)
}

fun Modifier.toggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    onValueChange: (Boolean) -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    foundationToggleable(value, source, LocalIndication.current, enabled, role, onValueChange)
}

fun Modifier.toggleable(
    value: Boolean,
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    role: Role? = null,
    onValueChange: (Boolean) -> Unit
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    foundationToggleable(value, source, indication, enabled, role, onValueChange)
}

fun Modifier.selectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    foundationSelectable(selected, source, LocalIndication.current, enabled, role, onClick)
}

fun Modifier.selectable(
    selected: Boolean,
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    foundationSelectable(selected, source, indication, enabled, role, onClick)
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    androidx.compose.material3.IconButton(onClick, modifier, enabled, colors, source, content)
}

@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    androidx.compose.material3.FilledIconButton(onClick, modifier, enabled, shape, colors, source, content)
}

@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    androidx.compose.material3.FilledTonalIconButton(onClick, modifier, enabled, shape, colors, source, content)
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, source, content)
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    androidx.compose.material3.TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, source, content)
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, source, content)
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    androidx.compose.material3.FilledTonalButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, source, content)
}

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    androidx.compose.material3.DropdownMenuItem(text, onClick, modifier, leadingIcon, trailingIcon, enabled, colors, contentPadding, source)
}
