package com.auralis.music.util

import java.io.File
import java.io.PrintWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Logcat-style lines on stderr ("D/Tag: message"), and in a session log file once
 * [logToFile] is called: an installed app has no console, so the file is where its log lives.
 */
actual object Log {
    actual fun d(tag: String, msg: String): Int = write('D', tag, msg, null)
    actual fun d(tag: String, msg: String, tr: Throwable?): Int = write('D', tag, msg, tr)
    actual fun i(tag: String, msg: String): Int = write('I', tag, msg, null)
    actual fun i(tag: String, msg: String, tr: Throwable?): Int = write('I', tag, msg, tr)
    actual fun w(tag: String, msg: String): Int = write('W', tag, msg, null)
    actual fun w(tag: String, msg: String, tr: Throwable?): Int = write('W', tag, msg, tr)
    actual fun e(tag: String, msg: String): Int = write('E', tag, msg, null)
    actual fun e(tag: String, msg: String, tr: Throwable?): Int = write('E', tag, msg, tr)

    @Volatile private var file: PrintWriter? = null
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /**
     * Also writes every line to [log] (this session), keeping the previous session's file as
     * `<name>.old`. Lines are flushed at once so the file is complete even after a crash.
     */
    fun logToFile(log: File, append: Boolean = false) {
        runCatching {
            log.parentFile?.mkdirs()
            if (!append && log.exists()) {
                val old = File(log.parentFile, log.name + ".old")
                old.delete()
                log.renameTo(old)
            }
            file = PrintWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(log, append || !log.exists()), Charsets.UTF_8), true)
        }
    }

    private fun write(level: Char, tag: String, msg: String, tr: Throwable?): Int {
        val line = "$level/$tag: $msg"
        System.err.println(line)
        tr?.printStackTrace()
        file?.let { out ->
            synchronized(this) {
                out.println("${LocalTime.now().format(time)} $line")
                tr?.printStackTrace(out)
            }
        }
        return line.length
    }
}
