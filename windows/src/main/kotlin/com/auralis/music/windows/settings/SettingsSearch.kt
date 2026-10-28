package com.auralis.music.windows.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.i18n.desktopText
import kotlinx.coroutines.delay

/**
 * One setting as Settings search finds it: the tab and group it sits in, and the title and
 * description it shows (in English; matching also uses the translation). The title is the one
 * the row is drawn with, so jumping to the result finds the row; a setting drawn some other way
 * (theme cards, buttons) leaves [onRow] false and the jump lands on its group's heading.
 */
internal data class SettingEntry(
    val tab: SettingsTab, val group: String, val title: String, val description: String = "",
    val keywords: String = "", val onRow: Boolean = true
)

private fun MutableList<SettingEntry>.tab(tab: SettingsTab, block: TabEntries.() -> Unit) = TabEntries(tab, this).block()

private class TabEntries(val tab: SettingsTab, val into: MutableList<SettingEntry>) {
    fun group(group: String, block: GroupEntries.() -> Unit) = GroupEntries(tab, group, into).block()
}

private class GroupEntries(val tab: SettingsTab, val group: String, val into: MutableList<SettingEntry>) {
    fun row(title: String, description: String = "", keywords: String = "") { into += SettingEntry(tab, group, title, description, keywords) }
    fun other(title: String, description: String = "", keywords: String = "") { into += SettingEntry(tab, group, title, description, keywords, onRow = false) }
}

/** Every setting in the Settings window, in the order the tabs show them. */
internal val settingEntries: List<SettingEntry> = buildList {
    tab(SettingsTab.GENERAL) {
        group("Language") { row("App language", "Applies to translated app text", "translation locale") }
        group("Activity") { row("Play history", "Record songs played on this PC", "listening recents stats") }
        group("System") {
            row("Close to system tray", "Keep playing when the window is closed", "minimise background")
            row("Song notifications", "Show the next song while Auralis is in the background", "toast popup")
            other("Test notification", "Show a song notification now")
            row("Start at login", "Launch Auralis with your Windows account", "startup autostart boot")
            row("Start minimized", "Only for automatic startup; opening Auralis normally shows its window", "tray hidden")
            row("System title bar", "Use Windows window decorations", "titlebar frame")
        }
    }
    tab(SettingsTab.APPEARANCE) {
        group("Interface") {
            row("Interface zoom", keywords = "scale size bigger smaller")
            row("Corner roundness", keywords = "radius rounded")
            row("Open player on play", "Expand the main player when a new playback session starts")
            row("Background tint", keywords = "colour color")
            row("Stage player", "Show cover and lyrics side by side in the main player")
            row("Tabbed player", "Switch between lyrics and queue; turn off to show both")
        }
        group("Typography") {
            row("Body font", "Installed Windows fonts", "typeface text")
            row("Heading font", "Installed Windows fonts", "typeface title")
            other("Load font file", "Use a TTF or OTF font", "typeface")
        }
        group("Player & seek bar") {
            other("Player background", keywords = "blur gradient")
            row("Seek bar style", "Visual appearance of the timeline slider", "progress slider")
        }
        group("Animated covers") {
            row("Show animated covers", "Play short video loops in Now Playing sidebar when available", "canvas video")
            row("Animated cover source", "Service to query for canvas video loops", "canvas")
        }
        group("Lyrics appearance") {
            row("Lyrics animation style", "Timing and visual transition mode")
            row("Text alignment", "Horizontal alignment of lyric lines", "left centre center right")
            row("Lyrics font size", keywords = "text size")
            row("Line spacing")
            row("Blur surrounding lyrics", "Apply depth blur to non-active lyric lines")
            row("Tap lyrics to seek", "Clicking any lyric jumps directly to that timestamp")
            row("Auto-scroll lyrics", "Keep active lyric line smoothly centered")
        }
        group("Desktop interface & density") {
            row("Open on startup", "Default tab when opening Auralis", "home search library")
            row("Display density", "UI scaling factor", "compact large")
        }
    }
    tab(SettingsTab.PLAYBACK) {
        group("Audio") {
            row("Audio quality", "Applies to the next stream", "bitrate")
            row("Normalize volume", "VLC volume normalization, applied when the next song starts", "loudness normalise")
            row("Autoplay", "Continue with recommendations when the queue ends", "radio")
            row("Preload next song", "Resolve the upcoming audio stream in advance", "gapless")
            row("Crossfade (experimental)", keywords = "fade transition")
            row("Playback speed", keywords = "rate tempo")
        }
        group("Queue") {
            row("Prevent duplicates", "Do not add the same song twice to the upcoming queue")
            row("Sticky shuffle", "Keep shuffling when you start a different album or playlist")
            row("Shuffle whole queue", "When shuffling, include earlier songs while retaining the current track")
        }
        group("Video") {
            row("Music videos", "Show a synchronized video in the sidebar when the playing recording has one. Audio continues independently.")
            row("Ambient light", "Sample the video colours for the sidebar background")
            row("Hide music videos in search", "Show audio recordings instead of video results on your next search")
        }
        group("Advanced · stream clients") {
            row("VISIONOS", keywords = "stream client extractor")
            row("IOS", keywords = "stream client extractor")
        }
        group("Lyrics source order") {
            row("Spicy Lyrics key override (optional)", "Works automatically with Auralis's included publishable key", "spicy api token key")
            row("Use custom source priority", "Try enabled sources in this order before automatic fallback. Applies to newly fetched lyrics.", "providers")
        }
    }
    tab(SettingsTab.HOTKEYS) {
        group("Global hotkeys") {
            row("Enable global hotkeys", "Works outside Auralis. Conflicts are reported beside each binding.", "keyboard shortcuts media keys")
            other("Reset bindings", keywords = "keyboard shortcuts")
        }
    }
    tab(SettingsTab.DISCORD) {
        group("Discord") {
            row("Rich Presence", "Show the currently playing song on your Discord profile.", "status activity")
            row("Show when paused", "Keep the card up while the track is paused.")
            row("Hide details", "Say only that music is playing.", "privacy")
            row("Status text", "What Discord shows next to your name in the member list.")
            other("App name", "Replaces \"Auralis\" wherever the app name appears.")
            row("Line 1"); row("Line 2")
            row("Album artwork", "Show the current song's cover.")
            row("Provider badge", "Add a small Auralis icon to the artwork.")
            row("Line 3 / artwork hover text")
            row("Progress bar", "Show elapsed and remaining time while playing.")
            row("Line 1 is clickable", keywords = "link"); row("Line 2 is clickable", keywords = "link")
            row("Album art is clickable", "Opens the album, or the song when no album link is available.", "link")
            row("Button 1"); row("Button 2")
        }
    }
    tab(SettingsTab.SCROBBLING) {
        group("Last.fm") {
            other("Last.fm API key and shared secret", "Use your Last.fm API application key and shared secret.", "lastfm scrobble")
            other("Connect to Last.fm", keywords = "lastfm scrobble sign in")
        }
        group("ListenBrainz") {
            other("ListenBrainz user token", keywords = "scrobble")
            row("Scrobble to ListenBrainz")
        }
        group("Timing & metadata") {
            row("Send now playing")
            row("Track percentage", keywords = "threshold")
            row("Use a time threshold", "Turn off to use only the track percentage")
            row("Maximum listening time")
            row("Clean title labels", "Remove Official Audio, Music Video and lyrics suffixes")
            row("Remove artist Topic suffix")
            row("Split music video titles", "Read Artist - Title from video titles")
            row("Primary artist only", "Exclude featured artists from scrobbles")
            row("Strict artist splitting", "Also split commas, ampersands and collaboration separators")
            row("Skip local files")
            other("Metadata rules", "Find and replace in titles, artists and albums", "add export import rule regex")
        }
    }
    tab(SettingsTab.DATA) {
        group("Network") {
            row("Use proxy", "New connections use this proxy", "socks http")
            row("Proxy type", keywords = "socks http")
            other("Proxy host:port", keywords = "address server")
        }
        group("Downloads") { other("Download folder", "Open or change where downloads are saved", "location directory offline") }
        group("Caches") { other("Clear caches", "Artwork and lyrics; downloads and accounts are kept", "storage space") }
    }
    tab(SettingsTab.ABOUT) {
        group("Updates") {
            row("Include prereleases", keywords = "beta")
            other("Check for updates", keywords = "version upgrade")
        }
        group("Support & diagnostics") {
            other("Source code and licenses")
            other("Report a problem", keywords = "bug issue")
            other("Copy diagnostics", keywords = "logs")
            other("Save diagnostics", keywords = "logs export")
        }
    }
    tab(SettingsTab.CONTENT) {
        group("Content country & language") {
            row("Content country", "Sets YouTube Music regional charts and new releases", "region location")
            row("Content language", "Preferred language for music metadata and descriptions")
            row("Show artist subscriber count", "Displays channel subscribers on artist pages")
            row("Show monthly listeners", "Displays estimated monthly listeners on artist pages")
            row("Show artist description", "Displays Wikipedia and bio descriptions on artist pages")
        }
        group("Lyrics providers") {
            other("Lyrics providers", "Active providers are queried in priority order for synchronous and word-timed lyrics.", "sources")
        }
        group("Lyrics romanization") {
            row("Romanize lyrics", "Show pronunciation below supported lyrics", "romanisation pronunciation latin")
        }
        group("Playlists") { row("Add songs to playlist", "Position where newly saved tracks appear", "top bottom start end") }
    }
    tab(SettingsTab.AI) {
        group("AI translation engine") {
            row("Enable AI lyric translation", "Send loaded lyrics to your configured provider and show translated lines in the player")
            row("AI provider", "Language model service used for translating song lyrics", "openrouter gemini openai deepseek")
            row("API key", "Your API key for the selected provider", "token secret")
            row("Model name", "Enter a model ID or load the models available from your provider")
            row("Translation mode", keywords = "transcription")
            row("Target translation language", "Language into which lyrics will be rendered")
            row("System prompt style", "Custom instruction prompt for artistic and rhythmic fidelity")
        }
    }
    tab(SettingsTab.PRIVACY) {
        group("Listening & search history") {
            row("Pause listening history", "Stops recording songs to your Recents shelf and stats")
            row("Pause search history", "Stops saving search queries in the search drop-down")
        }
        group("History data cleanup") {
            other("Clear listening history", "Deletes all recorded songs from your listening history", "delete")
            other("Clear search queries", "Deletes all recent search history queries", "delete")
        }
    }
}

/** Settings whose title, description, group, tab or keywords contain every word of [query]. */
internal fun searchSettings(query: String): List<SettingEntry> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return emptyList()
    val matches = settingEntries.filter { entry ->
        val text = listOf(entry.title, entry.description, entry.group, entry.tab.englishLabel, entry.keywords)
            .flatMap { listOf(it, desktopText(it)) }.joinToString(" ")
        terms.all { text.contains(it, ignoreCase = true) }
    }
    // The title itself first, then titles starting with or containing the words, then the rest; ties keep tab order.
    val phrase = terms.joinToString(" ")
    return matches.sortedBy { entry ->
        val titles = listOf(entry.title, desktopText(entry.title))
        when {
            titles.any { it.equals(phrase, true) } -> 0
            titles.any { it.startsWith(phrase, true) } -> 1
            terms.all { term -> titles.any { it.contains(term, true) } } -> 2
            else -> 3
        }
    }
}

/** A setting to scroll to and highlight once its tab shows; the group heading takes it when no row claims it. */
internal class SettingJump(val title: String, val group: String) {
    var claimed by mutableStateOf(false)
}

internal val LocalSettingJump = compositionLocalOf<SettingJump?> { null }

/** Scrolls this setting row (or, with [group], this group heading) into view and pulses it when it is the jump target. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.settingJumpTarget(title: String, group: Boolean = false): Modifier = composed {
    val jump = LocalSettingJump.current
    if (jump == null || (if (group) jump.group != title else jump.title != title)) return@composed Modifier
    val requester = remember { BringIntoViewRequester() }
    val glow = remember(jump) { Animatable(0f) }
    LaunchedEffect(jump) {
        if (!group) jump.claimed = true
        // Let the newly opened tab lay out first; a heading waits to see whether a row took the jump.
        delay(if (group) 140 else 60)
        if (group && jump.claimed) return@LaunchedEffect
        requester.bringIntoView()
        glow.animateTo(1f, tween(180))
        delay(1100)
        glow.animateTo(0f, tween(900))
    }
    val accent = MaterialTheme.colorScheme.primary
    bringIntoViewRequester(requester).drawWithContent {
        drawContent()
        if (glow.value > 0f) {
            val radius = CornerRadius(12.dp.toPx())
            drawRoundRect(accent.copy(alpha = .14f * glow.value), cornerRadius = radius)
            drawRoundRect(accent.copy(alpha = .75f * glow.value), cornerRadius = radius, style = Stroke(1.5.dp.toPx()))
        }
    }
}
