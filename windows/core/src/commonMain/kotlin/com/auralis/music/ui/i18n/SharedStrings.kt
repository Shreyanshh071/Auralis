package com.auralis.music.ui.i18n

import androidx.compose.runtime.mutableIntStateOf
import java.util.Locale

/**
 * A UI string, by its name in the phone's res/values/strings.xml. The generated [Strings] object
 * has one per string (`Strings.history`), so shared UI writes `str(Strings.history)` where the
 * phone wrote `str(R.string.history)`.
 */
@JvmInline
value class StringId(val key: String)

/**
 * Where shared UI text comes from. Each app sets [resolve] at startup: the phone reads its own
 * string resources (its translations and app-language setting unchanged), the desktop app reads
 * the same strings.xml files bundled as resources.
 */
object StringLookup {
    /** Bumped when the language changes; [str] reads it so text recomposes. */
    val revision = mutableIntStateOf(0)

    /** The string for a key in the current language, or null when unknown. */
    @Volatile var resolve: (key: String) -> String? = { null }

    /** Locale for %-formatting, as Android's getString(id, args) uses the resources' locale. */
    @Volatile var formatLocale: () -> Locale = { Locale.getDefault() }
}

/** UI text in the app language. Works outside composition too. */
fun str(id: StringId): String {
    StringLookup.revision.intValue
    return StringLookup.resolve(id.key) ?: id.key
}

fun str(id: StringId, vararg args: Any?): String {
    StringLookup.revision.intValue
    val template = StringLookup.resolve(id.key) ?: return id.key
    return String.format(StringLookup.formatLocale(), template, *args)
}
