package com.auralis.music.util

/** Logcat-style lines on stderr ("D/Tag: message"). */
actual object Log {
    actual fun d(tag: String, msg: String): Int = write('D', tag, msg, null)
    actual fun d(tag: String, msg: String, tr: Throwable?): Int = write('D', tag, msg, tr)
    actual fun i(tag: String, msg: String): Int = write('I', tag, msg, null)
    actual fun i(tag: String, msg: String, tr: Throwable?): Int = write('I', tag, msg, tr)
    actual fun w(tag: String, msg: String): Int = write('W', tag, msg, null)
    actual fun w(tag: String, msg: String, tr: Throwable?): Int = write('W', tag, msg, tr)
    actual fun e(tag: String, msg: String): Int = write('E', tag, msg, null)
    actual fun e(tag: String, msg: String, tr: Throwable?): Int = write('E', tag, msg, tr)

    private fun write(level: Char, tag: String, msg: String, tr: Throwable?): Int {
        val line = "$level/$tag: $msg"
        System.err.println(line)
        tr?.printStackTrace()
        return line.length
    }
}
