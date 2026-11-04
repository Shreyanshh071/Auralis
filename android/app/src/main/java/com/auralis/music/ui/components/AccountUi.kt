package com.auralis.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.glass.pageGlassActionColor
import com.auralis.music.ui.glass.pageGlassSelection
import com.auralis.music.ui.theme.dynamicPrimary

internal val AccountCardShape = RoundedCornerShape(26.dp)
internal val AccountButtonShape = CircleShape

/** Kept for the pages that wrap themselves in it: they now follow the app's own colours. */
@Composable
fun AccountPageTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
}

/** A settings page holding one column of panels. */
@Composable
fun AccountPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    com.auralis.music.ui.screens.SettingsPage(title = title, onBack = onBack, modifier = modifier.imePadding()) {
        item(key = "account_page_content", contentType = "content") {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        }
    }
}

@Composable
fun AccountPanel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    com.auralis.music.ui.glass.OptionalGlassCard(
        shape = AccountCardShape,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .07f),
        border = null,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun AccountGlyph(icon: ImageVector, modifier: Modifier = Modifier) {
    AccountGlyph(modifier) { Icon(icon, null, modifier = Modifier.size(20.dp)) }
}

@Composable
fun AccountGlyph(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .09f)),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun AccountBadge(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .09f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun AccountSectionLabel(text: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (detail != null) AccountBadge(detail)
    }
}

@Composable
fun AccountAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    secondary: Boolean = false
) {
    val accent = MaterialTheme.dynamicPrimary
    val content = if (secondary) MaterialTheme.colorScheme.onSurface else pageGlassActionColor(MaterialTheme.colorScheme.onPrimary)
    val usable = enabled && !loading
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .graphicsLayer { alpha = if (usable) 1f else .5f }
            .then(
                if (secondary) Modifier.clip(AccountButtonShape).background(MaterialTheme.colorScheme.onBackground.copy(alpha = .09f))
                else Modifier.pageGlassSelection(AccountButtonShape, true, accent, accent, usable)
            )
            .clickable(enabled = usable, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
        else Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
