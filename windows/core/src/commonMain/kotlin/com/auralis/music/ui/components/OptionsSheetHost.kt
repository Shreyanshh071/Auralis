package com.auralis.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.combinedClickable as foundationCombinedClickable

/**
 * Windows: shows a song, album or playlist options sheet as a menu at the pointer (where the
 * right-click or the ⋮ was) instead of a bottom sheet. Null keeps the phone's bottom sheet.
 */
val LocalOptionsSheetHost = staticCompositionLocalOf<(@Composable (onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit)?> { null }

/** True inside such a menu: it is glass of its own, so the cover backdrop and drag handle are left out. */
val LocalOptionsSheetInMenu = staticCompositionLocalOf { false }

/** The phone's options bottom sheet, or on Windows the same content as a menu at the pointer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState? = null,
    containerColor: Color,
    contentColor: Color,
    dragHandle: @Composable (() -> Unit)? = null,
    shape: Shape,
    content: @Composable ColumnScope.() -> Unit
) {
    val host = LocalOptionsSheetHost.current
    if (host != null) {
        host(onDismissRequest) {
            CompositionLocalProvider(LocalOptionsSheetInMenu provides true) { Column(content = content) }
        }
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState ?: rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = containerColor,
        contentColor = contentColor,
        dragHandle = dragHandle,
        shape = shape,
        content = content
    )
}

/**
 * Compose's combinedClickable, plus: a right-click does what a long-press does. On the phone a
 * long-press opens a song's, album's or playlist's options; with a mouse that is the right button.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    secondaryClick(if (enabled) onLongClick else null)
        .foundationCombinedClickable(source, indication, enabled, onClickLabel, role, onLongClickLabel, onLongClick, onDoubleClick, onClick)
}

/** As above, without an interaction source. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    TrackHover(source, enabled)
    secondaryClick(if (enabled) onLongClick else null)
        .foundationCombinedClickable(source, androidx.compose.foundation.LocalIndication.current, enabled, onClickLabel, role, onLongClickLabel, onLongClick, onDoubleClick, onClick)
}

/** Runs [onClick] on a right-click (secondary button press). */
fun Modifier.secondaryClick(onClick: (() -> Unit)?): Modifier = if (onClick == null) this else composed {
    val current by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Initial pass: seen before the row's own click handling underneath.
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    current()
                }
            }
        }
    }
}
