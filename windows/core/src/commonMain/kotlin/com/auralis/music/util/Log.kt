package com.auralis.music.util

/**
 * Logging for shared code, shaped like android.util.Log so moved code keeps its calls as they
 * were. On Android it is android.util.Log itself, so logcat output is unchanged.
 */
expect object Log {
    fun d(tag: String, msg: String): Int
    fun d(tag: String, msg: String, tr: Throwable?): Int
    fun i(tag: String, msg: String): Int
    fun i(tag: String, msg: String, tr: Throwable?): Int
    fun w(tag: String, msg: String): Int
    fun w(tag: String, msg: String, tr: Throwable?): Int
    fun e(tag: String, msg: String): Int
    fun e(tag: String, msg: String, tr: Throwable?): Int
}
