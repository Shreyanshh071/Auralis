package com.auralis.music.ui.lyrics

import androidx.compose.ui.text.PlatformTextStyle

/** Android's `includeFontPadding = false` for tightly set lyric lines; null where it doesn't exist. */
expect fun noFontPaddingStyle(): PlatformTextStyle?
