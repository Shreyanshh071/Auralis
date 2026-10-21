package com.auralis.music.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auralis.music.data.datastore.OnboardingStore
import com.auralis.music.data.network.*
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.onboarding.OnboardingFlow
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.theme.LocalReducedMotion
import kotlinx.coroutines.*

@Composable
fun ArtistPicker(repository: SearchRepository, onComplete: () -> Unit) {
    val online by ConnectivityMonitor.isOnline.collectAsState()
    val scope = rememberCoroutineScope()
    val accent = Color(0xFFC6B4FF)
    var artists by remember { mutableStateOf(emptyList<Artist>()) }
    var results by remember { mutableStateOf(emptyList<Artist>()) }
    var picks by remember { mutableStateOf(emptyList<Artist>()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val pages = remember { mutableMapOf<String, ArtistPage>() }
    val relatedLoading = remember { mutableSetOf<String>() }
    LaunchedEffect(online) {
        if (!online || artists.isNotEmpty()) return@LaunchedEffect
        loading = true
        try {
            artists = withTimeout(15_000) { ChartsArtistsSource.topArtists(ContentLocale.gl(), ContentLocale.hl()) }
            if (artists.isEmpty()) notice = "Artist suggestions are unavailable. Search for your favorites above."
        } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; notice = "Suggestions timed out. Try searching for an artist." }
        catch (_: Exception) { notice = "Artist suggestions are unavailable. Search for your favorites above." }
        finally { loading = false }
        if (artists.isEmpty()) {
            loading = true
            try {
                artists = withTimeoutOrNull(15_000) {
                    coroutineScope {
                        ChartsArtistsSource.fallbackNames(ContentLocale.gl()).map { name -> async {
                            try { repository.searchArtists(name).firstOrNull() }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                        } }.awaitAll().filterNotNull().distinctBy { it.id }
                    }
                }.orEmpty()
                if (artists.isNotEmpty()) notice = "Some artists to get you started. Search above to find anyone."
            } finally { loading = false }
        }
    }
    LaunchedEffect(query, online) {
        results = emptyList()
        if (query.isBlank() || !online) return@LaunchedEffect
        delay(350)
        try { results = withTimeout(12_000) { repository.searchArtists(query.trim()) } }
        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
        catch (_: Exception) { notice = "Search could not load. Please try again." }
    }
    fun finish() {
        if (saving || picks.size < OnboardingFlow.MIN_PICKS) return
        saving = true
        scope.launch {
            try {
                val pool = coroutineScope {
                    picks.map { artist -> async {
                        val songs = pages[artist.id]?.topSongs?.takeIf { it.isNotEmpty() } ?: try {
                            withTimeoutOrNull(12_000) { repository.getArtistPage(artist)?.topSongs.orEmpty().ifEmpty { repository.searchSongs("${artist.name} songs") } }.orEmpty()
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                        artist to songs
                    } }.awaitAll()
                }
                val songs = OnboardingFlow.seedTracks(pool)
                if (songs.isEmpty()) { notice = "Could not load your artists' songs. Check your connection and try again."; return@launch }
                OnboardingStore.savePicks(picks, songs)
                onComplete()
            } finally { saving = false }
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0A12)).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR SOUND", letterSpacing = 3.sp, color = accent, modifier = Modifier.weight(1f))
            TextButton(enabled = !saving, onClick = { OnboardingStore.skipPicker(); onComplete() }) { Text("Skip", color = Color.White.copy(alpha = .65f)) }
        }
        Text("Who do you love?", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Pick at least 3 artists. Your first Home starts here.", color = Color.White.copy(alpha = .6f), modifier = Modifier.padding(vertical = 12.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, enabled = !saving, singleLine = true, label = { Text("Find any artist") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        if (picks.isNotEmpty()) Text(picks.joinToString(" · ") { it.name }, maxLines = 2, color = accent, modifier = Modifier.padding(vertical = 8.dp))
        if (!online) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { com.auralis.music.ui.components.OfflineState() }
        } else if (loading && query.isBlank()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
        } else {
            notice?.let { Text(it, color = accent, modifier = Modifier.padding(vertical = 8.dp)) }
            val shown = if (query.isBlank()) artists else results
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
                items(shown.distinctBy { it.id }, key = { it.id }) { artist ->
                    val selected = picks.any { OnboardingFlow.sameArtist(it, artist) }
                    ArtistBubble(artist, selected, accent, enabled = !saving) {
                        picks = OnboardingFlow.togglePick(picks, artist)
                        if (!selected && relatedLoading.add(artist.id)) scope.launch {
                            try {
                                val page = withTimeoutOrNull(10_000) { repository.getArtistPage(artist) }
                                if (page != null) {
                                    pages[artist.id] = page
                                    if (artists.none { OnboardingFlow.sameArtist(it, artist) }) artists = listOf(artist) + artists
                                    artists = OnboardingFlow.insertRelated(artists, artist, page.similarArtists)
                                    results = OnboardingFlow.insertRelated(results, artist, page.similarArtists)
                                }
                            } catch (e: CancellationException) { throw e } catch (_: Exception) { relatedLoading.remove(artist.id) }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { (picks.size / 3f).coerceAtMost(1f) }, color = accent, modifier = Modifier.size(42.dp))
                Text("${picks.size}", color = Color.White)
            }
            Button(onClick = ::finish, enabled = online && picks.size >= 3 && !saving, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black), modifier = Modifier.weight(1f).height(54.dp)) {
                Text(if (saving) "Building your Home…" else "Let's listen")
            }
        }
    }
}

@Composable
private fun ArtistBubble(artist: Artist, selected: Boolean, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(if (selected) 1.07f else 1f, animationSpec = androidx.compose.animation.core.tween(if (reduced) 0 else 240), label = "artist pick")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(enabled = enabled, onClick = onClick)) {
        Box(Modifier.aspectRatio(1f).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(Color.White.copy(alpha = .08f)).border(if (selected) 3.dp else 0.dp, if (selected) accent else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Person, null, tint = accent.copy(alpha = .3f), modifier = Modifier.size(48.dp))
            AsyncImage(model = artist.thumbnail, contentDescription = artist.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (selected) Box(Modifier.fillMaxSize().background(accent.copy(alpha = .3f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, "Selected", tint = Color.White, modifier = Modifier.size(36.dp)) }
        }
        Text(artist.name, color = Color.White, maxLines = 2, textAlign = TextAlign.Center, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}
