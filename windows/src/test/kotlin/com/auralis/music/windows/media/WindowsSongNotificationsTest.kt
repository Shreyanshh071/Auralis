package com.auralis.music.windows.media

import com.sun.jna.platform.win32.Advapi32Util.*
import com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class WindowsSongNotificationsTest {
    @Test fun songMetadataRemainsXmlTextAndCannotBecomeCodeOrMarkup() {
        val title = "Song <&> ' \" \u0001 ${'$'}(Write-Host boom)"
        val xml = WindowsToast.xml(title, "Artist & band")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream(xml.toByteArray()))
        assertEquals(title.replace("\u0001", ""), document.getElementsByTagName("text").item(0).textContent)
        assertEquals("Artist & band", document.getElementsByTagName("text").item(1).textContent)
        assertEquals(1, document.getElementsByTagName("toast").length)
    }

    @Test fun windowsReceivesTheToastInNotificationHistory() {
        val id = "Auralis.IntegrationTest.${UUID.randomUUID()}"
        val key = "Software\\Classes\\AppUserModelId\\$id"
        try {
            // This sends a silent local test and removes it after Windows confirms delivery.
            WindowsToast.send("Auralis notification check", "Windows integration test", appId = id, verifyAndRemove = true)
            assertEquals("Auralis", registryGetStringValue(HKEY_CURRENT_USER, key, "DisplayName"))
        } finally {
            if (registryKeyExists(HKEY_CURRENT_USER, key)) registryDeleteKey(HKEY_CURRENT_USER, key)
        }
    }
}
