package com.auralis.music.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.LocalDynamicThemeEnabled

internal val AccountCardShape = RoundedCornerShape(18.dp)
internal val AccountButtonShape = CircleShape

/** Follow the active dynamic scheme, keeping the neutral design when dynamic theme is off. */
@Composable
fun AccountPageTheme(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (LocalDynamicThemeEnabled.current) MaterialTheme.colorScheme else MaterialTheme.colorScheme.copy(
        primary = if (dark) Color(0xFFF4F4F6) else Color(0xFF18191E),
        onPrimary = if (dark) Color(0xFF111215) else Color.White,
        background = if (dark) Color(0xFF090A0C) else Color(0xFFF7F7F9),
        onBackground = if (dark) Color(0xFFF4F4F6) else Color(0xFF18191E),
        surface = if (dark) Color(0xFF18191E) else Color.White,
        onSurface = if (dark) Color(0xFFF4F4F6) else Color(0xFF18191E),
        surfaceVariant = if (dark) Color(0xFF22242B) else Color(0xFFECEEF2),
        surfaceContainerLowest = if (dark) Color(0xFF08090B) else Color.White,
        surfaceContainerLow = if (dark) Color(0xFF131417) else Color(0xFFF3F4F6),
        surfaceContainer = if (dark) Color(0xFF18191E) else Color(0xFFEFF0F3),
        surfaceContainerHigh = if (dark) Color(0xFF22242B) else Color(0xFFE9EBEF),
        surfaceContainerHighest = if (dark) Color(0xFF2B2D35) else Color(0xFFE2E4E9),
        onSurfaceVariant = if (dark) Color(0xFF9BA0AA) else Color(0xFF626673),
        outline = if (dark) Color(0xFF8B8F9A) else Color(0xFF747985),
        outlineVariant = if (dark) Color(0xFF2B2D35) else Color(0xFFDEE0E6)
    )
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

@Composable
fun AccountPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back))
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            actions()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 16.dp)
            .padding(bottomChromePadding(includeNavigationBar = false)),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
fun AccountPanel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier.fillMaxWidth(), shape = AccountCardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun AccountGlyph(icon: ImageVector, modifier: Modifier = Modifier) {
    AccountGlyph(modifier) { Icon(icon, null, modifier = Modifier.size(20.dp)) }
}

@Composable
fun AccountGlyph(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.size(40.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun AccountBadge(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun AccountSectionLabel(text: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 10.sp, letterSpacing = 0.8.sp,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        if (detail != null) AccountBadge(detail)
    }
}

@Composable
fun AccountRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun AccountDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
    Button(onClick = onClick, enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp), shape = AccountButtonShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (secondary) 0.18f else 0.28f),
            contentColor = MaterialTheme.colorScheme.onSurface
        )) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        else Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
