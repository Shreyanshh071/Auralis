package com.auralis.music.windows.notch

import com.auralis.music.util.Log
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class NotchEnvironment(val display: NotchDisplay?, val allowed: Boolean, val conflict: Boolean)

/** Public AppKit APIs only. All Cocoa access runs on the Cocoa main queue, never the EDT. */
internal class MacNotchBridge : AutoCloseable {
    private val handoff = NotchHandoff()
    private var closed = false
    private val shutdown = Thread({
        val done = CountDownLatch(1)
        Cocoa.submit { try { release() } finally { done.countDown() } }
        done.await(1200, TimeUnit.MILLISECONDS)
    }, "Auralis notch cleanup")

    init { Runtime.getRuntime().addShutdownHook(shutdown) }

    suspend fun update(wanted: Boolean, nowMs: Long): NotchEnvironment = Cocoa.onMain {
        if (closed) return@onMain NotchEnvironment(null, false, false)
        try {
            val apps = Cocoa.boringNotchApps()
            val visible = if (wanted && apps.isNotEmpty()) Cocoa.visibleWindowOwners() else emptySet()
            val allowed = handoff.update(wanted, apps.map { (pid, app) ->
                NotchPeer(pid, Cocoa.bool(app, "isHidden"), pid in visible)
            }, nowMs, hide = { pid ->
                apps[pid]?.let { Cocoa.bool(it, "hide") } ?: false
            }, restore = ::restore)
            NotchEnvironment(Cocoa.display(), allowed, wanted && !allowed)
        } catch (error: Throwable) {
            handoff.release(::restore)
            throw error
        }
    }

    private fun restore(pid: Int) {
        Cocoa.boringNotchApps()[pid]?.let { Cocoa.bool(it, "unhide") }
    }

    private fun release() {
        closed = true
        handoff.release(::restore)
    }

    override fun close() {
        Cocoa.submit {
            release()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
    }

    companion object {
        fun create(): MacNotchBridge? {
            if (!System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return null
            return runCatching { NativeLibrary.getInstance("AppKit"); Cocoa.api; MacNotchBridge() }
                .onFailure { Log.w("AuralisNotch", "Native notch unavailable", it) }.getOrNull()
        }
    }
}

private object Cocoa {
    @Structure.FieldOrder("x", "y", "width", "height")
    class Rect : Structure(), Structure.ByValue {
        @JvmField var x = 0.0
        @JvmField var y = 0.0
        @JvmField var width = 0.0
        @JvmField var height = 0.0
    }

    @Structure.FieldOrder("top", "left", "bottom", "right")
    class Insets : Structure(), Structure.ByValue {
        @JvmField var top = 0.0
        @JvmField var left = 0.0
        @JvmField var bottom = 0.0
        @JvmField var right = 0.0
    }

    interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer?, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer, value: Long): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer, value: String): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer, value: Pointer?): Pointer?
    }
    interface LongMessage : Library {
        fun objc_msgSend(receiver: Pointer?, selector: Pointer): Long
    }
    interface BoolMessage : Library {
        fun objc_msgSend(receiver: Pointer?, selector: Pointer): Byte
        fun objc_msgSend(receiver: Pointer?, selector: Pointer, value: Pointer?): Byte
    }
    interface RectMessage : Library { fun objc_msgSend(receiver: Pointer?, selector: Pointer): Rect }
    interface InsetsMessage : Library { fun objc_msgSend(receiver: Pointer?, selector: Pointer): Insets }
    interface Work : Callback { fun invoke(context: Pointer?) }
    interface Dispatch : Library { fun dispatch_async_f(queue: Pointer, context: Pointer?, work: Work) }
    interface Graphics : Library { fun CGWindowListCopyWindowInfo(options: Int, relativeTo: Int): Pointer? }
    interface CF : Library { fun CFRelease(value: Pointer) }

    val api: ObjC by lazy { Native.load("objc", ObjC::class.java) }
    private val longs by lazy { Native.load("objc", LongMessage::class.java) }
    private val bools by lazy { Native.load("objc", BoolMessage::class.java) }
    private val rects by lazy { Native.load("objc", RectMessage::class.java) }
    private val insets by lazy { Native.load("objc", InsetsMessage::class.java) }
    private val dispatch by lazy { Native.load("System.B", Dispatch::class.java) }
    private val graphics by lazy { Native.load("CoreGraphics", Graphics::class.java) }
    private val cf by lazy { Native.load("CoreFoundation", CF::class.java) }
    private val mainQueue by lazy { NativeLibrary.getInstance("System.B").getGlobalVariableAddress("_dispatch_main_q") }
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val drain = object : Work {
        override fun invoke(context: Pointer?) {
            while (true) {
                val task = tasks.poll() ?: return
                val pool = ptr(ptr(cls("NSAutoreleasePool"), "alloc"), "init")
                try { task() } catch (error: Throwable) {
                    Log.w("AuralisNotch", "Cocoa task failed", error)
                } finally { ptr(pool, "drain") }
            }
        }
    }

    fun submit(task: () -> Unit) {
        tasks.add(task)
        dispatch.dispatch_async_f(mainQueue, null, drain)
    }

    suspend fun <T> onMain(task: () -> T): T = suspendCancellableCoroutine { continuation ->
        submit {
            if (continuation.isActive) {
                try { continuation.resume(task()) }
                catch (error: Throwable) { continuation.resumeWithException(error) }
            }
        }
    }

    fun cls(name: String): Pointer? = api.objc_getClass(name)
    fun sel(name: String): Pointer = api.sel_registerName(name)
    fun ptr(receiver: Pointer?, name: String): Pointer? = api.objc_msgSend(receiver, sel(name))
    fun long(receiver: Pointer?, name: String): Long = longs.objc_msgSend(receiver, sel(name))
    fun bool(receiver: Pointer?, name: String): Boolean = bools.objc_msgSend(receiver, sel(name)) != 0.toByte()
    fun at(array: Pointer?, index: Int): Pointer? = api.objc_msgSend(array, sel("objectAtIndex:"), index.toLong())
    private fun responds(receiver: Pointer?, name: String): Boolean =
        bools.objc_msgSend(receiver, sel("respondsToSelector:"), sel(name)) != 0.toByte()

    fun boringNotchApps(): Map<Int, Pointer> {
        val bundle = api.objc_msgSend(cls("NSString"), sel("stringWithUTF8String:"), "theboringteam.boringnotch")
        val apps = api.objc_msgSend(cls("NSRunningApplication"), sel("runningApplicationsWithBundleIdentifier:"), bundle)
        return buildMap {
            for (i in 0 until long(apps, "count").toInt()) {
                val app = at(apps, i) ?: continue
                if (!bool(app, "isTerminated")) put(long(app, "processIdentifier").toInt(), app)
            }
        }
    }

    fun visibleWindowOwners(): Set<Int> {
        val list = graphics.CGWindowListCopyWindowInfo(1, 0) ?: error("Window visibility is unavailable")
        try {
            val key = api.objc_msgSend(cls("NSString"), sel("stringWithUTF8String:"), "kCGWindowOwnerPID")
            return buildSet {
                for (i in 0 until long(list, "count").toInt()) {
                    val number = api.objc_msgSend(at(list, i), sel("objectForKey:"), key)
                    add(long(number, "intValue").toInt())
                }
            }
        } finally { cf.CFRelease(list) }
    }

    fun display(): NotchDisplay? {
        val screens = ptr(cls("NSScreen"), "screens")
        val count = long(screens, "count").toInt()
        if (count == 0) return null
        val primary = rects.objc_msgSend(at(screens, 0), sel("frame"))
        var selected = ptr(cls("NSScreen"), "mainScreen") ?: at(screens, 0)
        for (i in 0 until count) {
            val screen = at(screens, i)
            if (responds(screen, "safeAreaInsets") && insets.objc_msgSend(screen, sel("safeAreaInsets")).top > 0) {
                selected = screen; break
            }
        }
        val frame = rects.objc_msgSend(selected, sel("frame"))
        val topInset = if (responds(selected, "safeAreaInsets")) insets.objc_msgSend(selected, sel("safeAreaInsets")).top else 0.0
        val left = if (responds(selected, "auxiliaryTopLeftArea")) rects.objc_msgSend(selected, sel("auxiliaryTopLeftArea")) else Rect()
        val right = if (responds(selected, "auxiliaryTopRightArea")) rects.objc_msgSend(selected, sel("auxiliaryTopRightArea")) else Rect()
        val cutout = if (topInset > 0 && left.width > 0 && right.width > 0)
            (right.x - left.x - left.width).coerceIn(0.0, frame.width / 2) else 0.0
        return NotchDisplay(frame.x, frame.y, frame.width, frame.height, primary.y + primary.height, cutout, topInset)
    }
}
