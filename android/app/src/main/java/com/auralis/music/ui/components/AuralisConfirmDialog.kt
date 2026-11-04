package com.auralis.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.glass.glassContentColor
import com.auralis.music.ui.glass.liquidGlassDialog
import com.auralis.music.ui.i18n.str

private val DangerRed = Color(0xFFEF4444)

/** The confirmation for anything destructive: real glass on liquid glass, a raised card otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuralisConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector = Icons.Default.DeleteOutline,
    enabled: Boolean = true,
    dismissEnabled: Boolean = enabled,
    content: (@Composable () -> Unit)? = null
) {
    val glass = LocalLiquidGlass.current
    val pageView = LocalView.current
    val shape = RoundedCornerShape(30.dp)
    val foreground = if (glass != null) glassContentColor(glass.isDark) else MaterialTheme.colorScheme.onSurface
    BasicAlertDialog(onDismissRequest = { if (dismissEnabled) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (glass != null) Modifier.liquidGlassDialog(glass, shape, pageView)
                    else Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(60.dp).clip(CircleShape).background(DangerRed.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = DangerRed, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                color = foreground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = foreground.copy(alpha = 0.68f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
            if (content != null) {
                Spacer(Modifier.height(18.dp))
                content()
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(CircleShape)
                        .background(foreground.copy(alpha = 0.10f))
                        .clickable(enabled = dismissEnabled, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text(str(R.string.cancel), color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(CircleShape)
                        .background(if (enabled) DangerRed else DangerRed.copy(alpha = 0.4f))
                        .clickable(enabled = enabled, onClick = onConfirm)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(confirmLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1,
                        softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}
