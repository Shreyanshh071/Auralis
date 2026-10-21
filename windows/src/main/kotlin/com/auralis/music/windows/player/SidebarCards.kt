package com.auralis.music.windows.player

import com.auralis.music.ui.i18n.desktopText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.network.YouTubeBackoff
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.ui.components.clickable
import com.auralis.music.windows.search.DesktopArtwork
import com.auralis.music.windows.home.ArtistSubscriptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One person on a song: their name, what they did ("Main Artist", "Producer"…), and their channel. */
internal data class SongCredit(val name: String, val roles: List<String>, val channelId: String? = null)

/**
 * A song's credits from YouTube Music (the "Song credits" sheet, browse id MPTC + video id):
 * who performed, wrote and produced it. One request per song, cached; skipped while YouTube is
 * blocking this network. Null when YouTube has none.
 */
internal object SongCredits {
    private val cache = ConcurrentHashMap<String, List<SongCredit>>()
    private val roleNames = mapOf("Performed by" to "Main Artist", "Written by" to "Writer", "Produced by" to "Producer",
        "Composed by" to "Composer", "Lyrics by" to "Lyricist")

    fun cached(videoId: String): List<SongCredit>? = cache[videoId]

    suspend fun fetch(videoId: String): List<SongCredit>? = withContext(Dispatchers.IO) {
        cache[videoId]?.let { return@withContext it }
        if (videoId.isBlank() || videoId.startsWith("sp_") || YouTubeBackoff.active) return@withContext null
        val body = JSONObject().put("browseId", "MPTC$videoId").put("context", JSONObject().put("client",
            JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20250101.01.00").put("hl", "en").put("gl", "IN")))
        val request = okhttp3.Request.Builder().url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("Origin", "https://music.youtube.com").header("User-Agent", "Mozilla/5.0")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val text = runCatching {
            com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.code == 403 && YouTubeBackoff.isBlockPage(payload)) YouTubeBackoff.trip()
                payload.takeIf { response.isSuccessful }
            }
        }.getOrNull() ?: return@withContext null
        val credits = runCatching { parse(JSONObject(text)) }.getOrNull() ?: return@withContext null
        cache[videoId] = credits
        credits
    }

    internal fun parse(root: JSONObject): List<SongCredit> {
        val dialog = root.optJSONArray("onResponseReceivedActions")?.optJSONObject(0)
            ?.optJSONObject("openPopupAction")?.optJSONObject("popup")?.optJSONObject("dismissableDialogRenderer") ?: return emptyList()
        // The main artist's channel, from the song row at the top of the sheet.
        val artistRuns = dialog.optJSONObject("metadata")?.optJSONObject("musicMultiRowListItemRenderer")
            ?.optJSONObject("subtitle")?.optJSONArray("runs")
        val channels = mutableMapOf<String, String>()
        for (i in 0 until (artistRuns?.length() ?: 0)) {
            val run = artistRuns!!.optJSONObject(i)
            val id = run?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            if (!id.isNullOrBlank() && id.startsWith("UC")) channels[run.optString("text").trim().lowercase()] = id
        }
        val people = linkedMapOf<String, Pair<String, MutableList<String>>>()
        val sections = dialog.optJSONArray("sections") ?: return emptyList()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i)?.optJSONObject("dismissableDialogContentSectionRenderer") ?: continue
            fun text(key: String) = section.optJSONObject(key)?.optJSONArray("runs")?.let { runs ->
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
            val role = roleNames[text("title").trim()] ?: continue
            text("subtitle").split('\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { name ->
                val entry = people.getOrPut(name.lowercase()) { name to mutableListOf() }
                if (role !in entry.second) entry.second += role
            }
        }
        return people.values.map { (name, roles) -> SongCredit(name, roles, channels[name.lowercase()]) }
            .sortedByDescending { "Main Artist" in it.roles }
    }
}

/** The sidebar's card frame: soft glass, as the lyrics card. */
@Composable
internal fun SidebarCard(title: String, action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Color.White.copy(alpha = .07f)).border(1.dp, Color.White.copy(alpha = .10f), shape)
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(desktopText(title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            if (action != null) Text(desktopText(action), Modifier.clickable(onClick = onAction), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = .75f))
        }
        content()
    }
}

/** Credits: who performed, wrote and produced the playing song; the main artist can be followed. */
@Composable
internal fun SidebarCreditsCard(track: Track, library: LibraryRepository?, onOpenArtist: (Artist) -> Unit, modifier: Modifier = Modifier) {
    var credits by remember(track.id) { mutableStateOf(SongCredits.cached(track.id)) }
    LaunchedEffect(track.id) { if (credits == null) credits = SongCredits.fetch(track.id) }
    val people = credits?.takeIf { it.isNotEmpty() } ?: return
    var showAll by remember(track.id) { mutableStateOf(false) }
    val saved by (library?.getSavedArtists() ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val signedIn by YouTubeSession.signedIn.collectAsState()
    val sessionRevision by YouTubeSession.revision.collectAsState()
    val subscriptions = remember(sessionRevision) { ArtistSubscriptions() }
    var pendingChannel by remember { mutableStateOf<String?>(null) }
    var followError by remember(track.id, sessionRevision) { mutableStateOf<String?>(null) }
    LaunchedEffect(subscriptions, people.mapNotNull { it.channelId }) {
        subscriptions.load(people.mapNotNull { it.channelId })
    }
    SidebarCard("Credits", if (people.size > 3) (if (showAll) "Show less" else "Show all") else null, { showAll = !showAll }, modifier) {
        (if (showAll) people else people.take(3)).forEach { person ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // A link: underlined while hovered, no grey box (the default highlight hugged the text).
                    val nameHover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val nameHovered by nameHover.collectIsHoveredAsState()
                    val linked = person.channelId != null
                    Text(person.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (linked && nameHovered) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                        modifier = if (linked) Modifier.clickable(interactionSource = nameHover, indication = null) {
                            onOpenArtist(Artist(person.channelId!!, person.name))
                        } else Modifier)
                    Text(person.roles.joinToString(" • ") { desktopText(it) }, fontSize = 12.sp, color = Color.White.copy(alpha = .65f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                val channel = person.channelId
                if (channel != null && library != null) {
                    val following = if (signedIn) subscriptions.subscribed(channel) ?: saved.any { it.id == channel }
                        else saved.any { it.id == channel }
                    SidebarFollowButton(following, pendingChannel == channel, pendingChannel == null) {
                        pendingChannel = channel
                        followError = null
                        val next = !following
                        scope.launch {
                            try {
                                // Confirm the connected account's subscription before changing the local library.
                                if (signedIn) subscriptions.setSubscribed(channel, next)
                                if (next) library.saveArtist(SavedArtist(channel, person.name, null))
                                else library.removeArtist(channel)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                followError = e.message ?: "Couldn't update following. Try again."
                            } finally { pendingChannel = null }
                        }
                    }
                }
            }
        }
        followError?.let { Text(desktopText(it), fontSize = 12.sp, color = Color.White.copy(alpha = .8f)) }
    }
}

@Composable
internal fun SidebarFollowButton(following: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.padding(start = 12.dp).height(36.dp).widthIn(min = 76.dp)
        .clip(shape).border(1.dp, Color.White.copy(alpha = .35f), shape)
        .background(if (following) Color.White.copy(alpha = .12f) else Color.Transparent)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(if (busy) "…" else desktopText(if (following) "Following" else "Follow"),
            fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1, color = Color.White)
    }
}

/** Next in queue: the song after this one (click plays it), and a way to the whole queue. */
@Composable
internal fun SidebarNextInQueueCard(playback: DesktopPlaybackController, onOpenQueue: () -> Unit, modifier: Modifier = Modifier) {
    val queue by playback.queue.collectAsState()
    val next = queue.tracks.getOrNull(queue.index + 1) ?: return
    SidebarCard("Next in queue", "Open queue", onOpenQueue, modifier) {
        // A plain filled rounded highlight on hover (as the library sidebar's rows), not the ripple's
        // hover layer, which sat flush on the cover and read as a thin uneven outline.
        val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (hovered) Color.White.copy(alpha = .10f) else Color.Transparent)
            .hoverable(hover).clickable(interactionSource = hover, indication = null) { playback.next() }.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DesktopArtwork(next.thumbnail, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), 6.dp, next.title)
            Column(Modifier.weight(1f)) {
                Text(next.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(next.artist, fontSize = 12.sp, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
