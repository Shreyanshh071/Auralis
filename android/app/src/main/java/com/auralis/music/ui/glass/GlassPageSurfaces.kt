package com.auralis.music.ui.glass

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import com.auralis.music.ui.components.tactileBounce
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.rememberPageHeaderGlass
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Page cards sample only this background layer, never the cards or their scrolling text. */
private val LocalPageCardGlass = staticCompositionLocalOf<LiquidGlassContext?> { null }

/** The page's painted glow, for anything that has to match it (see [PageGlowTopFade]). */
@Stable
private class PageGlow {
    var enabled = false
    var image by mutableStateOf<ImageBitmap?>(null)
    var pageSize by mutableStateOf(IntSize.Zero)
}

private val LocalPageGlow = staticCompositionLocalOf<PageGlow?> { null }

/**
 * Paints the glow once, at a quarter of the page's size. Full-screen gradients are re-shaded on
 * every scroll frame; a finished image is a single texture draw.
 */
private fun renderPageGlow(pageSize: Size, background: Color, accent: Color): ImageBitmap {
    val width = (pageSize.width / 4f).toInt().coerceAtLeast(1)
    val height = (pageSize.height / 4f).toInt().coerceAtLeast(1)
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        drawRect(background)
        drawRect(Brush.radialGradient(listOf(accent.copy(alpha=.24f),Color.Transparent),
            Offset(size.width*.12f,size.height*.12f),size.width*.95f))
        drawRect(Brush.radialGradient(listOf(accent.copy(alpha=.14f),Color.Transparent),
            Offset(size.width*.95f,size.height*.72f),size.width*1.1f))
    }
    return image
}

@Composable
internal fun pageGlassEnabled(): Boolean = LocalPageCardGlass.current != null

@Composable
internal fun OptionalGlassPage(background: Color, accent: Color, modifier: Modifier = Modifier,
    /** Keeps the glow on the normal theme too, for pages that would otherwise read as flat. */
    glowWithoutGlass: Boolean = false,
    /** For pages that start below the status bar: carries the glow up behind it. */
    glowBehindStatusBar: Boolean = false,
    content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val glass = rememberPageHeaderGlass(backdrop)
    val pageGlow = remember { PageGlow() }
    pageGlow.enabled = glass != null || glowWithoutGlass
    if (glowBehindStatusBar) DisposableEffect(pageGlow) {
        onDispose { if (StatusBarGlow.image === pageGlow.image) StatusBarGlow.image = null }
    }
    CompositionLocalProvider(LocalPageCardGlass provides glass, LocalPageGlow provides pageGlow) {
        // Switching the theme rebuilds the page: cards already drawn kept the old look until they
        // next redrew, so the page looked stale until it was scrolled.
        key(glass != null) {
            Box(modifier) {
                if (pageGlow.enabled) Box(Modifier.fillMaxSize()
                    .then(if (glass != null) Modifier.layerBackdrop(backdrop) else Modifier).drawWithCache {
                    val image = renderPageGlow(size, background, accent)
                    val pageSize = IntSize(size.width.toInt(), size.height.toInt())
                    pageGlow.image = image
                    pageGlow.pageSize = pageSize
                    onDrawBehind {
                    // Set on draw: a tab kept alive in the background must take the strip back.
                    if (glowBehindStatusBar) StatusBarGlow.image = image
                    drawImage(image, dstSize = pageSize, filterQuality = FilterQuality.Low)
                }
                })
                content()
            }
        }
    }
}

/** The glow of the page showing below the status bar, for [StatusBarGlowBand] to continue. */
internal object StatusBarGlow {
    var image by mutableStateOf<ImageBitmap?>(null)
}

/** The strip behind the status bar: the top edge of the page's glow, carried up so there's no seam. */
@Composable
internal fun StatusBarGlowBand(modifier: Modifier = Modifier) {
    Box(modifier.drawBehind {
        val image = StatusBarGlow.image ?: return@drawBehind
        drawImage(
            image,
            srcSize = IntSize(image.width, 1),
            dstSize = IntSize(size.width.toInt() + 1, size.height.toInt() + 1),
            filterQuality = FilterQuality.Low
        )
    })
}

/**
 * The round button floating over a page: liquid glass over [backdrop], which must record the list
 * only so the button stays out of it, and a solid circle on the normal theme.
 */
@Composable
internal fun FloatingPageButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    backdrop: com.kyant.backdrop.Backdrop,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val glass = com.auralis.music.ui.components.rememberPageHeaderGlass(backdrop)
    val press = rememberGlassPress()
    val body = if (glass != null) {
        modifier.size(46.dp)
            .liquidGlass(glass, CircleShape, tint = glassBarTint(glass.isDark), press = press, blurRadius = 14.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        modifier.size(46.dp)
            .shadow(6.dp, CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .tactileBounce(scaleDown = 0.88f) { onClick() }
    }
    Box(body, contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

/**
 * A page's top: the round back button in the same spot on every page, any actions across from it,
 * then the title large beneath. [backdrop] records what scrolls behind the buttons, if anything.
 */
@Composable
internal fun PageHeading(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backdrop: com.kyant.backdrop.Backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop(),
    subtitle: String? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            if (onBack != null) FloatingPageButton(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                com.auralis.music.ui.i18n.str(com.auralis.music.R.string.back),
                backdrop,
                onClick = onBack
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            actions()
        }
        androidx.compose.material3.Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp)
        )
        if (subtitle != null) androidx.compose.material3.Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp)
        )
    }
}

/** Lets a list fade out under a pinned bar, in the page's own colours, glow included. */
@Composable
internal fun PageGlowTopFade(height: Dp, background: Color, modifier: Modifier = Modifier) {
    val glow = LocalPageGlow.current?.takeIf { it.enabled }
    val base = modifier.fillMaxWidth().height(height)
    if (glow == null) {
        Box(base.background(Brush.verticalGradient(
            0f to background, .64f to background.copy(alpha = .88f), 1f to Color.Transparent)))
    } else {
        Box(base.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            glow.image?.let { drawImage(it, dstSize = glow.pageSize, filterQuality = FilterQuality.Low) }
            drawRect(
                Brush.verticalGradient(0f to Color.Black, .64f to Color.Black.copy(alpha = .88f), 1f to Color.Transparent),
                blendMode = BlendMode.DstIn
            )
        })
    }
}

@Composable
internal fun Modifier.pageCardSurface(shape: CornerBasedShape, color: Color, border: Color): Modifier {
    val glass=LocalPageCardGlass.current
    return if (glass == null) clip(shape).background(color).border(1.dp,border,shape)
    else liquidGlass(glass,shape,tint=glassSurfaceTint(glass.isDark).copy(alpha=if(glass.isDark) .30f else .48f),
        blurRadius=20.dp,lensDepth=8.dp,rimLens=true)
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
            blurRadius=20.dp,lensDepth=8.dp,rimLens=true),content=content)
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
