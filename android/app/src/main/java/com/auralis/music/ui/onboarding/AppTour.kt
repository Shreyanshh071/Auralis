package com.auralis.music.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.datastore.*
import com.auralis.music.data.network.YouTubeLocales
import com.auralis.music.domain.model.*
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.screens.wrapped.WrappedBackground
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Interactive first-launch tour, using the actual player and lyric views for its demos. */
@Composable
fun AppTour(onFinish: () -> Unit) {
    val context = LocalContext.current
    val appearanceStore = remember { AppearanceSettingsDataStore(context) }
    val settingsStore = remember { SettingsDataStore(context) }
    val contentStore = remember { ContentSettingsDataStore(context) }
    val appearance by appearanceStore.settingsFlow.collectAsState(AppearanceSettings())
    val settings by settingsStore.settingsFlow.collectAsState(PlayerSettings())
    val content by contentStore.settingsFlow.collectAsState(ContentSettings())
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { 6 }
    val reduced = LocalReducedMotion.current
    fun moveTo(page: Int) { scope.launch {
        if (reduced) pager.scrollToPage(page)
        else pager.animateScrollToPage(page, animationSpec = spring(dampingRatio = .92f, stiffness = 190f))
    } }
    androidx.activity.compose.BackHandler(enabled = pager.currentPage > 0) { moveTo(pager.currentPage - 1) }
    val ink = Color(0xFFF0F2F7)
    val accent = Color(0xFFE5CB9E)
    val titles = listOf("Your music. Your Auralis.", "Make it yours.", "Hear every detail.", "Feel every word.", "Take music with you.", "Where is your sound?")
    val hints = listOf("A little tour before your first play.", "Try the player, lyrics and queue controls. Silent demo.", "Choose how much data your music uses.", "Try every lyrics style, exactly as it plays in Auralis.", "Download songs or playlists from their menu. Play them from Library, even without internet.", "Choose your music region and language. Next, sign in and pick your artists.")
    WrappedBackground(animate = !reduced) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AURALIS", color = ink, letterSpacing = 4.sp, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TourTouchButton("Skip tour", false, accent, ink, Modifier.width(90.dp).height(38.dp), onFinish)
            }
            HorizontalPager(state = pager, pageSpacing = 12.dp,
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = if (reduced) snap() else spring(dampingRatio = .92f, stiffness = 190f)),
                modifier = Modifier.weight(1f)) { page ->
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val active = pager.settledPage == page
                Column(Modifier.fillMaxSize().padding(horizontal = 22.dp).graphicsLayer {
                    alpha = if (reduced) 1f else (1f - abs(offset) * .32f).coerceIn(.35f, 1f)
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(12.dp))
                    Text(titles[page], fontSize = 29.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, color = ink, textAlign = TextAlign.Center)
                    Text(hints[page], color = ink.copy(alpha = .6f), fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
                    Box(Modifier.fillMaxWidth().weight(1f).graphicsLayer {
                        translationX = if (reduced) 0f else offset * size.width * .08f
                    }, contentAlignment = Alignment.Center) {
                        when (page) {
                            0 -> Icon(androidx.compose.ui.res.painterResource(com.auralis.music.R.drawable.ic_auralis_logo), null, tint = Color.Unspecified, modifier = Modifier.size(184.dp).clip(RoundedCornerShape(32.dp)))
                            1 -> TourPlayerPreview(appearance, active, Modifier.fillMaxSize())
                            2 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EqualizerBars(isPlaying = active, modifier = Modifier.size(160.dp), color = accent)
                                Text(settings.audioQuality.description, color = ink.copy(alpha = .65f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 26.dp))
                            }
                            3 -> TourLyricsPreview(appearance, active, Modifier.fillMaxSize())
                            else -> Icon(if (page == 4) Icons.Rounded.DownloadDone else Icons.Rounded.Public, null, tint = accent, modifier = Modifier.size(140.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    when(page) {
                        1 -> {
                            TourChoices(PlayerDesign.entries.map { it.displayName }, appearance.playerDesign, accent, ink, columns = 3) { value -> scope.launch {
                                appearanceStore.updateSettings(appearance.copy(playerDesign = value, newPlayerDesign = value != "Old player"))
                            } }
                            Spacer(Modifier.height(8.dp))
                            TourChoices(listOf("Follow system", "Light Mode", "Dark Mode"), appearance.appTheme, accent, ink, columns = 3) { value -> scope.launch { appearanceStore.updateSettings(appearance.copy(appTheme = value)) } }
                        }
                        2 -> TourChoices(AudioQuality.entries.map { it.displayName }, settings.audioQuality.displayName, accent, ink, columns = 2,
                            label = { value -> AudioQuality.entries.first { it.displayName == value }.name.lowercase().replaceFirstChar { it.uppercase() } }) { value -> scope.launch { settingsStore.setAudioQuality(AudioQuality.entries.first { it.displayName == value }) } }
                        3 -> TourChoices(LyricsAnimationMode.entries.map { it.displayName }, appearance.lyricsAnimation, accent, ink, columns = 4,
                            label = { when (LyricsAnimationMode.fromDisplayName(it)) {
                                LyricsAnimationMode.AURALIS -> "Auralis"
                                LyricsAnimationMode.FLUID -> "Fluid"
                                LyricsAnimationMode.APPLE_MUSIC_V2 -> "Apple V2"
                                LyricsAnimationMode.LYRICS_V2_FLUID -> "V2 Fluid"
                                else -> it
                            } }) { value -> scope.launch { appearanceStore.updateSettings(appearance.copy(lyricsAnimation = value)) } }
                        4 -> Text("Tap ⋮ beside a song → Download", color = ink.copy(alpha = .7f), fontSize = 13.sp)
                        5 -> {
                            LocaleChoice("Music region", YouTubeLocales.countries, content.contentCountry, accent, ink) { value -> scope.launch { contentStore.update { it.copy(contentCountry = value) } } }
                            Spacer(Modifier.height(8.dp))
                            LocaleChoice("Content language", YouTubeLocales.languages, content.contentLanguage, accent, ink) { value -> scope.launch { contentStore.update { it.copy(contentLanguage = value) } } }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(6) { index ->
                    val proximity = (1f - abs((pager.currentPage - index) + pager.currentPageOffsetFraction)).coerceIn(0f, 1f)
                    Box(Modifier.size((6 + 18 * proximity).dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(if (proximity > .5f) ink.copy(alpha = .8f) else ink.copy(alpha = .2f)))
                }
                Spacer(Modifier.weight(1f))
                TourTouchButton(if (pager.currentPage == 5) "Get started" else "Next", true, accent, ink, Modifier.width(108.dp).height(46.dp)) {
                    if (pager.currentPage == 5) onFinish() else moveTo(pager.currentPage + 1)
                }
            }
        }
    }
}

/** Shared press/release spring so every tour control responds while the finger is down. */
@Composable
private fun TourTouchButton(text: String, selected: Boolean, accent: Color, ink: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (pressed && !reduced) .93f else 1f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = if (pressed) .8f else .48f, stiffness = 450f), label = "tour press")
    val rotation by animateFloatAsState(if (pressed && !reduced) -1.4f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = .42f, stiffness = 400f), label = "tour wobble")
    val fill by animateColorAsState(if (selected) accent else ink.copy(alpha = .05f), tween(if (reduced) 0 else 180), label = "tour selection")
    val corner by animateDpAsState(if (selected) 7.dp else 25.dp,
        animationSpec = if (reduced) snap() else spring(dampingRatio = .78f, stiffness = 300f), label = "tour selection shape")
    val shape = RoundedCornerShape(corner)
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rotation }
        .clip(shape).background(fill).border(1.dp, if (selected) accent else ink.copy(alpha = .14f), shape)
        .selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.Button,
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() })
        .padding(horizontal = 6.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) Color(0xFF21192C) else ink, fontSize = 11.sp,
            lineHeight = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun TourChoices(values: List<String>, selected: String, accent: Color, ink: Color,
    columns: Int, label: (String) -> String = { it }, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        values.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { value -> TourTouchButton(label(value), value == selected, accent, ink, Modifier.weight(1f).height(47.dp)) { onSelect(value) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LocaleChoice(label: String, choices: Map<String, String>, selected: String, accent: Color, ink: Color, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TourTouchButton("$label: ${choices[selected] ?: "System default"}", false, accent, ink, Modifier.fillMaxWidth().height(46.dp)) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { TourTouchButton("System default", selected == ContentSettings.SYSTEM, accent, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxWidth().height(44.dp)) { onSelect(ContentSettings.SYSTEM); open = false } }
            choices.forEach { (code, name) -> item(key = code) { TourTouchButton(name, code == selected, accent, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxWidth().height(44.dp)) { onSelect(code); open = false } } }
        }
    }, confirmButton = { TourTouchButton("Close", false, accent, MaterialTheme.colorScheme.onSurface, Modifier.width(80.dp).height(40.dp)) { open = false } })
}
