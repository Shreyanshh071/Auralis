package com.auralis.music.windows.immersive

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.chrono.IsoChronology
import java.util.Locale

internal data class TimeFormatSpec(
    val use24Hour: Boolean,
    val zeroPadHour: Boolean
)

/** Strips ICU/Windows single-quoted literal segments (e.g. `'h'` in `HH 'h' mm`) before inspecting pattern tokens. */
internal fun stripQuotedDateFormatLiterals(pattern: String): String {
    if ('\'' !in pattern) return pattern
    val out = StringBuilder(pattern.length)
    var inQuotes = false
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '\'') {
            if (inQuotes && i + 1 < pattern.length && pattern[i + 1] == '\'') {
                i += 2
                continue
            }
            inQuotes = !inQuotes
        } else if (!inQuotes) {
            out.append(c)
        }
        i++
    }
    return out.toString()
}

internal fun parseTimePatternFormat(pattern: String): TimeFormatSpec? {
    val unquoted = stripQuotedDateFormatLiterals(pattern)
    return when {
        unquoted.any { it == 'H' || it == 'k' } -> TimeFormatSpec(
            use24Hour = true,
            zeroPadHour = unquoted.contains("HH") || unquoted.contains("kk")
        )
        unquoted.any { it == 'h' || it == 'K' } -> TimeFormatSpec(
            use24Hour = false,
            zeroPadHour = false
        )
        else -> null
    }
}

internal fun localeTimeFormatSpec(locale: Locale = Locale.getDefault()): TimeFormatSpec {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        null,
        FormatStyle.SHORT,
        IsoChronology.INSTANCE,
        locale
    )
    return parseTimePatternFormat(pattern) ?: TimeFormatSpec(use24Hour = false, zeroPadHour = false)
}

/**
 * Resolves the OS clock's 12-hour vs 24-hour preference so the lock screen and immersive clocks
 * match the system clock even when the JVM's [Locale] region defaults to a different style.
 */
internal object SystemClockFormat {
    private const val CF_STRING_ENCODING_UTF8 = 0x08000100
    private const val CF_NUMBER_SINT32_TYPE = 3L
    private const val CF_DATE_FORMATTER_NO_STYLE = 0L
    private const val CF_DATE_FORMATTER_SHORT_STYLE = 1L
    private const val CLOCK_DOMAIN = "com.apple.menuextra.clock"
    private const val WIN_INTL_KEY = "Control Panel\\International"

    private interface CoreFoundationClockLib : Library {
        fun CFPreferencesAppSynchronize(applicationID: Pointer?): Byte
        fun CFPreferencesCopyAppValue(key: Pointer?, applicationID: Pointer?): Pointer?
        fun CFStringCreateWithCString(alloc: Pointer?, cStr: String, encoding: Int): Pointer?
        fun CFGetTypeID(cf: Pointer?): Long
        fun CFBooleanGetTypeID(): Long
        fun CFBooleanGetValue(boolean: Pointer?): Byte
        fun CFNumberGetTypeID(): Long
        fun CFNumberGetValue(number: Pointer?, theType: Long, valuePtr: Pointer): Byte
        fun CFStringGetTypeID(): Long
        fun CFStringGetLength(theString: Pointer?): Long
        fun CFStringGetMaximumSizeForEncoding(length: Long, encoding: Int): Long
        fun CFStringGetCString(
            theString: Pointer?,
            buffer: ByteArray,
            bufferSize: Long,
            encoding: Int
        ): Byte
        fun CFLocaleCopyCurrent(): Pointer?
        fun CFDateFormatterCreate(
            allocator: Pointer?,
            locale: Pointer?,
            dateStyle: Long,
            timeStyle: Long
        ): Pointer?
        fun CFDateFormatterGetFormat(formatter: Pointer?): Pointer?
        fun CFRelease(cf: Pointer?)
    }

    private data class MacBindings(
        val cf: CoreFoundationClockLib,
        val anyApplication: Pointer,
        val clockDomain: Pointer
    )

    @Volatile private var cachedMacBindings: MacBindings? = null

    private fun macBindings(): MacBindings? {
        if (!isMacLockScreenHost()) return null
        cachedMacBindings?.let { return it }
        return synchronized(this) {
            cachedMacBindings ?: runCatching {
                val cfNative = NativeLibrary.getInstance("CoreFoundation")
                val cf = Native.load("CoreFoundation", CoreFoundationClockLib::class.java)
                val anyApp = cfNative.getGlobalVariableAddress("kCFPreferencesAnyApplication").getPointer(0L)
                    ?: return@runCatching null
                val clockDomain = cf.CFStringCreateWithCString(null, CLOCK_DOMAIN, CF_STRING_ENCODING_UTF8)
                    ?: return@runCatching null
                MacBindings(cf = cf, anyApplication = anyApp, clockDomain = clockDomain)
            }.getOrNull()?.also { cachedMacBindings = it }
        }
    }

    fun current(): TimeFormatSpec {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val resolved = when {
            os.contains("mac") -> readMacTimeFormat()
            os.contains("win") -> readWindowsTimeFormat()
            else -> null
        }
        return resolved ?: localeTimeFormatSpec()
    }

    private fun readMacTimeFormat(): TimeFormatSpec? {
        val b = macBindings() ?: return null
        val cf = b.cf
        cf.CFPreferencesAppSynchronize(b.anyApplication)
        cf.CFPreferencesAppSynchronize(b.clockDomain)

        val force24 = readPrefBool(b, "AppleICUForce24HourTime", b.anyApplication)
        val force12 = readPrefBool(b, "AppleICUForce12HourTime", b.anyApplication)
        val cfFormatterSpec = if (force24 == false || force12 == true) null else readCfFormatterSpec(cf)

        if (force24 == true) {
            return TimeFormatSpec(
                use24Hour = true,
                zeroPadHour = cfFormatterSpec?.takeIf { it.use24Hour }?.zeroPadHour ?: true
            )
        }
        if (force12 == true) {
            return TimeFormatSpec(use24Hour = false, zeroPadHour = false)
        }

        val show24 = readPrefBool(b, "Show24Hour", b.clockDomain)
        if (show24 != null) {
            return TimeFormatSpec(
                use24Hour = show24,
                zeroPadHour = if (show24) cfFormatterSpec?.takeIf { it.use24Hour }?.zeroPadHour ?: true else false
            )
        }

        val isAnalog = readPrefBool(b, "IsAnalog", b.clockDomain) == true
        val menuFormat = if (!isAnalog) {
            readPrefString(b, "DateFormat", b.clockDomain)?.let(::parseTimePatternFormat)
        } else {
            null
        }
        if (menuFormat?.use24Hour == true) return menuFormat
        if (cfFormatterSpec != null) return cfFormatterSpec
        return menuFormat
    }

    private fun readCfFormatterSpec(cf: CoreFoundationClockLib): TimeFormatSpec? {
        val locale = cf.CFLocaleCopyCurrent() ?: return null
        return try {
            val formatter = cf.CFDateFormatterCreate(
                null,
                locale,
                CF_DATE_FORMATTER_NO_STYLE,
                CF_DATE_FORMATTER_SHORT_STYLE
            ) ?: return null
            try {
                // CFDateFormatterGetFormat follows CoreFoundation's Get Rule (not retained).
                val formatRef = cf.CFDateFormatterGetFormat(formatter) ?: return null
                readCfString(cf, formatRef)?.let(::parseTimePatternFormat)
            } finally {
                cf.CFRelease(formatter)
            }
        } finally {
            cf.CFRelease(locale)
        }
    }

    private fun readPrefBool(b: MacBindings, key: String, domain: Pointer): Boolean? {
        val cfKey = b.cf.CFStringCreateWithCString(null, key, CF_STRING_ENCODING_UTF8) ?: return null
        val value = try {
            b.cf.CFPreferencesCopyAppValue(cfKey, domain)
        } finally {
            b.cf.CFRelease(cfKey)
        } ?: return null
        return try {
            readCfBoolean(b.cf, value)
        } finally {
            b.cf.CFRelease(value)
        }
    }

    private fun readPrefString(b: MacBindings, key: String, domain: Pointer): String? {
        val cfKey = b.cf.CFStringCreateWithCString(null, key, CF_STRING_ENCODING_UTF8) ?: return null
        val value = try {
            b.cf.CFPreferencesCopyAppValue(cfKey, domain)
        } finally {
            b.cf.CFRelease(cfKey)
        } ?: return null
        return try {
            readCfString(b.cf, value)
        } finally {
            b.cf.CFRelease(value)
        }
    }

    private fun readCfBoolean(cf: CoreFoundationClockLib, ref: Pointer): Boolean? = when (cf.CFGetTypeID(ref)) {
        cf.CFBooleanGetTypeID() -> cf.CFBooleanGetValue(ref) != 0.toByte()
        cf.CFNumberGetTypeID() -> {
            val mem = Memory(4L)
            if (cf.CFNumberGetValue(ref, CF_NUMBER_SINT32_TYPE, mem) != 0.toByte()) {
                mem.getInt(0L) != 0
            } else null
        }
        cf.CFStringGetTypeID() -> when (readCfString(cf, ref)?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
        else -> null
    }

    private fun readCfString(cf: CoreFoundationClockLib, ref: Pointer): String? {
        if (cf.CFGetTypeID(ref) != cf.CFStringGetTypeID()) return null
        val len = cf.CFStringGetLength(ref)
        if (len <= 0L) return ""
        val maxBytes = cf.CFStringGetMaximumSizeForEncoding(len, CF_STRING_ENCODING_UTF8) + 1L
        if (maxBytes <= 1L || maxBytes > 16384L) return null
        val buf = ByteArray(maxBytes.toInt())
        if (cf.CFStringGetCString(ref, buf, maxBytes, CF_STRING_ENCODING_UTF8) == 0.toByte()) return null
        val end = buf.indexOf(0.toByte()).let { if (it < 0) buf.size else it }
        return String(buf, 0, end, Charsets.UTF_8)
    }

    private fun readWindowsTimeFormat(): TimeFormatSpec? = runCatching {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, WIN_INTL_KEY, "sShortTime")) {
            val shortTime = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, WIN_INTL_KEY, "sShortTime")
            parseTimePatternFormat(shortTime)?.let { return@runCatching it }
        }
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, WIN_INTL_KEY, "iTime")) {
            val iTime = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, WIN_INTL_KEY, "iTime").trim()
            if (iTime == "1") return@runCatching TimeFormatSpec(use24Hour = true, zeroPadHour = true)
            if (iTime == "0") return@runCatching TimeFormatSpec(use24Hour = false, zeroPadHour = false)
        }
        null
    }.getOrNull()
}
