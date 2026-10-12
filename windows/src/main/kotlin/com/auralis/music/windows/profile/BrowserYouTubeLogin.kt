package com.auralis.music.windows.profile

import com.auralis.music.data.network.YouTubeSession
import com.sun.jna.platform.win32.Crypt32Util
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Enumerates profile paths only. Session values are read solely after the user picks a profile. */
internal object BrowserYouTubeLogin {
    data class Profile(val browser: String, val name: String, val database: File, val firefox: Boolean, val localState: File? = null) {
        val label get() = "$browser · $name"
    }
    fun profiles(roaming: File = File(System.getenv("APPDATA")), local: File = File(System.getenv("LOCALAPPDATA"))): List<Profile> = buildList {
        listOf("Firefox" to "Mozilla/Firefox/Profiles", "Zen" to "zen/Profiles", "LibreWolf" to "librewolf/Profiles", "Waterfox" to "Waterfox/Profiles").forEach { (browser, path) ->
            File(roaming, path).listFiles()?.filter { it.isDirectory && File(it, "cookies.sqlite").isFile }?.forEach {
                add(Profile(browser, it.name, File(it, "cookies.sqlite"), true))
            }
        }
        listOf("Chrome" to "Google/Chrome/User Data", "Edge" to "Microsoft/Edge/User Data", "Brave" to "BraveSoftware/Brave-Browser/User Data", "Vivaldi" to "Vivaldi/User Data", "Chromium" to "Chromium/User Data").forEach { (browser, path) ->
            val root = File(local, path)
            val state = File(root, "Local State")
            val names = runCatching { JSONObject(state.readText()).optJSONObject("profile")?.optJSONObject("info_cache") }.getOrNull()
            root.listFiles()?.filter { it.isDirectory && (it.name == "Default" || it.name.startsWith("Profile ")) }?.forEach {
                val database = File(it, "Network/Cookies").takeIf(File::isFile) ?: File(it, "Cookies").takeIf(File::isFile)
                if (database != null) add(Profile(browser, names?.optJSONObject(it.name)?.optString("name")?.takeIf(String::isNotBlank) ?: it.name, database, false, state))
            }
        }
    }.sortedBy { it.label }

    fun read(profile: Profile): String {
        val (cookie, protected) = readCookies(profile, "youtube.com")
        check(YouTubeSession.hasAuthCookies(cookie)) {
            if (protected) "This browser protects its session with Windows App-Bound Encryption. Use Firefox/Zen or paste a YouTube cookie."
            else "No signed-in YouTube session was found. Sign in at music.youtube.com in this profile, then try again."
        }
        return cookie
    }

    /** The profile's Spotify session cookies (sp_dc), for Spotify sign-in. */
    fun readSpotify(profile: Profile): String {
        val (cookie, protected) = readCookies(profile, "spotify.com")
        check(com.auralis.music.data.network.SpotifySession.hasAuth(cookie, "")) {
            if (protected) "This browser protects its session with Windows App-Bound Encryption. Use Firefox/Zen or paste your sp_dc cookie."
            else "No signed-in Spotify session was found. Sign in at open.spotify.com in this profile, then try again."
        }
        return cookie
    }

    /** Cookies for [domain] and its subdomains from [profile], and whether some were unreadable (App-Bound). */
    private fun readCookies(profile: Profile, domain: String): Pair<String, Boolean> {
        // Read-only SQLite transactions preserve a consistent view including Firefox's WAL;
        // no copies containing unrelated browser sessions are made or left in temp folders.
        val key = if (profile.firefox) null else runCatching {
            val wrapped = Base64.getDecoder().decode(JSONObject(profile.localState!!.readText()).getJSONObject("os_crypt").getString("encrypted_key"))
            check(String(wrapped.take(5).toByteArray(), Charsets.US_ASCII) == "DPAPI")
            Crypt32Util.cryptUnprotectData(wrapped.copyOfRange(5, wrapped.size))
        }.getOrNull()
        val cookies = linkedMapOf<String, String>()
        var protected = false
        try {
            DriverManager.getConnection("jdbc:sqlite:${profile.database.toURI()}?mode=ro").use { connection ->
                val sql = if (profile.firefox)
                    "SELECT host, name, value, NULL FROM moz_cookies WHERE (host = '$domain' OR host LIKE '%.$domain') AND (expiry = 0 OR expiry > strftime('%s','now')) ORDER BY lastAccessed"
                else "SELECT host_key, name, value, encrypted_value FROM cookies WHERE (host_key = '$domain' OR host_key LIKE '%.$domain') AND (expires_utc = 0 OR expires_utc > (strftime('%s','now') + 11644473600) * 1000000) ORDER BY last_access_utc"
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 3
                    statement.executeQuery(sql).use { rows -> while (rows.next()) {
                        val host = rows.getString(1)
                        val name = rows.getString(2)
                        val encrypted = rows.getBytes(4)
                        val value = rows.getString(3)?.takeIf(String::isNotBlank) ?: encrypted?.let {
                            if (String(it.take(3).toByteArray(), Charsets.US_ASCII) == "v20") { protected = true; null }
                            else decrypt(it, key, host)
                        }
                        if (!value.isNullOrBlank()) cookies[name] = value
                    } }
                }
            }
        } catch (_: Exception) { error("Couldn't read this profile. Close that browser normally and try again, or paste the cookie instead.") }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" } to protected
    }
    internal fun decrypt(value: ByteArray, key: ByteArray?, host: String): String? = runCatching {
        val prefix = String(value.take(3).toByteArray(), Charsets.US_ASCII)
        val plain = when (prefix) {
            "v20" -> return null
            "v10", "v11" -> {
                if (key == null || value.size < 31) return null
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, value.copyOfRange(3, 15)))
                    doFinal(value, 15, value.size - 15)
                }
            }
            else -> Crypt32Util.cryptUnprotectData(value)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
        val text = if (plain.size >= 32 && plain.take(32).toByteArray().contentEquals(digest)) plain.copyOfRange(32, plain.size) else plain
        String(text, Charsets.UTF_8)
    }.getOrNull()
}
