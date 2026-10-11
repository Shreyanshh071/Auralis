package com.auralis.music.windows.profile

import com.auralis.music.util.KeyValueStore
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.Base64

/** Existing sessions remain readable; newly saved cookies are protected by the Windows user. */
internal class ProtectedYouTubeStore(file: File) : KeyValueStore {
    private val store = PropertiesFileKeyValueStore(file)
    override fun getString(key: String, default: String): String? {
        val value = store.getString(key, default) ?: return null
        return if (key == "cookie" && value.startsWith("dpapi:")) runCatching {
            String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(value.removePrefix("dpapi:"))), Charsets.UTF_8)
        }.getOrDefault(default) else value
    }
    override fun putStrings(values: Map<String, String>) = store.putStrings(values.mapValues { (key, value) ->
        if (key == "cookie" && value.isNotEmpty()) "dpapi:" + Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))) else value
    })
    override fun clear() = store.clear()
}
