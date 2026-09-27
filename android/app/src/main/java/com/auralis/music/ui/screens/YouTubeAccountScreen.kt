package com.auralis.music.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.auralis.music.data.network.YouTubeMusicLibrary
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.ui.theme.dynamicBackground
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal val YOUTUBE_RED = Color(0xFFFF0033)
private const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

/**
 * Optional YouTube sign-in, only for age-restricted songs. Auralis's own sign-in is a Firebase
 * account YouTube never sees, so these songs need the user to sign in to YouTube itself.
 */
@Composable
fun YouTubeAccountScreen(onDismiss: () -> Unit) {
    val signedIn by YouTubeSession.signedIn.collectAsState()
    val accountLabel by YouTubeSession.accountLabel.collectAsState()
    var isSigningIn by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (isSigningIn) isSigningIn = false else onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (isSigningIn) {
            YouTubeSignInWebView(
                onSignedIn = { isSigningIn = false },
                onCancel = { isSigningIn = false }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Text(
                        text = "YouTube account",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.2.dp, YOUTUBE_RED.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(YOUTUBE_RED.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (signedIn) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = YOUTUBE_RED,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (signedIn) "Signed in to YouTube" else "Not signed in to YouTube",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp
                                )
                                if (signedIn && accountLabel.isNotBlank()) {
                                    Text(
                                        text = accountLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = if (signedIn) {
                                "Age-restricted songs will download using this YouTube account. " +
                                    "Your YouTube account needs to be age-verified for this to work."
                            } else {
                                "Some songs are age-restricted on YouTube. You can't download them unless " +
                                    "you sign in to YouTube here, even though you're signed in to Auralis. " +
                                    "Everything else works without it."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )

                        Spacer(Modifier.height(16.dp))
                        if (signedIn) {
                            OutlinedButton(
                                onClick = { signOutOfYouTube() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Sign out of YouTube", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Button(
                                onClick = { isSigningIn = true },
                                colors = ButtonDefaults.buttonColors(containerColor = YOUTUBE_RED, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Sign in to YouTube", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp).padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Your YouTube sign-in stays on this phone. Auralis only sends it to YouTube, " +
                            "to download age-restricted songs and to read your playlists when you import them, " +
                            "and never to Auralis's servers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                if (signedIn) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Pick playlists to import from Profile → Import playlists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

/** The signed-in user's YouTube Music playlists, to pick and import instead of pasting links. */
@Composable
internal fun YouTubeLibrarySection(
    isImporting: Boolean,
    importMessage: String?,
    onImport: (List<YouTubeMusicLibrary.LibraryPlaylist>) -> Unit
) {
    var playlists by remember { mutableStateOf<List<YouTubeMusicLibrary.LibraryPlaylist>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val selected = remember { mutableStateListOf<String>() }

    LaunchedEffect(reloadKey) {
        loadFailed = false
        val result = YouTubeMusicLibrary.fetchPlaylists()
        playlists = result
        loadFailed = result == null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Text(
                text = "Your YouTube Music playlists",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp
            )
            val all = playlists.orEmpty()
            if (all.isNotEmpty()) {
                val allSelected = selected.size == all.size
                TextButton(onClick = {
                    selected.clear()
                    if (!allSelected) selected.addAll(all.map { it.id })
                }) {
                    Text(if (allSelected) "Clear" else "Select all", color = YOUTUBE_RED)
                }
            }
        }
        Text(
            text = "Private playlists included. Liked Music is added to your liked songs; " +
                "importing a playlist you already have updates it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(10.dp))

        val list = playlists
        when {
            list == null && !loadFailed -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YOUTUBE_RED, modifier = Modifier.size(28.dp))
            }
            list == null -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Couldn't load your playlists from YouTube Music.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                TextButton(onClick = { reloadKey++ }) { Text("Try again", color = YOUTUBE_RED) }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(vertical = 4.dp)
                ) {
                    list.forEach { item ->
                        val checked = item.id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isImporting) {
                                    if (checked) selected.remove(item.id) else selected.add(item.id)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (item.isLikedMusic || item.thumbnail == null) {
                                    Icon(
                                        imageVector = if (item.isLikedMusic) Icons.Default.Favorite else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = YOUTUBE_RED,
                                        modifier = Modifier.size(22.dp)
                                    )
                                } else {
                                    coil.compose.AsyncImage(
                                        model = item.thumbnail,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                if (item.subtitle.isNotBlank()) {
                                    Text(
                                        text = item.subtitle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Checkbox(
                                checked = checked,
                                enabled = !isImporting,
                                onCheckedChange = { on -> if (on) selected.add(item.id) else selected.remove(item.id) },
                                colors = CheckboxDefaults.colors(checkedColor = YOUTUBE_RED)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onImport(list.filter { it.id in selected }) },
                    enabled = selected.isNotEmpty() && !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = YOUTUBE_RED, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when {
                            isImporting -> "Importing..."
                            selected.isEmpty() -> "Select playlists to import"
                            else -> "Import ${selected.size} ${if (selected.size == 1) "playlist" else "playlists"}"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
                importMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                    )
                }
            }
        }
    }
}

/** Forgets the session and removes YouTube/Google cookies without touching other WebView cookies. */
private fun signOutOfYouTube() {
    YouTubeSession.signOut()
    val manager = CookieManager.getInstance()
    for (site in listOf("https://music.youtube.com", "https://www.youtube.com", "https://accounts.google.com", "https://www.google.com")) {
        val host = Uri.parse(site).host.orEmpty()
        val baseDomain = host.substringAfter('.', host)
        manager.getCookie(site).orEmpty().split(";").mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
            .forEach { name ->
                for (domain in listOf(host, ".$baseDomain")) {
                    manager.setCookie(site, "$name=; Domain=$domain; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
                manager.setCookie(site, "$name=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
    }
    manager.flush()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeSignInWebView(onSignedIn: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finishFrom(view: WebView) {
        if (finishing) return
        finishing = true
        scope.launch {
            // Cookies and the page's config land a moment after music.youtube.com first loads.
            repeat(20) {
                val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                if (YouTubeSession.hasAuthCookies(cookie)) {
                    val config = readPageConfig(view)
                    if (config != null && config.visitorData.isNotBlank()) {
                        CookieManager.getInstance().flush()
                        YouTubeSession.save(cookie, config.visitorData, config.sessionIndex, accountLabel = "", dataSyncId = config.dataSyncId)
                        val label = withContext(Dispatchers.IO) { fetchAccountLabel() }
                        if (label.isNotBlank()) {
                            YouTubeSession.save(cookie, config.visitorData, config.sessionIndex, label, config.dataSyncId)
                        }
                        onSignedIn()
                        return@launch
                    }
                }
                delay(500)
            }
            finishing = false
            error = "Couldn't finish signing in. Make sure you completed Google's sign-in, then try again."
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = 52.dp),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme
                            return !(scheme == "http" || scheme == "https")
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            val uri = url?.let(Uri::parse)
                            if (uri?.scheme == "https" && uri.host == "music.youtube.com") finishFrom(view)
                        }
                    }
                    loadUrl(SIGN_IN_URL)
                }
            }
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(MaterialTheme.dynamicBackground)
                .padding(horizontal = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = "Sign in to YouTube",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp
                )
            }
        }

        if (finishing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = YOUTUBE_RED)
        }
        error?.let {
            Text(
                text = it,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xE6202020))
                    .padding(12.dp)
            )
        }
    }
}

private class PageConfig(val visitorData: String, val sessionIndex: String, val dataSyncId: String)

/** VISITOR_DATA, SESSION_INDEX and DATASYNC_ID from the signed-in music.youtube.com page, or null. */
private suspend fun readPageConfig(view: WebView): PageConfig? {
    val result = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        view.evaluateJavascript(
            "(function(){var c=window.yt&&window.yt.config_;" +
                "return c?JSON.stringify({v:c.VISITOR_DATA||'',s:String(c.SESSION_INDEX||0),d:c.DATASYNC_ID||''}):'';})()"
        ) { raw -> result.complete(raw) }
    }
    val raw = withTimeoutOrNull(1_500L) { result.await() } ?: return null
    return try {
        // evaluateJavascript hands back the string JSON-encoded, so decode twice.
        val inner = org.json.JSONTokener(raw).nextValue() as? String ?: return null
        if (inner.isBlank()) return null
        val json = JSONObject(inner)
        PageConfig(json.optString("v"), json.optString("s", "0"), json.optString("d"))
    } catch (_: Exception) {
        null
    }
}

/** "Name · email" of the signed-in YouTube account, or "" if YouTube doesn't say. */
private fun fetchAccountLabel(): String = try {
    val origin = "https://music.youtube.com"
    val body = JSONObject().put(
        "context",
        JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20241201.01.00")
                .put("hl", "en")
                .apply { if (YouTubeSession.visitorData.isNotBlank()) put("visitorData", YouTubeSession.visitorData) }
        )
    )
    val request = Request.Builder()
        .url("https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
        .header("Origin", origin)
        .header("Referer", "$origin/")
        .apply { YouTubeSession.authHeaders(origin).forEach { (k, v) -> header(k, v) } }
        .build()
    OkHttpClient().newCall(request).execute().use { resp ->
        val json = JSONObject(resp.body?.string().orEmpty())
        val header = json.optJSONArray("actions")?.optJSONObject(0)
            ?.optJSONObject("openPopupAction")?.optJSONObject("popup")
            ?.optJSONObject("multiPageMenuRenderer")?.optJSONObject("header")
            ?.optJSONObject("activeAccountHeaderRenderer")
        fun runsText(key: String) = header?.optJSONObject(key)?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
        listOf(runsText("accountName"), runsText("email")).filter { it.isNotBlank() }.joinToString(" · ")
    }
} catch (_: Exception) {
    ""
}
