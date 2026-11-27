package com.zemer.cipher

import com.auralis.music.util.Log

/**
 * Desktop stand-in for the Timber calls in the vendored zemer-cipher sources, so they stay close to
 * upstream. Debug and verbose lines are dropped unless [ZemerCipher.debugLogging]: zemer's debug
 * lines print signatures and PO tokens, which must not reach auralis.log.
 */
internal object Timber {
    fun tag(tag: String) = Tree(tag)

    class Tree(private val tag: String) {
        fun v(message: String) { if (ZemerCipher.debugLogging) Log.d(tag, message) }
        fun d(message: String) { if (ZemerCipher.debugLogging) Log.d(tag, message) }
        fun d(t: Throwable, message: String) { if (ZemerCipher.debugLogging) Log.d(tag, message, t) }
        fun i(message: String) { Log.i(tag, message) }
        fun w(message: String) { Log.w(tag, message) }
        fun w(t: Throwable, message: String) { Log.w(tag, message, t) }
        fun e(message: String) { Log.e(tag, message) }
        fun e(t: Throwable, message: String) { Log.e(tag, message, t) }
    }
}
