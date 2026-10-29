package com.auralis.music.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.rememberPageHeaderGlass
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Page cards sample only this background layer, never the cards or their scrolling text. */
private val LocalPageCardGlass = staticCompositionLocalOf<LiquidGlassContext?> { null }

@Composable
internal fun pageGlassEnabled(): Boolean = LocalPageCardGlass.current != null

@Composable
internal fun OptionalGlassPage(background: Color, accent: Color, modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val glass = rememberPageHeaderGlass(backdrop)
    CompositionLocalProvider(LocalPageCardGlass provides glass) {
        Box(modifier) {
            if (glass != null) Box(Modifier.fillMaxSize().layerBackdrop(backdrop).drawWithCache {
                val top = Brush.radialGradient(listOf(accent.copy(alpha=.24f),Color.Transparent),
                    Offset(size.width*.12f,size.height*.12f),size.width*.95f)
                val bottom = Brush.radialGradient(listOf(accent.copy(alpha=.14f),Color.Transparent),
                    Offset(size.width*.95f,size.height*.72f),size.width*1.1f)
                onDrawBehind { drawRect(background); drawRect(top); drawRect(bottom) }
            })
            content()
        }
    }
}

@Composable
internal fun Modifier.pageCardSurface(shape: CornerBasedShape, color: Color, border: Color): Modifier {
    val glass=LocalPageCardGlass.current
    return if (glass == null) clip(shape).background(color).border(1.dp,border,shape)
    else liquidGlass(glass,shape,tint=glassSurfaceTint(glass.isDark).copy(alpha=if(glass.isDark) .30f else .48f),
        blurRadius=20.dp,lensDepth=8.dp)
}

/** Keeps the existing Material surface byte-for-byte in the off branch. */
@Composable
internal fun OptionalGlassCard(shape: CornerBasedShape, color: Color, border: BorderStroke?,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val glass=LocalPageCardGlass.current
    Surface(shape=shape, color=if(glass == null) color else Color.Transparent,
        border=if(glass == null) border else null,
        modifier=if(glass == null) modifier else modifier.liquidGlass(glass,shape,
            tint=glassSurfaceTint(glass.isDark).copy(alpha=if(glass.isDark) .30f else .48f),
            blurRadius=20.dp,lensDepth=8.dp),content=content)
}

@Composable
internal fun Modifier.pageGlassSelection(shape: CornerBasedShape, selected: Boolean, fallback: Color,
    accent: Color, enabled: Boolean = true): Modifier {
    val glass=LocalPageCardGlass.current
    return if(glass == null || !selected) clip(shape).background(fallback)
    else liquidGlass(glass,shape,tint=accent.copy(alpha=if(glass.isDark) .24f else .18f),
        press=if(enabled) rememberGlassPress() else null,pressedScale=1.025f,lensDepth=12.dp)
}

@Composable
internal fun pageGlassActionColor(fallback: Color = Color.Black): Color = LocalPageCardGlass.current?.let { glassContentColor(it.isDark) } ?: fallback

@Composable
internal fun pageGlassButtonFill(fallback: Color): Color = if(LocalPageCardGlass.current == null) fallback else Color.Transparent
