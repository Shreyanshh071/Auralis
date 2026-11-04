package com.auralis.music

import com.auralis.music.data.network.JioSaavnAudio
import com.auralis.music.domain.model.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class JioSaavnAudioTest {
    private val target = Track(id = "yt", title = "Tum Hi Ho", artist = "Arijit Singh", album = "Aashiqui 2", duration = 262)
    private fun row(id: String = "a", title: String = "Tum Hi Ho", artist: String = "Arijit Singh",
        duration: Long = 262, has320: Boolean = true, explicit: Boolean = false) =
        JioSaavnAudio.Candidate(id, title, artist, "Aashiqui 2", duration, explicit, has320, "enc")

    @Test fun `the same recording with a 320 file is used`() {
        assertEquals("a", JioSaavnAudio.pick(target, listOf(row()))?.id)
    }

    @Test fun `a song without a real 320 file stays on YouTube`() {
        assertNull(JioSaavnAudio.pick(target, listOf(row(has320 = false))))
    }

    @Test fun `a different length is refused so lyrics stay in time`() {
        assertNull(JioSaavnAudio.pick(target, listOf(row(duration = 266))))
        assertEquals("a", JioSaavnAudio.pick(target, listOf(row(duration = 264)))?.id)
    }

    @Test fun `another song from the same film is refused`() {
        assertNull(JioSaavnAudio.pick(target, listOf(row(title = "Sunn Raha Hai"))))
    }

    @Test fun `another artist's version is refused`() {
        assertNull(JioSaavnAudio.pick(target, listOf(row(artist = "Someone Else"))))
    }

    @Test fun `a remix of the song is refused`() {
        assertNull(JioSaavnAudio.pick(target, listOf(row(title = "Tum Hi Ho (Remix)"))))
    }

    @Test fun `an unknown length is never guessed at`() {
        assertNull(JioSaavnAudio.pick(target.copy(duration = 0), listOf(row())))
    }

    @Test fun `the uncensored row wins over its clean copy`() {
        val picked = JioSaavnAudio.pick(target, listOf(row(id = "clean"), row(id = "explicit", explicit = true)))
        assertEquals("explicit", picked?.id)
    }

    @Test fun `links decrypt and point at the 320 file, keeping their parameters`() {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
        val plain = "http://aac.saavncdn.com/123/song_96.mp4?token=1"
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray()))
        assertEquals(plain, JioSaavnAudio.decrypt(encrypted))
        assertEquals("https://aac.saavncdn.com/123/song_320.mp4?token=1", JioSaavnAudio.stream320(plain))
    }

    @Test fun `a link with no readable rendition is not labelled 320`() {
        assertNull(JioSaavnAudio.stream320("https://aac.saavncdn.com/123/song.mp4"))
    }

    @Test fun `search rows read their details and decode titles`() {
        val row = JioSaavnAudio.parseSearch("""{"results":[{"id":"x","title":"Rock &amp; Roll","explicit_content":"1",
            "more_info":{"album":"Album","duration":"200","320kbps":"true","encrypted_media_url":"enc",
            "artistMap":{"primary_artists":[{"name":"One"},{"name":"Two"}]}}}]}""").single()
        assertEquals("Rock & Roll", row.title)
        assertEquals("One, Two", row.artist)
        assertEquals(200L, row.durationSec)
        assertTrue(row.has320 && row.explicit)
    }

    @Test fun `details answers are read in either shape`() {
        val song = JSONObject("""{"id":"x","title":"T","more_info":{"encrypted_media_url":"enc"}}""")
        assertEquals("enc", JioSaavnAudio.parseDetails(JSONObject().put("x", song).toString())?.encryptedUrl)
        assertEquals("enc", JioSaavnAudio.parseDetails("""{"songs":[$song]}""")?.encryptedUrl)
    }
}
