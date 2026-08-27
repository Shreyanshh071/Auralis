package com.auralis.music.domain.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.auralis.music.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GoogleUserAccount(
    val email: String,
    val displayName: String,
    val avatarUrl: String?,
    val idToken: String
)

class GoogleSignInHelper(
    private val context: Context
) {
    private val credentialManager = CredentialManager.create(context)
    private val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID

    /**
     * Authenticates with Google Credential Manager (ID Token).
     * Requires an Activity context to display the system account selector bottom sheet.
     */
    suspend fun signIn(activity: Activity): GoogleUserAccount? = withContext(Dispatchers.IO) {
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response: GetCredentialResponse = credentialManager.getCredential(
                context = activity,
                request = request
            )

            val credential = response.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
                return@withContext GoogleUserAccount(
                    email = googleIdToken.id,
                    displayName = googleIdToken.displayName ?: googleIdToken.givenName ?: "Google User",
                    avatarUrl = googleIdToken.profilePictureUri?.toString(),
                    idToken = googleIdToken.idToken
                )
            }
            null
        } catch (e: GetCredentialCancellationException) {
            // User dismissed or cancelled the Google Account picker dialog
            null
        } catch (e: GetCredentialException) {
            // Surface real Credential Manager error (e.g. Developer Error / SHA-1 / configuration)
            throw RuntimeException(e.localizedMessage ?: "Google Sign-In failed (${e::class.simpleName})", e)
        } catch (e: Exception) {
            throw e
        }
    }
}

