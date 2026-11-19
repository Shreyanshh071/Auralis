package com.auralis.music.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
            artists = withTimeout(15_000) { coroutineScope {
                val regional = async { ChartsArtistsSource.topArtists(ContentLocale.gl(), ContentLocale.hl()) }
                val international = async { ChartsArtistsSource.topArtists("US", "en") }
                OnboardingFlow.suggestions(regional.await(), international.await())
            } }
            if (artists.isEmpty()) notice = "Artist suggestions are unavailable. Search for your favorites above."
        } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; notice = "Suggestions timed out. Try searching for an artist." }
        catch (_: Exception) { notice = "Artist suggestions are unavailable. Search for your favorites above." }
        finally { loading = false }
        if (artists.isEmpty()) {
            loading = true
            try {
                artists = withTimeoutOrNull(15_000) {
                    coroutineScope {
                        (ChartsArtistsSource.fallbackNames(ContentLocale.gl()) + ChartsArtistsSource.fallbackNames("US")).distinct().map { name -> async {
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
    val reduced = LocalReducedMotion.current
    fun pick(artist: Artist, selected: Boolean) {
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
    // Same backdrop and chrome as the tour, so the picker reads as its last step.
    TourBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AURALIS", color = PickerInk, letterSpacing = 4.sp, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TourTouchButton("Skip", false, PickerAccent, PickerInk, Modifier.width(72.dp).height(38.dp)) {
                    if (!saving) { OnboardingStore.skipPicker(); onComplete() }
                }
            }
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text("Who do you love?", color = PickerInk, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp))
                Text("Pick at least ${OnboardingFlow.MIN_PICKS} artists. Your first Home is built from them.",
                    color = PickerInk.copy(alpha = .6f), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                PickerSearchField(query, enabled = !saving) { query = it }
            }
            // The chosen artists, newest first; tap one to drop it.
            AnimatedVisibility(picks.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 14.dp)) {
                    items(picks.reversed(), key = { "pick-" + it.id }) { artist ->
                        PickedChip(artist, enabled = !saving, modifier = Modifier.animateItem()) { pick(artist, true) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !online -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { com.auralis.music.ui.components.OfflineState() }
                    loading && query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PickerAccent, strokeWidth = 3.dp)
                    }
                    else -> {
                        val shown = (if (query.isBlank()) artists else results).distinctBy { it.id }
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                            verticalItemSpacing = 10.dp,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            notice?.let { message ->
                                item(span = StaggeredGridItemSpan.FullLine, key = "notice") {
                                    Text(message, color = PickerAccent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                                }
                            }
                            itemsIndexed(shown, key = { _, artist -> artist.id }) { index, artist ->
                                val order = picks.indexOfFirst { OnboardingFlow.sameArtist(it, artist) }
                                // Three heights in rotation give the wall its uneven, poster-like rhythm.
                                val ratio = when (index % 3) { 0 -> .78f; 1 -> .98f; else -> .86f }
                                ArtistTile(artist, order, ratio, enabled = !saving, modifier = Modifier.animateItem()) { pick(artist, order >= 0) }
                            }
                        }
                    }
                }
            }
            PickerFooter(picks.size, saving, enabled = online && picks.size >= OnboardingFlow.MIN_PICKS && !saving, onFinish = ::finish)
        }
    }
}

private val PickerInk = Color(0xFFF0F2F7)
private val PickerAccent = Color(0xFFE5CB9E)

@Composable
private fun PickerSearchField(query: String, enabled: Boolean, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(Modifier.fillMaxWidth().height(52.dp).clip(shape).background(PickerInk.copy(alpha = .07f))
        .border(1.dp, PickerInk.copy(alpha = .12f), shape).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = PickerInk.copy(alpha = .6f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search any artist", color = PickerInk.copy(alpha = .45f), fontSize = 15.sp)
            BasicTextField(query, onChange, enabled = enabled, singleLine = true,
                textStyle = TextStyle(color = PickerInk, fontSize = 15.sp), cursorBrush = SolidColor(PickerAccent),
                modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear search", tint = PickerInk.copy(alpha = .6f),
            modifier = Modifier.size(20.dp).clickable { onChange("") })
    }
}

/** A tall photo tile with the name on a dark fade; picked tiles get an accent ring and their pick number. */
@Composable
private fun ArtistTile(artist: Artist, order: Int, ratio: Float, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val selected = order >= 0
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) .94f else 1f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = if (pressed) .8f else .5f, stiffness = 450f), label = "artist press")
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, label = "artist ring")
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.fillMaxWidth().aspectRatio(ratio).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape).background(PickerInk.copy(alpha = .06f))
        .border(ring, PickerAccent, shape)
        .clickable(interactions, null, enabled = enabled, onClick = onClick)) {
        Icon(Icons.Rounded.Person, null, tint = PickerInk.copy(alpha = .18f), modifier = Modifier.size(44.dp).align(Alignment.Center))
        AsyncImage(model = artist.thumbnail, contentDescription = artist.name, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent,
            1f to Color.Black.copy(alpha = .78f))))
        Text(artist.name, color = Color.White, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 9.dp))
        AnimatedVisibility(selected, enter = fadeIn() + scaleIn(initialScale = .4f), exit = fadeOut() + scaleOut(targetScale = .4f),
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(PickerAccent), contentAlignment = Alignment.Center) {
                Text("${order + 1}", color = Color(0xFF21192C), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PickedChip(artist: Artist, enabled: Boolean, modifier: Modifier = Modifier, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(modifier.height(44.dp).clip(shape).background(PickerAccent.copy(alpha = .16f)).border(1.dp, PickerAccent.copy(alpha = .4f), shape)
        .clickable(enabled = enabled, onClick = onRemove).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(PickerInk.copy(alpha = .08f))) {
            AsyncImage(model = artist.thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.width(8.dp))
        Text(artist.name, color = PickerInk, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.Close, "Remove ${artist.name}", tint = PickerInk.copy(alpha = .7f), modifier = Modifier.size(16.dp))
    }
}

/** Progress toward the minimum as filling segments, then the button. */
@Composable
private fun PickerFooter(count: Int, saving: Boolean, enabled: Boolean, onFinish: () -> Unit) {
    val needed = OnboardingFlow.MIN_PICKS
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(when {
                count == 0 -> "Pick $needed to start"
                count < needed -> "${needed - count} more to go"
                else -> "$count picked · ready"
            }, color = PickerInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(needed) { index ->
                    val fill by animateFloatAsState(if (index < count) 1f else .18f, label = "pick progress")
                    Box(Modifier.width(26.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(PickerAccent.copy(alpha = fill)))
                }
            }
        }
        TourTouchButton(if (saving) "Building…" else "Let’s listen", enabled || saving, PickerAccent, PickerInk,
            Modifier.width(132.dp).height(50.dp)) { if (enabled) onFinish() }
    }
}
