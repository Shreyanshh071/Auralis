package com.auralis.music.ui.i18n

import com.auralis.music.util.Log
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject

/**
 * The phone's strings.xml files (bundled under /i18n, one folder per language), read the way Android's resource
 * compiler reads them. Complete desktop catalogs supplement the phone's partial catalogs;
 * reviewed wording takes precedence over generated baseline translations.
 */
object DesktopStrings {
    private const val TAG = "DesktopStrings"

    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile private var locale: Locale = Locale.getDefault()
    @Volatile private var uiTable: Map<String, String> = emptyMap()
    @Volatile private var templates: List<UiTemplate> = emptyList()
    private val argument = Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")
    private data class UiTemplate(val pattern: Regex, val positions: List<Int>, val translated: String)
    private val english by lazy { load("values") }
    private val bundledCatalogs = mutableMapOf<String, Map<String, String>>()

    private fun jsonCatalog(path: String): Map<String, String> = DesktopStrings::class.java.getResourceAsStream(path)?.bufferedReader()?.use {
            val json = JSONObject(it.readText())
            json.keys().asSequence().associateWith(json::getString)
        }.orEmpty()

    @Synchronized private fun catalog(tag: String): Map<String, String> = bundledCatalogs.getOrPut(tag) {
        jsonCatalog("/i18n/desktop/catalogs/$tag.json") + jsonCatalog("/i18n/desktop/reviewed/$tag.json")
    }

    /** Used by the release coverage check; an English fallback is not a translation. */
    fun missingInterfaceCopy(tag: String): Set<String> {
        if (Locale.forLanguageTag(tag).language == "en") return emptySet()
        val copy = DesktopStrings::class.java.getResourceAsStream("/i18n/desktop/interface-copy.json")!!.bufferedReader().use {
            org.json.JSONArray(it.readText())
        }
        val translated = catalog(Locale.forLanguageTag(tag).language) + catalog(tag)
        fun signature(value: String) = argument.findAll(value).map { it.value }.sorted().toList()
        return (0 until copy.length()).map(copy::getString).filter {
            translated[it].isNullOrBlank() || signature(it) != signature(translated.getValue(it))
        }.toSet()
    }
    private val desktopCatalog by lazy {
        DesktopStrings::class.java.getResourceAsStream("/i18n/desktop/translations.tsv")?.bufferedReader()?.use { reader ->
            val lines = reader.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            val languages = lines.first().split('\t')
            lines.drop(1).associate { line ->
                val cells = line.split('\t')
                cells.first() to languages.drop(1).mapIndexedNotNull { index, language ->
                    cells.getOrNull(index + 1)?.takeIf(String::isNotBlank)?.let { language to it }
                }.toMap()
            }
        }.orEmpty()
    }

    // Desktop wording that differs from the shared resource's English text.
    private val aliases = mapOf(
        "Settings" to "settings", "Home" to "home", "Library" to "library", "History" to "history",
        "Recents" to "opt_recently_played", "Shortcuts" to "speed_dial",
        "Play next" to "play_next", "Retry" to "try_again", "Try again" to "try_again",
        "Clear caches" to "clear_cache", "Use proxy" to "enable_proxy",
        "Play history" to "listen_history", "Theme & colours" to "theme_colors",
        "AI translation" to "ai_lyrics_translation", "Pin to Shortcuts" to "pin_to_speed_dial",
        "Unpin from Shortcuts" to "unpin_from_speed_dial"
    )

    /** Loads [locale]'s strings over the English defaults and points [StringLookup] at them. */
    fun install(locale: Locale = Locale.getDefault()) {
        val overlay = buildMap {
            // A regional catalogue may contain only a few overrides; retain its parent language.
            folderCandidates(locale).asReversed().forEach { folder ->
                putAll(load(folder))
            }
        }
        val generated = (catalog(locale.language) + catalog(locale.toLanguageTag())).mapKeys { normalize(it.key) }
        val reviewed = (jsonCatalog("/i18n/desktop/reviewed/${locale.language}.json") +
            jsonCatalog("/i18n/desktop/reviewed/${locale.toLanguageTag()}.json")).mapKeys { normalize(it.key) }
        val desktopTranslations = generated + desktopCatalog.mapNotNull { (label, translations) ->
            (translations[locale.toLanguageTag()] ?: translations[locale.language])?.let { normalize(label) to it }
        }.toMap() + reviewed
        val supplemental = english.mapNotNull { (key, value) -> desktopTranslations[normalize(value)]?.let { key to it } }.toMap()
        table = english + overlay + supplemental + if (locale.language == "en") mapOf(
            "pin_to_speed_dial" to "Pin to Shortcuts",
            "unpin_from_speed_dial" to "Unpin from Shortcuts"
        ) else emptyMap()
        this.locale = locale
        uiTable = buildMap {
            english.forEach { (key, value) ->
                val label = normalize(value)
                if (label !in this || key in overlay || key in supplemental) put(label, table[key] ?: value)
            }
            aliases.forEach { (label, key) -> table[key]?.let { put(normalize(label), it) } }
            putAll(desktopTranslations)
        }
        templates = (english.values + catalog(locale.language).keys + catalog(locale.toLanguageTag()).keys)
            .distinct().filter { argument.containsMatchIn(it) && argument.replace(it, "").any(Char::isLetter) }
            .sortedByDescending { argument.replace(it, "").length }.map { source ->
                val positions = mutableListOf<Int>()
                var end = 0
                val pattern = buildString {
                    append('^')
                    argument.findAll(source).forEach { match ->
                        append(Regex.escape(source.substring(end, match.range.first)))
                        if (match.value == "%%") append('%') else {
                            positions += match.groupValues[1].toIntOrNull()?.minus(1) ?: positions.size
                            append("(.+?)")
                        }
                        end = match.range.last + 1
                    }
                    append(Regex.escape(source.substring(end))); append('$')
                }
                UiTemplate(Regex(pattern, RegexOption.IGNORE_CASE), positions, uiTable[normalize(source)] ?: source)
            }
        StringLookup.resolve = { key -> table[key] }
        StringLookup.formatLocale = { this.locale }
        StringLookup.revision.intValue++
        Log.i(TAG, "Loaded ${table.size} resource strings and ${uiTable.size} interface labels for ${locale.toLanguageTag()}")
    }

    /** Only call for interface copy, never for song/artist names, paths or saved preference values. */
    fun text(english: String, vararg arguments: Any): String {
        StringLookup.revision.intValue
        val translated = if (locale.language == "en") english else uiTable[normalize(english)] ?: if (arguments.isEmpty()) {
            translateFormattedCopy(english) ?: english
        } else english
        return if (arguments.isEmpty()) translated else String.format(locale, translated, *arguments)
    }

    private fun translateFormattedCopy(copy: String): String? {
        for (template in templates) {
            val match = template.pattern.matchEntire(copy) ?: continue
            val values = template.positions.mapIndexed { index, position -> position to match.groupValues[index + 1] }.toMap()
            var next = 0
            return argument.replace(template.translated) { token ->
                if (token.value == "%%") "%" else values[token.groupValues[1].toIntOrNull()?.minus(1) ?: next++] ?: token.value
            }
        }
        return null
    }

    private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

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

/** Reading the resource revision makes desktop labels react without closing the current page. */
fun desktopText(english: String, vararg arguments: Any): String = DesktopStrings.text(english, *arguments)

/** Localize the structural part of a collection caption while retaining artist names. */
fun desktopCollectionCaption(caption: String): String {
    val parts = caption.split(" · ", limit = 2)
    if (parts.size == 2 && parts[0] in setOf("Playlist", "Album", "Artist")) {
        val detail = if (Regex("\\d+ (songs|tracks)").matches(parts[1])) desktopText(parts[1]) else parts[1]
        return "${desktopText(parts[0])} · $detail"
    }
    return caption
}
