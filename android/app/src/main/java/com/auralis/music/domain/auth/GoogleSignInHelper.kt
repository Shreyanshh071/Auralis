package com.auralis.music.domain.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.auralis.music.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
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
     * Uses GetSignInWithGoogleOption for explicit button clicks (showing full system account chooser)
     * with graceful fallback to GetGoogleIdOption if needed.
     */
    suspend fun signIn(activity: Activity): GoogleUserAccount? = withContext(Dispatchers.IO) {
        // 1. Primary: GetSignInWithGoogleOption (Official Google API for explicit "Sign in with Google" button click)
        try {
            val signInOption = GetSignInWithGoogleOption.Builder(webClientId)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val account = executeCredentialRequest(activity, request)
            if (account != null) {
                return@withContext account
            }
        } catch (e: GetCredentialCancellationException) {
            // User explicitly dismissed or cancelled the Google Account picker dialog
            Log.d("GoogleSignInHelper", "User cancelled Google Sign-In account chooser")
            return@withContext null
        } catch (e: Exception) {
            Log.w("GoogleSignInHelper", "GetSignInWithGoogleOption failed, attempting fallback to GetGoogleIdOption: ${e.message}")
        }

        // 2. Fallback: GetGoogleIdOption (for devices/accounts configured via Google ID option)
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            return@withContext executeCredentialRequest(activity, request)
        } catch (e: GetCredentialCancellationException) {
            Log.d("GoogleSignInHelper", "User cancelled fallback Google Sign-In dialog")
            return@withContext null
        } catch (e: GetCredentialException) {
            Log.e("GoogleSignInHelper", "Google Sign-In Credential Manager error: ${e.message}", e)
            throw RuntimeException(e.localizedMessage ?: "Google Sign-In failed (${e::class.simpleName})", e)
        } catch (e: Exception) {
            Log.e("GoogleSignInHelper", "Google Sign-In unexpected error: ${e.message}", e)
            throw e
        }
    }

    private suspend fun executeCredentialRequest(activity: Activity, request: GetCredentialRequest): GoogleUserAccount? {
        val response: GetCredentialResponse = credentialManager.getCredential(
            context = activity,
            request = request
        )

        val credential = response.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
            return GoogleUserAccount(
                email = googleIdToken.id,
                displayName = googleIdToken.displayName ?: googleIdToken.givenName ?: "Google User",
                avatarUrl = googleIdToken.profilePictureUri?.toString(),
                idToken = googleIdToken.idToken
            )
        }
        return null
    }
}

