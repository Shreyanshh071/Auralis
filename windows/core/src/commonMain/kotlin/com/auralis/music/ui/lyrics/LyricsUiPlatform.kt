package com.auralis.music.ui.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.lazy.LazyListState
import com.auralis.music.domain.model.Track

/** The lyrics view's text, in the app's language. */
class LyricsUiStrings(
    val noLyricsAvailable: () -> String,
    val unsyncedLyrics: () -> String,
    val selectUpTo5Lines: () -> String,
    val search: () -> String,
    val searchSyncedLyrics: () -> String,
    val reSync: () -> String,
    val cancelSelection: () -> String,
    val share: () -> String
)

/**
 * The few platform pieces the shared lyrics view needs: each app supplies its own (the phone its
 * string resources, Toast, back handling, share sheet and romanization setting).
 */
class LyricsUiPlatform(
    val strings: LyricsUiStrings,
    /** A short message, like a Toast. */
    val showMessage: (String) -> Unit = {},
    /** Runs [onBack] on the platform's back gesture/button while [enabled]. */
    val backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    /** The share sheet for selected lines; null where sharing isn't available. */
    val shareSheet: (@Composable (track: Track, text: String, onDismiss: () -> Unit) -> Unit)? = null,
    /** Settings → Content → Romanize lyrics: the Latin line under [text]; draws nothing when off. */
    val romanizedLine: @Composable (text: String, color: Color, fontSize: TextUnit, textAlign: TextAlign, modifier: Modifier) -> Unit =
        { _, _, _, _, _ -> },
    /** Desktop mouse targets use an inset rounded highlight instead of a touch ripple. */
    val roundedMouseHover: Boolean = false,
    /** Platform wheel handling, independent of lyric row heights and synchronization format. */
    val manualScrollModifier: @Composable (LazyListState, onUserScroll: () -> Unit) -> Modifier = { _, _ -> Modifier },
    /** Whether holding a lyric may enter the multi-line sharing selection. */
    val lyricSelectionEnabled: Boolean = true,
    val resyncButton: (@Composable (String, () -> Unit) -> Unit)? = null,
    /** Capture only the scrolling content behind the floating button, never the button itself. */
    val resyncBackdropModifier: @Composable () -> Modifier = { Modifier }
)

/** English text and no platform extras; each app provides its own [LocalLyricsUi]. */
val DefaultLyricsUi = LyricsUiPlatform(
    strings = LyricsUiStrings(
        noLyricsAvailable = { "No lyrics available" },
        unsyncedLyrics = { "Unsynced Lyrics" },
        selectUpTo5Lines = { "Select up to 5 lines for showoff" },
        search = { "Search" },
        searchSyncedLyrics = { "Search Synced Lyrics" },
        reSync = { "Re-sync" },
        cancelSelection = { "Cancel Selection" },
        share = { "Share" }
    )
)

val LocalLyricsUi = staticCompositionLocalOf { DefaultLyricsUi }
