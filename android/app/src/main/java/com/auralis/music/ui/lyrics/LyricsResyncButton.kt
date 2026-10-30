package com.auralis.music.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.ui.glass.LiquidGlassContext
import com.auralis.music.ui.glass.glassBarTint
import com.auralis.music.ui.glass.glassContentColor
import com.auralis.music.ui.glass.liquidGlass
import com.auralis.music.ui.i18n.str

private val ResyncShape = RoundedCornerShape(32.dp)

/** [glass] samples the lyric list, which the caller records only while this button shows. */
@Composable
internal fun LyricsResyncButton(glass: LiquidGlassContext?, onClick: () -> Unit) {
    val content = if (glass != null) glassContentColor(glass.isDark) else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(
                if (glass != null) {
                    // Lyrics pass right behind this: a heavy blur melts them without tinting the glass.
                    Modifier.liquidGlass(glass, ResyncShape, tint = glassBarTint(glass.isDark), blurRadius = 26.dp)
                        .clip(ResyncShape)
                } else {
                    Modifier
                        .clip(ResyncShape)
                        .background(Color(0xFF1E1B18).copy(alpha = 0.92f))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), ResyncShape)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = str(R.string.re_sync),
            tint = content,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = str(R.string.re_sync),
            color = content,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
