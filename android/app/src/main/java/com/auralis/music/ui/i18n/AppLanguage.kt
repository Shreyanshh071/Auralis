package com.auralis.music.ui.i18n

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Settings → Content → App language.
 *
 * Android 13+ owns the choice (the system per-app language page, fed by
 * res/xml/locales_config.xml) and applies it to the app's resources itself. Below 13 the choice
 * is ours: stored here and applied by wrapping the base context of the Application and
 * MainActivity ([wrap]).
 *
 * MainActivity declares `locale` in configChanges, so a language change is applied in place: the
 * system updates the activity's resources, [onConfigurationChanged] bumps [revision], and every
 * composable that called [str] recomposes with the new text — no recreate, so the open page,
 * back stack and scroll positions all stay where they were.
 */
object AppLanguage {
    private const val PREFS = "auralis_app_language"
    private const val KEY_TAG = "tag"

    /** Language tags with a translation (values-<tag>/strings.xml). Keep in sync with locales_config.xml. */
    val available: List<String> = listOf("en") + TranslatedLocales.tags

    /** Pre-33 choice ("" = system default). Ignored on 33+, where the system stores it. */
    fun storedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "") ?: ""

    @SuppressLint("ApplySharedPref")
    fun setStoredTag(context: Context, tag: String) {
        cachedTag = null
        _changes.value += 1
        // commit, not apply: the activity is recreated right after and must read the new value.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).commit()
    }

    /**
     * Switches the app language ("" = system default) and redraws now. Android 13+ stores it as the
     * per-app locale (shown in system settings too); older versions keep it in our preferences.
     */
    fun apply(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales =
                if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            cachedTag = null
            _changes.value += 1
            // The system delivers the new locale as a configuration change; MainActivity handles
            // locale itself, so onConfigurationChanged below redraws the text in place.
        } else {
            setStoredTag(context, tag)
            (context as? Activity)?.recreate()
        }
    }

    /** The current app language tag, or "" for system default. */
    fun currentTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
            return if (locales == null || locales.isEmpty) "" else locales[0].toLanguageTag()
        }
        return storedTag(context)
    }

    /** Pre-33: a context whose resources use the stored language. 33+: [base] unchanged. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = storedTag(base).ifBlank { return base }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    // ── Text lookup usable from anywhere (composables, click handlers, view models) ──

    private var activityRef: WeakReference<Activity>? = null
    private lateinit var appContext: Context
    private var lastLocales: LocaleList? = null

    fun init(application: Context) {
        appContext = application
    }

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
        lastLocales = activity.resources.configuration.locales
    }

    /** Call from MainActivity.onConfigurationChanged: redraws all text when the language changed. */
    fun onConfigurationChanged(activity: Activity, newConfig: Configuration) {
        val locales = newConfig.locales
        if (lastLocales != null && locales != lastLocales) {
            lastLocales = locales
            cachedTag = null
            Locale.setDefault(locales[0])
            revision.intValue += 1
            _changes.value += 1
        }
    }

    /**
     * Read by every [str] call. Inside composition that read subscribes the caller, so bumping it
     * recomposes exactly the composables that show translated text.
     */
    internal val revision = androidx.compose.runtime.mutableIntStateOf(0)

    internal fun context(): Context = activityRef?.get() ?: appContext

    /** The app language tag, or "" for system default / before init. Safe from any thread. */
    fun currentTagOrBlank(): String {
        cachedTag?.let { return it }
        if (!::appContext.isInitialized) return ""
        return currentTag(appContext).also { cachedTag = it }
    }

    @Volatile private var cachedTag: String? = null

    /** Bumped whenever the app language changes, so screens that cache network content can reload. */
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    val changes: kotlinx.coroutines.flow.StateFlow<Int> = _changes
}

/** UI text in the app language. Works outside composition too; see [AppLanguage]. */
fun str(@StringRes id: Int): String {
    AppLanguage.revision.intValue
    return AppLanguage.context().getString(id)
}

fun str(@StringRes id: Int, vararg args: Any?): String {
    AppLanguage.revision.intValue
    return AppLanguage.context().getString(id, *args)
}
