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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.ui.components.bottomChromePadding
import com.auralis.music.ui.glass.FloatingPageButton
import com.auralis.music.ui.glass.OptionalGlassCard
import com.auralis.music.ui.glass.OptionalGlassPage
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** The layout every settings page shares: a big title that scrolls away under a floating back button. */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    val background = MaterialTheme.dynamicBackground
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = statusBar + 68.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item(key = "settings_page_title", contentType = "title") {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 30.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                )
            }
            content()
        }
        FloatingPageButton(
            Icons.AutoMirrored.Filled.ArrowBack,
            str(R.string.back),
            listBackdrop,
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 8.dp),
            onBack
        )
    }
}

/** One card of rows, under a small label. */
@Composable
internal fun SettingsGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                text = title,
                color = MaterialTheme.dynamicPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        OptionalGlassCard(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .07f),
            border = null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector, busy: Boolean) {
    val accent = MaterialTheme.dynamicPrimary
    Box(
        Modifier.size(38.dp).background(accent.copy(alpha = .15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
        } else {
            Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
        }
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
        SettingsIcon(icon, busy)
        Spacer(Modifier.width(14.dp))
        SettingsRowText(title, subtitle, Modifier.weight(1f))
        trailing()
    }
}

/** A row that opens another page. */
@Composable
internal fun SettingsNavRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
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
