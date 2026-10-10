package com.auralis.music.ui.screens

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.auralis.music.R
import com.auralis.music.data.network.SpotifyLibrary
import com.auralis.music.data.network.SpotifySession
import com.auralis.music.data.network.YouTubeMusicLibrary
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.profile.SpotifyLogoIcon
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

/**
 * The phone's web sign-in for YouTube and Spotify: Android WebViews whose cookies become the
 * sessions. Shared screens reach them through [LocalWebSignIn].
 */
val PhoneWebSignIn = WebSignIn(
    spotifySignIn = { onSignedIn, onDismiss -> SpotifySignInScreen(onSignedIn, onDismiss) },
    youTubeSignIn = { onSignedIn, onCancel -> YouTubeSignInWebView(onSignedIn, onCancel) },
    signOutOfYouTube = { signOutOfYouTube() }
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifySignInScreen(onSignedIn: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadSequence by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(loadSequence) {
        if (loadSequence > 0) {
            delay(15_000)
            if (loading && !checking) pageError = true
        }
    }

    fun checkSession() {
        if (checking) return
        val cookies = CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
        if (!SpotifySession.hasAuth(cookies, "")) return
        checking = true
        scope.launch {
            val token = SpotifySession.fetchWebPlayerToken(cookies)
            val profile = token?.let { SpotifySession.fetchUserProfile(it) }
            if (token != null) {
                CookieManager.getInstance().flush()
                SpotifySession.save(cookies, token, System.currentTimeMillis() + 3_000_000L,
                    profile?.displayName.orEmpty().ifBlank { "Spotify" },
                    profile?.id.orEmpty(), profile?.avatarUrl.orEmpty())
                onSignedIn()
            } else {
                android.util.Log.w("SpotifyLogin", "Could not obtain an authenticated Spotify token")
                error = true
                checking = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.dynamicBackground)
        .statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
            }
            Text(str(R.string.sign_in_to_spotify), fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                pageError = false
                error = false
                loading = true
                webView?.loadUrl(SPOTIFY_LOGIN_URL)
            }) { Text(str(R.string.try_again), color = SPOTIFY_GREEN) }
        }
        if (loading || checking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SPOTIFY_GREEN)
        Box(Modifier.fillMaxWidth().weight(1f)) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.userAgentString = SpotifySession.USER_AGENT
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress == 100 && !checking) loading = false
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                            pageError = false
                            loadSequence++
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.isForMainFrame && request.url.host == "open.spotify.com") {
                                val cookies = CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
                                if (SpotifySession.hasAuth(cookies, "")) {
                                    checkSession()
                                    return true
                                }
                            }
                            return request.url.scheme !in listOf("https", "http")
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            when (Uri.parse(url).host) {
                                "accounts.spotify.com" -> repairSpotifyLoginLayout(view)
                                "open.spotify.com" -> checkSession()
                            }
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) {
                                loading = false
                                pageError = true
                                android.util.Log.w("SpotifyLogin", "Page load failed: ${error.errorCode} ${error.description}")
                            }
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) {
                                loading = false
                                pageError = true
                                android.util.Log.w("SpotifyLogin", "Page returned HTTP ${response.statusCode}")
                            }
                        }
                    }
                    loadUrl(SPOTIFY_LOGIN_URL)
                }
            },
            onRelease = { view -> if (webView === view) webView = null; view.destroy() }
          )
          if (checking || error || pageError) {
              Column(
                  modifier = Modifier.fillMaxSize().background(MaterialTheme.dynamicBackground),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center
              ) {
                  if (checking) CircularProgressIndicator(color = SPOTIFY_GREEN)
                  Text(
                      text = if (pageError) str(R.string.spotify_login_page_failed)
                          else if (error) str(R.string.spotify_sign_in_failed)
                          else str(R.string.connecting_to_spotify),
                      color = if (pageError || error) MaterialTheme.colorScheme.error
                          else MaterialTheme.colorScheme.onBackground,
                      modifier = Modifier.padding(16.dp)
                  )
                  if (pageError || error) TextButton(onClick = {
                      pageError = false
                      error = false
                      loading = true
                      webView?.loadUrl(SPOTIFY_LOGIN_URL)
                  }) { Text(str(R.string.try_again), color = SPOTIFY_GREEN) }
              }
          }
        }
    }
}

/** Spotify's login shell can clip its form to a zero-height main element in WebView. */
private fun repairSpotifyLoginLayout(view: WebView) {
    view.evaluateJavascript(
        """(function() {
          if (document.getElementById('auralis-spotify-layout')) return;
          var style = document.createElement('style');
          style.id = 'auralis-spotify-layout';
          style.textContent =
            'html, body { height: auto !important; min-height: 100% !important; overflow: visible !important; }' +
            'body > div { height: auto !important; min-height: 100% !important; }' +
            'main { position: static !important; height: auto !important; min-height: 100dvh !important; max-height: none !important; overflow: visible !important; }';
          document.head.appendChild(style);
        })();""".trimIndent(), null
    )
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
            error = str(R.string.couldn_t_finish_signing_in_make_sure_you)
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
                        contentDescription = str(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = str(R.string.sign_in_to_youtube),
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
