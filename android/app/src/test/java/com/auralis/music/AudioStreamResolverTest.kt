package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AudioStreamResolverTest {

    @Test
    fun testDesDecryptionOnRealSamples() {
        val samples = listOf(
            "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDylhOEa6MwiBv8RbWyeZkFSVaIZ4YhaGLZcsfH8FJk/dAB6KbI/Bd0EBw7tS9a8Gtq",
            "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDy513eMCDw7bB0+Y4Uyf+iqG+M3J8fJ+9yW53G1ZeUJ8ANK6O9LQdZgBw7tS9a8Gtq",
            "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDya0uCjNMz3vGrG/pzVyExLJBWGtrHUtnTZ5lzrSbho/3KYZFUau5nChw7tS9a8Gtq",
            "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyUAfhmijvFBT8pPh5PqKgzsRHfUZzMKSjj3NUx57Nm2u/4CmI0GLa9hw7tS9a8Gtq"
        )

        for (enc in samples) {
            try {
                val key = "38346564".toByteArray(Charsets.UTF_8)
                val secretKey = SecretKeySpec(key, "DES")
                val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey)
                val decoded = java.util.Base64.getDecoder().decode(enc.trim())
                val decrypted = cipher.doFinal(decoded)
                val url = String(decrypted, Charsets.UTF_8)
                println("SUCCESS DECRYPTING: $url")
            } catch (e: Exception) {
                println("FAILED DECRYPTING: ${e.message}")
            }
        }
    }

    @Test
    fun testFailedDesReturnsNull() {
        val invalidEnc = "INVALID_CORRUPTED_BASE64_PADDING"
        val result = AudioStreamResolver.decryptSaavnMediaUrl(invalidEnc)
        assertNull("Failed DES decryption must return null, not a fabricated URL", result)
    }

    @Test
    fun testSaavnDirectStreamResolution() = runBlocking {
        val streamUrl = AudioStreamResolver.resolveAudioStream(
            videoId = "kJQP7kiw5Fk",
            title = "Every Breath You Take",
            artist = "The Police"
        )
        println("Resolved Stream URL: $streamUrl")
    }
}
