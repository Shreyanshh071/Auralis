package com.auralis.music.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str

/**
 * The Windows page title: back arrow and a large bold heading on the page's own background, so
 * no tinted bar or divider sits above the content.
 */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(width = 36.dp, height = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, str(Strings.back), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(title, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground)
    }
}
