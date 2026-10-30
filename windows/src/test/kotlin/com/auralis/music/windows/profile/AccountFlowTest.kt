package com.auralis.music.windows.profile

import com.auralis.music.windows.together.FirestoreRest
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.test.*

class AccountFlowTest {
    @Test fun googlePkceMatchesRfcVectorAndEscapesCallbackValues() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", DesktopGoogleLogin.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals("a+b/c", DesktopGoogleLogin.parseQuery("code=a%2Bb%2Fc&state=abc")["code"])
    }

    @Test fun loopbackRejectsWrongStateThenAcceptsCancellation() = runBlocking {
        val failure = runCatching {
            DesktopGoogleLogin.idToken { uri ->
                val parameters = DesktopGoogleLogin.parseQuery(uri.rawQuery)
                assertEquals("S256", parameters["code_challenge_method"])
                assertEquals("openid email profile", parameters["scope"])
                val redirect = parameters.getValue("redirect_uri")
                val invalid = URI("${redirect}?state=wrong&code=bogus").toURL().openConnection() as java.net.HttpURLConnection
                assertEquals(400, invalid.responseCode); invalid.disconnect()
                URI("${redirect}?state=${parameters.getValue("state")}&error=access_denied").toURL().openStream().use { it.readBytes() }
            }
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("cancelled or denied") == true)
    }

    @Test fun googleLoginUriContainsSelectAccountPromptAndPkce() = runBlocking {
        var capturedUri: URI? = null
        val failure = runCatching {
            DesktopGoogleLogin.idToken { uri ->
                capturedUri = uri
                val params = DesktopGoogleLogin.parseQuery(uri.rawQuery)
                assertEquals("select_account", params["prompt"])
                assertEquals("code", params["response_type"])
                assertEquals("S256", params["code_challenge_method"])
                val redirect = params.getValue("redirect_uri")
                URI("${redirect}?state=${params.getValue("state")}&error=access_denied").toURL().openStream().use { it.readBytes() }
            }
        }.exceptionOrNull()
        assertNotNull(capturedUri)
        assertTrue(failure?.message?.contains("cancelled or denied") == true)
    }

    @Test fun firebaseEmailGoogleAndRefreshUseExistingAccountRatherThanAnonymousSignup() {
        val calls = mutableListOf<String>()
        val api = FirestoreRest("test", "test-key") { url, _, body, _ ->
            calls += url
            when {
                url.contains("signInWithPassword") -> { assertEquals("person@example.com", body!!.getString("email")); session() }
                url.contains("signInWithIdp") -> { assertTrue(body!!.getString("postBody").contains("providerId=google.com")); session() }
                url.contains("securetoken") -> { assertEquals("refresh", body!!.getString("refresh_token")); JSONObject().put("id_token", "new").put("refresh_token", "refresh").put("user_id", "existing").put("expires_in", "3600") }
                url.contains("accounts:lookup") -> JSONObject().put("users", JSONArray().put(JSONObject().put("email", "person@example.com")))
                else -> error("Unexpected auth call")
            }
        }
        api.signInEmail(" person@example.com ", "test-password")
        assertEquals("existing", api.uid)
        api.signInGoogle("test-google-token")
        api.restoreAccount("refresh")
        assertEquals("person@example.com", api.accountProfile().getString("email"))
        api.deleteAnonymousAccount()
        assertEquals(4, calls.size)
        api.forgetAccount(); assertEquals("", api.uid); assertEquals("", api.accountRefreshToken())
    }

    @Test fun accountProfileResolvesAvatarFromProviderUserInfoWhenRootIsEmpty() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("account-avatar-test").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val file = java.io.File(dir, "session.bin")
            val api = FirestoreRest("test", "test-key") { url, _, _, _ ->
                when {
                    url.contains("securetoken") -> JSONObject().put("id_token", "new-id").put("refresh_token", "refresh").put("user_id", "uid").put("expires_in", "3600")
                    url.contains("accounts:lookup") -> JSONObject().put("users", JSONArray().put(
                        JSONObject()
                            .put("email", "alter@example.com")
                            .put("displayName", "Alter")
                            .put("photoUrl", "") // empty root photo
                            .put("providerUserInfo", JSONArray().put(
                                JSONObject()
                                    .put("providerId", "google.com")
                                    .put("displayName", "Alter Google")
                                    .put("photoUrl", "https://lh3.googleusercontent.com/avatar123")
                            ))
                    ))
                    else -> error("Unexpected url: $url")
                }
            }
            val account = DesktopAccount(file, scope, api, restoreRetryMs = 500)
            file.writeText(JSONObject().put("refreshToken", "refresh").put("profile", JSONObject()
                .put("uid", "uid").put("displayName", "Alter").put("email", "alter@example.com").put("avatarUrl", "")).toString())
            val restored = DesktopAccount(file, scope, api, restoreRetryMs = 500)
            kotlinx.coroutines.withTimeout(5000) {
                while (restored.state.value.profile?.avatarUrl == null) kotlinx.coroutines.delay(10)
            }
            assertEquals("https://lh3.googleusercontent.com/avatar123", restored.state.value.profile?.avatarUrl)
            assertEquals("Alter", restored.state.value.profile?.displayName)
            assertTrue(restored.state.value.profile?.isGoogleConnected == true)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            dir.deleteRecursively()
        }
    }

    @Test fun windowsCanProtectAndRestoreSignInWithoutPlaintextOnDisk() {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (!isWindows) return
        val token = "temporary-test-token".toByteArray()
        val protected = Crypt32Util.cryptProtectData(token)
        assertFalse(protected.contentEquals(token))
        assertEquals("temporary-test-token", String(Crypt32Util.cryptUnprotectData(protected)))
    }

    private fun session() = JSONObject().put("idToken", "id").put("refreshToken", "refresh").put("localId", "existing").put("expiresIn", "3600")
}
