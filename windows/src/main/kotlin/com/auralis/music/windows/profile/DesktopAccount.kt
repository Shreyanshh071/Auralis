package com.auralis.music.windows.profile

import com.auralis.music.domain.auth.UserProfile
import com.auralis.music.windows.together.FirestoreRest
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Passwords are never saved. The refresh token is protected by the signed-in Windows user. */
class DesktopAccount(private val sessionFile: File, private val scope: CoroutineScope) {
    data class State(val profile: UserProfile? = null, val busy: Boolean = false, val message: String? = null, val browser: Boolean = false)
    private val mutable = MutableStateFlow(State(busy = sessionFile.isFile))
    val state = mutable.asStateFlow()
    private val api = FirestoreRest.fromResource()
    init {
        if (sessionFile.isFile) scope.launch(Dispatchers.IO) {
            try {
                api.restoreAccount(String(Crypt32Util.cryptUnprotectData(sessionFile.readBytes()), Charsets.UTF_8))
                refreshProfile(); save()
            } catch (_: Exception) { mutable.value = State(message = "Couldn't restore your sign-in. Please sign in again.") }
        }
    }
    fun signIn(email: String, password: String, create: Boolean) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        scope.launch(Dispatchers.IO) {
            try {
                api.signInEmail(email, password, create); refreshProfile()
                try { save() } catch (_: Exception) { mutable.value = mutable.value.copy(message = "Signed in for this session. Windows couldn't save the protected sign-in.") }
            } catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, browser = false, message = errorMessage(e)) }
        }
    }
    val googleAvailable: Boolean get() = DesktopGoogleLogin.available
    private var googleJob: Job? = null
    fun signInGoogle() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, browser = true, message = "Complete sign-in in your browser")
        googleJob = scope.launch(Dispatchers.IO) {
            try {
                api.signInGoogle(DesktopGoogleLogin.idToken()); refreshProfile()
                try { save() } catch (_: Exception) { mutable.value = mutable.value.copy(message = "Signed in for this session. Windows couldn't save the protected sign-in.") }
            } catch (_: CancellationException) { mutable.value = mutable.value.copy(busy = false, browser = false, message = "Google sign-in cancelled") }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, browser = false, message = errorMessage(e)) }
        }
    }
    fun cancelGoogle() { googleJob?.cancel() }
    private fun refreshProfile() {
        val profile = api.accountProfile()
        val email = profile.optString("email")
        mutable.value = State(profile = UserProfile(uid = api.uid, displayName = profile.optString("displayName").ifBlank { email.substringBefore('@') },
            email = email, avatarUrl = profile.optString("photoUrl").takeIf { it.isNotBlank() }, isGoogleConnected = false))
    }
    private fun save() {
        val protected = Crypt32Util.cryptProtectData(api.accountRefreshToken().toByteArray(Charsets.UTF_8))
        sessionFile.parentFile?.mkdirs()
        val temporary = File(sessionFile.parentFile, sessionFile.name + ".tmp")
        temporary.writeBytes(protected)
        Files.move(temporary.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    fun signOut() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        scope.launch(Dispatchers.IO) {
            try { Files.deleteIfExists(sessionFile.toPath()); api.forgetAccount(); mutable.value = State() }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, message = "Couldn't remove the saved sign-in. Try again.") }
        }
    }
    fun reset(email: String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        scope.launch(Dispatchers.IO) {
            try { api.resetPassword(email); mutable.value = mutable.value.copy(busy = false, message = "If this address has an account, check its inbox for a password reset link.") }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, browser = false, message = errorMessage(e)) }
        }
    }
    private fun errorMessage(e: Exception): String = when {
        e.message?.contains("INVALID_LOGIN_CREDENTIALS") == true || e.message?.contains("INVALID_PASSWORD") == true -> "Email or password is incorrect."
        e.message?.contains("EMAIL_EXISTS") == true -> "This email already has an account. Choose Sign in."
        e.message?.contains("OPERATION_NOT_ALLOWED") == true -> "Email sign-in is disabled in this Firebase project. Enable Email/Password in Firebase Authentication."
        e.message?.contains("TOO_MANY_ATTEMPTS") == true -> "Too many attempts. Please try again later."
        else -> "Couldn't sign in: ${e.message?.take(160) ?: "check your connection"}"
    }
}
