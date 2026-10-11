package com.auralis.music.windows.profile

import java.io.File
import java.sql.DriverManager
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class BrowserYouTubeLoginTest {
    @Test fun firefoxImportFiltersOtherDomainsAndExpiredCookies() {
        val root = kotlin.io.path.createTempDirectory("auralis-browser-test").toFile()
        try {
            val database = File(root, "cookies.sqlite")
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("CREATE TABLE moz_cookies(host TEXT, name TEXT, value TEXT, expiry INTEGER, lastAccessed INTEGER)")
                    sql.execute("INSERT INTO moz_cookies VALUES('.youtube.com','SAPISID','test-signing',9999999999,1),('.youtube.com','LOGIN_INFO','test-login',9999999999,2),('notyoutube.com','secret','excluded',9999999999,3),('.youtube.com','expired','excluded',1,4)")
                }
            }
            val result = BrowserYouTubeLogin.read(BrowserYouTubeLogin.Profile("Test", "fixture", database, true))
            assertTrue(result.contains("SAPISID=test-signing"))
            assertFalse(result.contains("excluded"))
            assertTrue(database.exists(), "Import must not remove the browser database")
        } finally { root.deleteRecursively() }
    }
    @Test fun chromiumGcmChecksAuthenticationAndRemovesHostDigest() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 3).toByte() }
        val host = ".youtube.com"
        val value = MessageDigest.getInstance("SHA-256").digest(host.toByteArray()) + "test-cookie".toByteArray()
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); doFinal(value)
        }
        val blob = "v10".toByteArray() + nonce + encrypted
        assertEquals("test-cookie", BrowserYouTubeLogin.decrypt(blob, key, host))
        blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
        assertNull(BrowserYouTubeLogin.decrypt(blob, key, host))
        assertNull(BrowserYouTubeLogin.decrypt("v20protected".toByteArray(), key, host))
    }
}
