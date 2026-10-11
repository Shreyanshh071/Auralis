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

    @Test fun windowsCanProtectAndRestoreSignInWithoutPlaintextOnDisk() {
        val token = "temporary-test-token".toByteArray()
        val protected = Crypt32Util.cryptProtectData(token)
        assertFalse(protected.contentEquals(token))
        assertEquals("temporary-test-token", String(Crypt32Util.cryptUnprotectData(protected)))
    }

    private fun session() = JSONObject().put("idToken", "id").put("refreshToken", "refresh").put("localId", "existing").put("expiresIn", "3600")
}
