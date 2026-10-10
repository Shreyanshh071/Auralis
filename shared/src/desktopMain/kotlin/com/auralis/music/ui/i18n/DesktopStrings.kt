package com.auralis.music.ui.i18n

import com.auralis.music.util.Log
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The phone's strings.xml files (bundled under /i18n, one folder per language), read the way Android's resource
 * compiler reads them, so the desktop app shows the same text in the same ~60 languages.
 */
object DesktopStrings {
    private const val TAG = "DesktopStrings"

    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile private var locale: Locale = Locale.getDefault()

    /** Loads [locale]'s strings over the English defaults and points [StringLookup] at them. */
    fun install(locale: Locale = Locale.getDefault()) {
        val base = load("values")
        val overlay = folderCandidates(locale).firstNotNullOfOrNull { folder -> load(folder).takeIf { it.isNotEmpty() } }.orEmpty()
        table = base + overlay
        this.locale = locale
        StringLookup.resolve = { key -> table[key] }
        StringLookup.formatLocale = { this.locale }
        StringLookup.revision.intValue++
        Log.i(TAG, "Loaded ${table.size} strings (${overlay.size} in ${locale.toLanguageTag()})")
    }

    /** Android resource folder names for [locale], most specific first. */
    internal fun folderCandidates(locale: Locale): List<String> {
        val lang = when (locale.language) {
            "id" -> "in"   // Android's legacy codes
            "he" -> "iw"
            "yi" -> "ji"
            else -> locale.language
        }
        val out = mutableListOf<String>()
        if (locale.script.isNotEmpty()) out += "values-b+$lang+${locale.script}"
        if (lang == "zh" && locale.country.isEmpty()) out += if (locale.script == "Hant") "values-zh-rTW" else "values-zh-rCN"
        if (locale.country.isNotEmpty()) out += "values-$lang-r${locale.country}"
        out += "values-$lang"
        return out
    }

    private fun load(folder: String): Map<String, String> {
        val stream = DesktopStrings::class.java.getResourceAsStream("/i18n/$folder/strings.xml") ?: return emptyMap()
        return try {
            val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
            val nodes = doc.getElementsByTagName("string")
            buildMap {
                for (i in 0 until nodes.length) {
                    val e = nodes.item(i) as Element
                    put(e.getAttribute("name"), unescape(e.textContent))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read $folder/strings.xml: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Android's string rules: double-quoted runs keep their whitespace, other whitespace runs
     * collapse to one space, and backslash escapes (\n \t \' \" \ \@ \? \uXXXX) are decoded.
     */
    internal fun unescape(raw: String): String {
        val out = StringBuilder()
        var quoted = false
        var lastWasSpace = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val n = raw[i + 1]
                    when (n) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                            hex.toIntOrNull(16)?.let { out.append(it.toChar()); i += 4 }
                        }
                        else -> out.append(n)
                    }
                    i += 2
                    lastWasSpace = false
                    continue
                }
                c == '"' -> quoted = !quoted
                c.isWhitespace() && !quoted -> {
                    if (!lastWasSpace) out.append(' ')
                    lastWasSpace = true
                    i++
                    continue
                }
                else -> out.append(c)
            }
            lastWasSpace = false
            i++
        }
        return out.toString().trim()
    }
}
