package com.auralis.music

import com.auralis.music.data.parser.QrcDecrypter
import com.auralis.music.data.parser.QrcParser
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QrcDecrypterTest {

    @Test
    fun testRealQrcDecryptionAndParsing() {
        val hexFile = File("../../scratch/creep_hex.txt")
        if (!hexFile.exists()) return

        val hex = hexFile.readText().trim()
        val decrypted = QrcDecrypter.decryptQrcHex(hex)
        assertNotNull("Decrypted QRC should not be null", decrypted)
        assertTrue("Decrypted QRC should contain XML or lyrics", decrypted!!.contains("Creep") || decrypted.contains("LyricInfo"))

        val parsed = QrcParser.parse(decrypted, LyricsProvider.QQMUSIC, "Creep", "Radiohead")
        assertNotNull("Parsed lyrics should not be null", parsed)
        assertEquals(SyncType.RICHSYNC, parsed!!.syncType)
        assertTrue("Should have multiple lyric lines", parsed.lines.size >= 10)
        assertTrue("Lines should contain word timing", parsed.lines.any { it.words?.isNotEmpty() == true })
    }
}
