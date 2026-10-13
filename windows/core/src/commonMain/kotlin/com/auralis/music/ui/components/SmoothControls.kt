package com.auralis.music.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    SmoothControl(modifier, interactionSource, shape, colors.contentColor, enabled) { draw, source ->
        androidx.compose.material3.Button(onClick = onClick, modifier = draw, enabled = enabled, shape = shape,
            colors = colors, elevation = elevation, border = border, contentPadding = contentPadding,
            interactionSource = source, content = content)
    }
}

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    SmoothControl(modifier, interactionSource, shape, colors.contentColor, enabled) { draw, source ->
        androidx.compose.material3.TextButton(onClick = onClick, modifier = draw, enabled = enabled, shape = shape,
            colors = colors, elevation = elevation, border = border, contentPadding = contentPadding,
            interactionSource = source, content = content)
    }
}

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    SmoothControl(modifier, interactionSource, shape, colors.contentColor, enabled) { draw, source ->
        androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = draw, enabled = enabled, shape = shape,
            colors = colors, elevation = elevation, border = border, contentPadding = contentPadding,
            interactionSource = source, content = content)
    }
}

@Composable
fun FilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    SmoothControl(modifier, interactionSource, shape, colors.contentColor, enabled) { draw, source ->
        androidx.compose.material3.FilledTonalButton(onClick = onClick, modifier = draw, enabled = enabled, shape = shape,
            colors = colors, elevation = elevation, border = border, contentPadding = contentPadding,
            interactionSource = source, content = content)
    }
}

@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null, content: @Composable () -> Unit) {
    SmoothControl(modifier, interactionSource, CircleShape, colors.contentColor, enabled) { draw, source ->
        androidx.compose.material3.IconButton(onClick = onClick, modifier = draw, enabled = enabled, 
            colors = colors, interactionSource = source, content = content)
    }
}

@Composable
fun FilledIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = CircleShape, colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null, content: @Composable () -> Unit) {
    SmoothControl(modifier, interactionSource, shape, colors.contentColor, enabled) { draw, source ->
        androidx.compose.material3.FilledIconButton(onClick = onClick, modifier = draw, enabled = enabled, shape = shape, 
            colors = colors, interactionSource = source, content = content)
    }
}

@Composable
fun FilledTonalIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = CircleShape, colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null, content: @Composable () -> Unit) {
    SmoothControl(modifier, interactionSource, shape, colors.contentColor, enabled) { draw, source ->
        androidx.compose.material3.FilledTonalIconButton(onClick = onClick, modifier = draw, enabled = enabled, shape = shape, 
            colors = colors, interactionSource = source, content = content)
    }
}

@Composable
fun OutlinedIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = CircleShape, border: BorderStroke? = IconButtonDefaults.outlinedIconButtonBorder(enabled), colors: IconButtonColors = IconButtonDefaults.outlinedIconButtonColors(),
    interactionSource: MutableInteractionSource? = null, content: @Composable () -> Unit) {
    SmoothControl(modifier, interactionSource, shape, colors.contentColor, enabled) { draw, source ->
        androidx.compose.material3.OutlinedIconButton(onClick = onClick, modifier = draw, enabled = enabled, shape = shape, border = border, 
            colors = colors, interactionSource = source, content = content)
    }
}

@Composable
fun DropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true, colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null) {
    SmoothControl(modifier, interactionSource, RectangleShape, MaterialTheme.colorScheme.onSurface, enabled) { draw, source ->
        androidx.compose.material3.DropdownMenuItem(text, onClick, draw, leadingIcon, trailingIcon, enabled,
            colors, contentPadding, source)
    }
}

@Composable
fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: CheckboxColors = CheckboxDefaults.colors(), interactionSource: MutableInteractionSource? = null) {
    SmoothControl(modifier, interactionSource, CircleShape, MaterialTheme.colorScheme.onSurface, enabled) { draw, source ->
        androidx.compose.material3.Checkbox(checked, onCheckedChange, draw, enabled, colors, source)
    }
}

@Composable
fun RadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: RadioButtonColors = RadioButtonDefaults.colors(), interactionSource: MutableInteractionSource? = null) {
    SmoothControl(modifier, interactionSource, CircleShape, MaterialTheme.colorScheme.onSurface, enabled) { draw, source ->
        androidx.compose.material3.RadioButton(selected, onClick, draw, enabled, colors, source)
    }
}

@Composable
fun Slider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(), interactionSource: MutableInteractionSource? = null,
    steps: Int = 0, valueRange: ClosedFloatingPointRange<Float> = 0f..1f) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(.00001f)
    val shape = rememberThumbHoverShape(((value - valueRange.start) / span).coerceIn(0f, 1f), 2f)
    SmoothControl(modifier, interactionSource, shape, colors.thumbColor, enabled) { draw, source ->
        androidx.compose.material3.Slider(value = value, onValueChange = onValueChange, modifier = draw,
            enabled = enabled, onValueChangeFinished = onValueChangeFinished, colors = colors,
            interactionSource = source, steps = steps, valueRange = valueRange)
    }
}

@Composable
fun Surface(modifier: Modifier = Modifier, shape: Shape = RectangleShape,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    contentColor: androidx.compose.ui.graphics.Color = contentColorFor(color),
    tonalElevation: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    shadowElevation: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    border: BorderStroke? = null, content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(modifier, shape, color, contentColor, tonalElevation, shadowElevation, border, content)
}

@Composable
fun Surface(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RectangleShape, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    contentColor: androidx.compose.ui.graphics.Color = contentColorFor(color),
    tonalElevation: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    shadowElevation: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    border: BorderStroke? = null, interactionSource: MutableInteractionSource? = null, content: @Composable () -> Unit) {
    SmoothControl(modifier, interactionSource, shape, contentColor, enabled) { draw, source ->
        androidx.compose.material3.Surface(onClick = onClick, modifier = draw, enabled = enabled, shape = shape,
            color = color, contentColor = contentColor, tonalElevation = tonalElevation,
            shadowElevation = shadowElevation, border = border, interactionSource = source, content = content)
    }
}
