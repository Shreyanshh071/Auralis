package com.auralis.music.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.datastore.*
import com.auralis.music.data.network.YouTubeLocales
import com.auralis.music.domain.model.*
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/** Interactive first-launch tour. Choices use the same stores as Settings. */
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
    androidx.activity.compose.BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }
    val reduced = LocalReducedMotion.current
    val accents = listOf(Color(0xFFA999FF), Color(0xFFFFB995), Color(0xFF8EE9CE), Color(0xFFFF9CC9), Color(0xFF8DCFFF), Color(0xFFCFDF89))
    val accent by animateColorAsState(accents[pager.currentPage], tween(if (reduced) 0 else 500), label = "tour colour")
    val drift = if (reduced) 0f else {
        val motion = rememberInfiniteTransition(label = "tour atmosphere")
        val value by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "glow drift")
        value
    }
    val titles = listOf("Your music.\nYour Auralis.", "Make it yours.", "Hear every detail.", "Feel every word.", "Take music with you.", "Where is your sound?")
    val hints = listOf("A little tour before your first play. Swipe to explore.", "Try a player and choose your theme. You can change everything later.", "Choose how much data your music uses.", "Lyrics move with the music. Pick the animation you like.", "Download songs or whole playlists from their menu. Find them in Library → Downloaded, even without internet.", "Choose the music region and language for your recommendations. Then sign in and pick your artists.")
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0A12))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .28f), Color.Transparent), center = center.copy(x = size.width * (.3f + drift * .4f), y = size.height * .24f), radius = size.width * 1.1f))
            drawRect(Brush.radialGradient(listOf(Color(0xFF685BCE).copy(alpha = .14f), Color.Transparent), center = center.copy(x = size.width * (.8f - drift * .5f), y = size.height * .8f), radius = size.width))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AURALIS", color = Color.White, letterSpacing = 4.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onFinish) { Text("Skip tour", color = Color.White.copy(alpha = .7f)) }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(200.dp).graphicsLayer {
                        translationX = if (reduced) 0f else offset * size.width * .25f
                        alpha = if (reduced) 1f else (1f - kotlin.math.abs(offset) * .5f).coerceIn(.3f, 1f)
                    }, contentAlignment = Alignment.Center) {
                        when (page) {
                            0 -> Icon(androidx.compose.ui.res.painterResource(com.auralis.music.R.drawable.ic_auralis_logo), null, tint = Color.Unspecified, modifier = Modifier.size(138.dp))
                            1 -> Surface(shape = RoundedCornerShape(if (appearance.playerDesign == "Immersive player") 60.dp else 24.dp), color = accent.copy(alpha = .15f), modifier = Modifier.size(174.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(Icons.Rounded.MusicNote, null, tint = accent, modifier = Modifier.size(64.dp))
                                    Text(appearance.playerDesign, color = Color.White)
                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
                                }
                            }
                            2 -> EqualizerBars(isPlaying = true, modifier = Modifier.size(130.dp), color = accent)
                            3 -> LyricsPreview(accent, reduced, appearance.lyricsAnimation)
                            else -> Icon(when(page) { 0 -> Icons.Rounded.Headphones; 4 -> Icons.Rounded.DownloadDone; else -> Icons.Rounded.Public }, null, tint = accent, modifier = Modifier.size(112.dp))
                        }
                    }
                    Text(titles[page], fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.graphicsLayer {
                        translationX = if (reduced) 0f else offset * size.width * .09f
                        alpha = if (reduced) 1f else (1f - kotlin.math.abs(offset)).coerceIn(0f, 1f)
                    })
                    Spacer(Modifier.height(14.dp))
                    Text(hints[page], color = Color.White.copy(alpha = .65f), textAlign = TextAlign.Center, lineHeight = 22.sp)
                    Spacer(Modifier.height(24.dp))
                    when(page) {
                        1 -> {
                            TourChoices(listOf("New player", "Old player", "Immersive player"), appearance.playerDesign, accent) { value -> scope.launch { appearanceStore.updateSettings(appearance.copy(playerDesign = value)) } }
                            Spacer(Modifier.height(12.dp))
                            TourChoices(listOf("Follow system", "Light Mode", "Dark Mode"), appearance.appTheme, accent) { value -> scope.launch { appearanceStore.updateSettings(appearance.copy(appTheme = value)) } }
                        }
                        2 -> TourChoices(AudioQuality.entries.map { it.displayName }, settings.audioQuality.displayName, accent) { value -> scope.launch { settingsStore.setAudioQuality(AudioQuality.entries.first { it.displayName == value }) } }
                        3 -> TourChoices(listOf(LyricsAnimationMode.METRO_LYRICS, LyricsAnimationMode.FLUID, LyricsAnimationMode.GLOW).map { it.displayName }, appearance.lyricsAnimation, accent) { value -> scope.launch { appearanceStore.updateSettings(appearance.copy(lyricsAnimation = value)) } }
                        4 -> Text("Tip: tap ⋮ beside a song → Download", color = accent, textAlign = TextAlign.Center)
                        5 -> {
                            LocaleChoice("Music region", YouTubeLocales.countries, content.contentCountry) { value -> scope.launch { contentStore.update { it.copy(contentCountry = value) } } }
                            LocaleChoice("Content language", YouTubeLocales.languages, content.contentLanguage) { value -> scope.launch { contentStore.update { it.copy(contentLanguage = value) } } }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { index ->
                    val width by animateDpAsState(if (index == pager.currentPage) 26.dp else 6.dp, tween(if (reduced) 0 else 220), label = "page dot")
                    Box(Modifier.size(width, 6.dp).clip(RoundedCornerShape(9.dp)).background(if (index == pager.currentPage) accent else Color.White.copy(alpha = .2f)))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (pager.currentPage == 5) onFinish() else scope.launch { if (reduced) pager.scrollToPage(pager.currentPage + 1) else pager.animateScrollToPage(pager.currentPage + 1) } }, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) { Text(if (pager.currentPage == 5) "Get started" else "Next") }
            }
        }
    }
}

@Composable
private fun TourChoices(values: List<String>, selected: String, accent: Color, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        values.forEach { value ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(value) }, colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = accent, selectedLabelColor = Color.Black), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LocaleChoice(label: String, choices: Map<String, String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${choices[selected] ?: "System default"}", color = Color.White) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        androidx.compose.foundation.lazy.LazyColumn {
            item { TextButton(onClick = { onSelect(ContentSettings.SYSTEM); open = false }) { Text("System default") } }
            choices.forEach { (code, name) -> item(key = code) { TextButton(onClick = { onSelect(code); open = false }) { Text(name) } } }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

@Composable
private fun LyricsPreview(accent: Color, reduced: Boolean, style: String) {
    var word by remember { mutableIntStateOf(0) }
    LaunchedEffect(reduced, style) { if (!reduced) while (true) { kotlinx.coroutines.delay(650); word = (word + 1) % 4 } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("A song for every moment", color = Color.White.copy(alpha = .25f), fontSize = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Let", "your", "music", "shine").forEachIndexed { index, text ->
                val color by animateColorAsState(if (reduced || index <= word) accent else Color.White.copy(alpha = .3f), label = "lyric word")
                Text(text, color = color, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(style, color = Color.White.copy(alpha = .5f), modifier = Modifier.padding(top = 14.dp))
    }
}
