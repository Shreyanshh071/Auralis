package com.auralis.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.ui.glass.*
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.time.Year

/** Compact entry to the annual recap, coloured by the same live palette as Home. */
@Composable
internal fun WrappedHomeCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val appGlass = LocalLiquidGlass.current
    val accent = MaterialTheme.dynamicPrimary
    val secondary = MaterialTheme.dynamicSecondary
    val surface = MaterialTheme.dynamicSurface
    val foreground = if (appGlass != null) glassContentColor(appGlass.isDark) else MaterialTheme.dynamicOnSurface
    val shape = RoundedCornerShape(18.dp)
    val year = remember { Year.now().value.toString() }

    Box(modifier.fillMaxWidth().clip(shape).testTag("wrapped-home-card")) {
        if (appGlass != null) {
            // This bar lives INSIDE the page's recorded layer. Sampling that page
            // would sample the bar itself. Record a separate palette wash under
            // the lens instead; the live theme supplies its song-derived colour.
            val backdrop = rememberLayerBackdrop()
            val glass = remember(backdrop, appGlass.isDark, appGlass.collapse) {
                LiquidGlassContext(backdrop, appGlass.isDark, appGlass.collapse)
            }
            Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(Brush.linearGradient(
                listOf(lerp(surface, accent, .46f), lerp(surface, secondary, .20f), lerp(surface, accent, .12f)))))
            Box(Modifier.matchParentSize().liquidGlass(glass, shape,
                tint = glassSurfaceTint(glass.isDark), blurRadius = 12.dp, lensDepth = 24.dp))
        } else {
            Box(Modifier.matchParentSize().background(lerp(surface, accent, .06f), shape)
                .border(.75.dp, accent.copy(alpha = .16f), shape))
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.BarChart, null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(year, color = foreground.copy(alpha = .66f), fontSize = 10.sp,
                    lineHeight = 12.sp, fontWeight = FontWeight.Medium)
                Text(str(R.string.your_wrapped), color = foreground, fontSize = 15.sp,
                    lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(str(R.string.wrapped_home_caption), color = foreground.copy(alpha = .76f),
                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, null, tint = foreground.copy(alpha = .7f), modifier = Modifier.size(18.dp))
        }
    }
}
