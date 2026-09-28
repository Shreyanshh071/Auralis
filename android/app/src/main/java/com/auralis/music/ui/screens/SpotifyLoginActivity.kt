package com.auralis.music.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.auralis.music.data.network.SpotifySession
import kotlinx.coroutines.launch

/** A native WebView window avoids AndroidView's black surface inside the Compose profile page. */
class SpotifyLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private var connecting = false
    private var tokenAttempted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val background = Color.rgb(19, 17, 15)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            isFocusableInTouchMode = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(16), 0)
            setBackgroundColor(background)
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(52)))
        header.addView(TextView(this).apply {
            text = "Sign in to Spotify"
            textSize = 18f
            setTextColor(Color.WHITE)
        })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(72, 32, 32))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }

        CookieManager.getInstance().setAcceptCookie(true)
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            val mobileUserAgent = settings.userAgentString.orEmpty()
            settings.userAgentString = mobileUserAgent
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    this@SpotifyLoginActivity.progress.isIndeterminate = false
                    this@SpotifyLoginActivity.progress.progress = newProgress
                    this@SpotifyLoginActivity.progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme.orEmpty().lowercase()
                    if (scheme == "http" || scheme == "https") return false
                    return try {
                        startActivity(android.content.Intent.parseUri(request.url.toString(), android.content.Intent.URI_INTENT_SCHEME))
                        true
                    } catch (_: Exception) {
                        true
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    message.visibility = View.GONE
                    completeIfSignedIn()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    completeIfSignedIn()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showError("Spotify sign-in couldn't load: ${error.description}")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) showError("Spotify sign-in returned HTTP ${response.statusCode}.")
                }
            }
        }
        content.addView(webView, FrameLayout.LayoutParams(-1, -1))
        content.addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        root.requestFocus()
        webView.loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
    }

    private fun completeIfSignedIn() {
        if (connecting || tokenAttempted) return
        val cookies = listOf("https://open.spotify.com", "https://spotify.com", "https://accounts.spotify.com")
            .map { CookieManager.getInstance().getCookie(it).orEmpty() }
            .filter(String::isNotBlank)
            .joinToString("; ")
        if (!SpotifySession.hasAuth(cookies, "")) return

        connecting = true
        tokenAttempted = true
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        lifecycleScope.launch {
            val token = SpotifySession.fetchWebPlayerToken(cookies)
            if (token.isNullOrBlank()) {
                connecting = false
                progress.visibility = View.INVISIBLE
                showError("Spotify opened, but Auralis couldn't access your library. You can still import public playlists by link.")
                return@launch
            }
            val profile = SpotifySession.fetchUserProfile(token)
            CookieManager.getInstance().flush()
            SpotifySession.save(
                cookie = cookies,
                accessToken = token,
                expiresAtMs = System.currentTimeMillis() + 3_600_000L,
                accountLabel = profile?.displayName?.ifBlank { "Signed in" } ?: "Signed in",
                userId = profile?.id.orEmpty(),
                avatarUrl = profile?.avatarUrl.orEmpty()
            )
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun showError(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
