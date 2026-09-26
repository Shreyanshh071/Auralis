package com.auralis.music.data.network.discord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class DiscordOAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long
)

/** Keeps SDK OAuth credentials encrypted with a key that never leaves Android Keystore. */
internal class DiscordOAuthTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("discord_social_sdk_auth", Context.MODE_PRIVATE)

    @Synchronized
    fun save(tokens: DiscordOAuthTokens): Boolean = runCatching {
        val payload = JSONObject()
            .put("access", tokens.accessToken)
            .put("refresh", tokens.refreshToken)
            .put("expires", tokens.expiresAtMs)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(payload)
        preferences.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }.onFailure { Log.e(TAG, "Could not securely save Discord authorization", it) }
        .getOrDefault(false)

    @Synchronized
    fun load(): DiscordOAuthTokens? = runCatching {
        val iv = preferences.getString("iv", null) ?: return null
        val payload = preferences.getString("payload", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val json = JSONObject(String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)), Charsets.UTF_8))
        DiscordOAuthTokens(
            json.getString("access"),
            json.getString("refresh"),
            json.getLong("expires")
        )
    }.onFailure { Log.e(TAG, "Could not restore Discord authorization", it) }
        .getOrNull()

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "AuralisDiscord"
        const val KEY_ALIAS = "auralis_discord_social_sdk_oauth"
    }
}
