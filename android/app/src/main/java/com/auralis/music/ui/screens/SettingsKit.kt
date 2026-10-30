package com.auralis.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.ui.components.bottomChromePadding
import com.auralis.music.ui.glass.FloatingPageButton
import com.auralis.music.ui.glass.OptionalGlassCard
import com.auralis.music.ui.glass.OptionalGlassPage
import com.auralis.music.ui.glass.PageGlowTopFade
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** What the page being shown is about. Set once by the main page, so every page picks it up. */
internal class SettingsSection(val icon: ImageVector?, val hint: String?)

internal val LocalSettingsSection = compositionLocalOf { SettingsSection(null, null) }

private val BadgeShape = RoundedCornerShape(11.dp)
private val LargeBadgeShape = RoundedCornerShape(18.dp)
private val CardShape = RoundedCornerShape(26.dp)
private val HeaderHeight = 72.dp

/**
 * The layout every settings page shares: a big header that scrolls away, leaving a small title
 * beside the floating back button, with the list fading out beneath them.
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.(backdrop: Backdrop) -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val background = MaterialTheme.dynamicBackground
    val section = LocalSettingsSection.current
    val listBackdrop = rememberLayerBackdrop()
    val listState = rememberLazyListState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = bottomChromePadding().calculateBottomPadding()

    OptionalGlassPage(
        background = background,
        accent = MaterialTheme.dynamicPrimary,
        modifier = modifier.fillMaxSize().background(background),
        glowWithoutGlass = true
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().layerBackdrop(listBackdrop),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = statusBar + HeaderHeight, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item(key = "settings_page_header", contentType = "header") {
                Column(
                    Modifier
                        .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 2.dp)
                        .graphicsLayer {
                            alpha = if (listState.firstVisibleItemIndex > 0) 0f
                                else (1f - listState.firstVisibleItemScrollOffset / 28.dp.toPx()).coerceIn(0f, 1f)
                        }
                ) {
                    section.icon?.let { icon ->
                        SettingsBadge(icon, size = 58.dp, iconSize = 30.dp)
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 32.sp,
                        lineHeight = 36.sp
                    )
                    section.hint?.let { hint ->
                        Spacer(Modifier.height(4.dp))
                        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                }
            }
            content()
        }
        PageGlowTopFade(
            height = statusBar + HeaderHeight,
            background = background,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        FloatingPageButton(
            Icons.AutoMirrored.Filled.ArrowBack,
            str(R.string.back),
            listBackdrop,
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 12.dp),
            onClick = onBack
        )
        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 14.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) { actions(listBackdrop) }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 74.dp, end = 20.dp, top = 23.dp)
                .graphicsLayer {
                    val scrolled = if (listState.firstVisibleItemIndex > 0) 1f
                        else ((listState.firstVisibleItemScrollOffset - 28.dp.toPx()) / 24.dp.toPx()).coerceIn(0f, 1f)
                    alpha = scrolled
                    translationY = (1f - scrolled) * 8.dp.toPx()
                }
        )
    }
}

@Composable
internal fun SettingsBadge(icon: ImageVector, size: Dp = 36.dp, iconSize: Dp = 20.dp) {
    Box(
        Modifier
            .size(size)
            .clip(if (size > 40.dp) LargeBadgeShape else BadgeShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .09f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(iconSize))
    }
}

/** One card of rows, under a small label. */
@Composable
internal fun SettingsGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        OptionalGlassCard(
            shape = CardShape,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .07f),
            border = null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

/** A large square for the main page: the section's icon, name and what's inside. */
@Composable
internal fun SettingsTile(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OptionalGlassCard(
        shape = CardShape,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .07f),
        border = null,
        modifier = modifier
    ) {
        Column(Modifier.fillMaxWidth().height(136.dp).clickable(onClick = onClick).padding(16.dp)) {
            SettingsBadge(icon, size = 42.dp, iconSize = 23.dp)
            Spacer(Modifier.weight(1f))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The wide card leading the main page. */
@Composable
internal fun SettingsFeatureCard(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    OptionalGlassCard(
        shape = CardShape,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .07f),
        border = null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsBadge(icon, size = 54.dp, iconSize = 28.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SettingsRowIcon(icon: ImageVector, busy: Boolean) {
    if (busy) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.dynamicPrimary, strokeWidth = 2.dp)
        }
    } else {
        SettingsBadge(icon)
    }
}

@Composable
private fun SettingsRowText(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    busy: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(icon, busy)
        Spacer(Modifier.width(14.dp))
        SettingsRowText(title, subtitle, Modifier.weight(1f))
        trailing()
    }
}

/** A row that opens another page. */
@Composable
internal fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    SettingsRow(icon, title, subtitle, onClick = onClick) {
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
internal fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val accent = MaterialTheme.dynamicPrimary
    SettingsRow(icon, title, subtitle, onClick = { onCheckedChange(!checked) }) {
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            thumbContent = if (checked) {
                { Icon(Icons.Default.Check, null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
            } else null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedIconColor = accent,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = .08f),
                uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
    }
}
