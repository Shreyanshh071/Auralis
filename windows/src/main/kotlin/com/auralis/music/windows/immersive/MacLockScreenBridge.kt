package com.auralis.music.windows.immersive

import com.auralis.music.util.Log
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val LOCK_SCREEN_SPACE_LEVEL = 400
internal const val LOCK_SCREEN_COLLECTION_BEHAVIOR = 337L
internal const val LOCK_SCREEN_WINDOW_LEVEL = Int.MAX_VALUE.toLong() - 2L

internal fun isMacLockScreenHost(osName: String? = System.getProperty("os.name")): Boolean =
    osName.orEmpty().lowercase().contains("mac")

/**
 * macOS Lock Screen bridge using CoreFoundation distributed notifications (`screenIsLocked` /
 * `screenIsUnlocked`), Cocoa main-queue dispatch, `NSWindow` login-visibility flags, and SkyLight
 * notification-center-at-screen-lock space assignment (level 400).
 *
 * Every public entrypoint is strictly guarded by [isMacLockScreenHost] so non-macOS builds never
 * touch Darwin frameworks.
 */
internal object MacLockScreenBridge {
    private const val TAG = "MacLockScreenBridge"
    private const val CF_STRING_ENCODING_UTF8 = 0x08000100
    private const val CF_NOTIFICATION_DELIVER_IMMEDIATELY = 4L
    private const val CF_NUMBER_SINT32_TYPE = 3L
    private const val SKY_LIGHT_PATH =
        "/System/Library/PrivateFrameworks/SkyLight.framework/Versions/A/SkyLight"

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _wakeEpoch = MutableStateFlow(0L)
    val wakeEpoch: StateFlow<Long> = _wakeEpoch.asStateFlow()

    private val started = AtomicBoolean(false)
    private val awtFirstMouseHooked = AtomicBoolean(false)
    private val wakeObserverRegistered = AtomicBoolean(false)

    @Volatile private var activeNsWindowPtr: Long = 0L
    @Volatile private var originalAcceptsFirstMouseImp: Pointer? = null
    @Volatile private var skyLightConnection: Int = 0
    @Volatile private var skyLightSpace: Int = 0
    @Volatile private var wakeObserverInstance: Pointer? = null

    private val mainTasks = ConcurrentLinkedQueue<() -> Unit>()

    private interface LibDispatch : Library {
        fun dispatch_async_f(queue: Pointer, context: Pointer?, work: DispatchWork)
    }

    private interface DispatchWork : Callback {
        fun invoke(context: Pointer?)
    }

    private interface CoreFoundationLib : Library {
        fun CFNotificationCenterGetDistributedCenter(): Pointer?
        fun CFNotificationCenterAddObserver(
            center: Pointer?,
            observer: Pointer?,
            callBack: CFNotificationCallback,
            name: Pointer?,
            obj: Pointer?,
            suspensionBehavior: Long
        )
        fun CFStringCreateWithCString(alloc: Pointer?, cStr: String, encoding: Int): Pointer?
        fun CFNumberCreate(allocator: Pointer?, theType: Long, valuePtr: Pointer): Pointer?
        fun CFArrayCreate(
            allocator: Pointer?,
            values: Pointer?,
            numValues: Long,
            callBacks: Pointer?
        ): Pointer?
        fun CFRelease(cf: Pointer?)
    }

    private interface CFNotificationCallback : Callback {
        fun invoke(
            center: Pointer?,
            observer: Pointer?,
            name: Pointer?,
            obj: Pointer?,
            userInfo: Pointer?
        )
    }

    private interface SkyLightLib : Library {
        fun SLSMainConnectionID(): Int
        fun SLSSpaceCreate(cid: Int, one: Int, zero: Int): Int
        fun SLSSpaceSetAbsoluteLevel(cid: Int, sid: Int, level: Int): Int
        fun SLSShowSpaces(cid: Int, spaceList: Pointer)
        fun SLSSpaceAddWindowsAndRemoveFromSpaces(
            cid: Int,
            sid: Int,
            windowNumbers: Pointer,
            seven: Int
        ): Int
    }

    private interface ObjCLib : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer?
        fun objc_allocateClassPair(superclass: Pointer?, name: String, extraBytes: Long): Pointer?
        fun objc_registerClassPair(cls: Pointer?)
        fun class_getInstanceMethod(cls: Pointer?, sel: Pointer?): Pointer?
        fun class_addMethod(cls: Pointer?, sel: Pointer?, imp: Callback, types: String): Byte
        fun method_getImplementation(method: Pointer?): Pointer?
        fun method_setImplementation(method: Pointer?, imp: Callback): Pointer?
    }

    private interface MsgPtr : Library {
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, arg1: Pointer?): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, arg1: Long): Pointer?
    }

    private interface MsgLong : Library {
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?): Long
    }

    private interface MsgByte : Library {
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, arg1: Pointer?): Byte
    }

    private interface MsgVoid : Library {
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?)
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, arg1: Pointer?)
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, arg1: Byte)
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, arg1: Long)
        fun objc_msgSend(
            receiver: Pointer?,
            selector: Pointer?,
            arg1: Pointer?,
            arg2: Pointer?,
            arg3: Pointer?,
            arg4: Pointer?
        )
    }

    private interface AcceptsFirstMouseImp : Callback {
        fun invoke(self: Pointer?, sel: Pointer?, event: Pointer?): Byte
    }

    private interface WakeNotificationImp : Callback {
        fun invoke(self: Pointer?, sel: Pointer?, notification: Pointer?)
    }

    private data class NativeBindings(
        val libDispatch: LibDispatch,
        val mainQueue: Pointer,
        val cf: CoreFoundationLib,
        val cfArrayCallbacks: Pointer,
        val objc: ObjCLib,
        val msgPtr: MsgPtr,
        val msgLong: MsgLong,
        val msgByte: MsgByte,
        val msgVoid: MsgVoid,
        val skyLight: SkyLightLib?
    )

    @Volatile private var cachedBindings: NativeBindings? = null

    private fun bindings(): NativeBindings? {
        if (!isMacLockScreenHost()) return null
        cachedBindings?.let { return it }
        return synchronized(this) {
            cachedBindings ?: runCatching {
                val sysLib = NativeLibrary.getInstance("System.B")
                val cfNative = NativeLibrary.getInstance("CoreFoundation")
                NativeBindings(
                    libDispatch = Native.load("System.B", LibDispatch::class.java),
                    mainQueue = sysLib.getGlobalVariableAddress("_dispatch_main_q"),
                    cf = Native.load("CoreFoundation", CoreFoundationLib::class.java),
                    cfArrayCallbacks = cfNative.getGlobalVariableAddress("kCFTypeArrayCallBacks"),
                    objc = Native.load("objc", ObjCLib::class.java),
                    msgPtr = Native.load("objc", MsgPtr::class.java),
                    msgLong = Native.load("objc", MsgLong::class.java),
                    msgByte = Native.load("objc", MsgByte::class.java),
                    msgVoid = Native.load("objc", MsgVoid::class.java),
                    skyLight = runCatching {
                        Native.load(SKY_LIGHT_PATH, SkyLightLib::class.java)
                    }.onFailure { err ->
                        Log.w(TAG, "SkyLight framework unavailable", err)
                    }.getOrNull()
                )
            }.onFailure { err ->
                Log.w(TAG, "Failed to bind macOS Lock Screen native libraries", err)
            }.getOrNull()?.also { cachedBindings = it }
        }
    }

    // Keep strong references to all JNA callbacks for the process lifetime so GC cannot collect them.
    private val drainMainQueueWork = object : DispatchWork {
        override fun invoke(context: Pointer?) {
            while (true) {
                val next = mainTasks.poll() ?: break
                runCatching(next).onFailure { err ->
                    Log.w(TAG, "Main-queue lock screen task failed", err)
                }
            }
        }
    }

    private val screenLockedCallback = object : CFNotificationCallback {
        override fun invoke(
            center: Pointer?,
            observer: Pointer?,
            name: Pointer?,
            obj: Pointer?,
            userInfo: Pointer?
        ) {
            _isLocked.value = true
        }
    }

    private val screenUnlockedCallback = object : CFNotificationCallback {
        override fun invoke(
            center: Pointer?,
            observer: Pointer?,
            name: Pointer?,
            obj: Pointer?,
            userInfo: Pointer?
        ) {
            _isLocked.value = false
        }
    }

    private val wakeImp = object : WakeNotificationImp {
        override fun invoke(self: Pointer?, sel: Pointer?, notification: Pointer?) {
            if (_isLocked.value) {
                _wakeEpoch.value = _wakeEpoch.value + 1L
            }
        }
    }

    private val acceptsFirstMouseHook = object : AcceptsFirstMouseImp {
        override fun invoke(self: Pointer?, sel: Pointer?, event: Pointer?): Byte {
            val targetPtr = activeNsWindowPtr
            val b = cachedBindings
            if (targetPtr != 0L && self != null && b != null) {
                val selWindow = b.objc.sel_registerName("window")
                val viewWindow = b.msgPtr.objc_msgSend(self, selWindow)
                if (viewWindow != null && Pointer.nativeValue(viewWindow) == targetPtr) {
                    return 1.toByte()
                }
            }
            val orig = originalAcceptsFirstMouseImp
            return if (orig != null && orig != Pointer.NULL) {
                runCatching {
                    val fn = Function.getFunction(orig)
                    (fn.invoke(java.lang.Byte.TYPE, arrayOf<Any?>(self, sel, event)) as? Byte)
                        ?: 0.toByte()
                }.getOrDefault(0.toByte())
            } else {
                0.toByte()
            }
        }
    }

    private fun runOnCocoaMainThread(task: (NativeBindings) -> Unit) {
        val b = bindings() ?: return
        mainTasks.add { task(b) }
        b.libDispatch.dispatch_async_f(b.mainQueue, null, drainMainQueueWork)
    }

    fun startObserving() {
        if (!isMacLockScreenHost()) return
        if (!started.compareAndSet(false, true)) return
        runOnCocoaMainThread { b ->
            registerDistributedLockNotifications(b)
            registerScreenWakeObserver(b)
            ensureSkyLightSpace(b)
        }
    }

    fun promoteWindowToLockScreen(windowTitle: String) {
        if (!isMacLockScreenHost()) return
        runOnCocoaMainThread { b ->
            ensureSkyLightSpace(b)
            ensureFirstMouseHook(b)
            val nsWindow = findNsWindowByTitle(b, windowTitle) ?: return@runOnCocoaMainThread
            activeNsWindowPtr = Pointer.nativeValue(nsWindow)

            val nsColorCls = b.objc.objc_getClass("NSColor")
            val clearColor = if (nsColorCls != null) {
                b.msgPtr.objc_msgSend(nsColorCls, b.objc.sel_registerName("clearColor"))
            } else null

            b.msgVoid.objc_msgSend(nsWindow, b.objc.sel_registerName("setOpaque:"), 0.toByte())
            b.msgVoid.objc_msgSend(nsWindow, b.objc.sel_registerName("setHasShadow:"), 0.toByte())
            if (clearColor != null) {
                b.msgVoid.objc_msgSend(nsWindow, b.objc.sel_registerName("setBackgroundColor:"), clearColor)
            }
            b.msgVoid.objc_msgSend(nsWindow, b.objc.sel_registerName("setMovable:"), 0.toByte())
            b.msgVoid.objc_msgSend(nsWindow, b.objc.sel_registerName("setHidesOnDeactivate:"), 0.toByte())
            b.msgVoid.objc_msgSend(nsWindow, b.objc.sel_registerName("setIgnoresMouseEvents:"), 0.toByte())

            val selCanBecomeVisible = b.objc.sel_registerName("setCanBecomeVisibleWithoutLogin:")
            val selResponds = b.objc.sel_registerName("respondsToSelector:")
            if (b.msgByte.objc_msgSend(nsWindow, selResponds, selCanBecomeVisible) != 0.toByte()) {
                b.msgVoid.objc_msgSend(nsWindow, selCanBecomeVisible, 1.toByte())
            }

            b.msgVoid.objc_msgSend(
                nsWindow,
                b.objc.sel_registerName("setCollectionBehavior:"),
                LOCK_SCREEN_COLLECTION_BEHAVIOR
            )
            b.msgVoid.objc_msgSend(
                nsWindow,
                b.objc.sel_registerName("setLevel:"),
                LOCK_SCREEN_WINDOW_LEVEL
            )
            b.msgVoid.objc_msgSend(nsWindow, b.objc.sel_registerName("invalidateShadow"))
            b.msgVoid.objc_msgSend(nsWindow, b.objc.sel_registerName("orderFrontRegardless"))

            val winNumber = b.msgLong.objc_msgSend(nsWindow, b.objc.sel_registerName("windowNumber")).toInt()
            val sid = skyLightSpace
            val cid = skyLightConnection
            val sl = b.skyLight
            if (winNumber > 0 && sid != 0 && cid != 0 && sl != null) {
                createInt32CFArray(b, winNumber)?.let { winArray ->
                    try {
                        sl.SLSSpaceAddWindowsAndRemoveFromSpaces(cid, sid, winArray, 7)
                    } finally {
                        b.cf.CFRelease(winArray)
                    }
                }
            }
        }
    }

    fun clearActiveLockScreenWindow() {
        activeNsWindowPtr = 0L
    }

    internal fun verifyNativeSymbolsLoaded(): Boolean {
        if (!isMacLockScreenHost()) return false
        val b = bindings() ?: return false
        val sl = b.skyLight ?: return false
        val nsApp = b.objc.objc_getClass("NSApplication") ?: return false
        val nsWorkspace = b.objc.objc_getClass("NSWorkspace") ?: return false
        val center = b.cf.CFNotificationCenterGetDistributedCenter() ?: return false
        return Pointer.nativeValue(nsApp) != 0L &&
            Pointer.nativeValue(nsWorkspace) != 0L &&
            Pointer.nativeValue(center) != 0L &&
            sl.SLSMainConnectionID() != 0
    }

    private fun registerDistributedLockNotifications(b: NativeBindings) {
        val center = b.cf.CFNotificationCenterGetDistributedCenter() ?: return
        addDistributedObserver(b, center, "com.apple.screenIsLocked", Pointer(1L), screenLockedCallback)
        addDistributedObserver(b, center, "com.apple.screenIsUnlocked", Pointer(2L), screenUnlockedCallback)
    }

    private fun addDistributedObserver(
        b: NativeBindings,
        center: Pointer,
        eventName: String,
        observerToken: Pointer,
        callback: CFNotificationCallback
    ) {
        val cfName = b.cf.CFStringCreateWithCString(null, eventName, CF_STRING_ENCODING_UTF8) ?: return
        try {
            b.cf.CFNotificationCenterAddObserver(
                center = center,
                observer = observerToken,
                callBack = callback,
                name = cfName,
                obj = null,
                suspensionBehavior = CF_NOTIFICATION_DELIVER_IMMEDIATELY
            )
        } finally {
            b.cf.CFRelease(cfName)
        }
    }

    private fun registerScreenWakeObserver(b: NativeBindings) {
        if (!wakeObserverRegistered.compareAndSet(false, true)) return
        val wsCls = b.objc.objc_getClass("NSWorkspace") ?: return
        val ws = b.msgPtr.objc_msgSend(wsCls, b.objc.sel_registerName("sharedWorkspace")) ?: return
        val nc = b.msgPtr.objc_msgSend(ws, b.objc.sel_registerName("notificationCenter")) ?: return

        val nsObjectCls = b.objc.objc_getClass("NSObject") ?: return
        val existingCls = b.objc.objc_getClass("AuralisLockWakeObserver")
        val selWake = b.objc.sel_registerName("handleWake:")
        val observerCls = existingCls ?: b.objc.objc_allocateClassPair(
            nsObjectCls,
            "AuralisLockWakeObserver",
            0L
        )?.also { cls ->
            b.objc.class_addMethod(cls, selWake, wakeImp, "v@:@")
            b.objc.objc_registerClassPair(cls)
        } ?: return

        val alloced = b.msgPtr.objc_msgSend(observerCls, b.objc.sel_registerName("alloc")) ?: return
        val instance = b.msgPtr.objc_msgSend(alloced, b.objc.sel_registerName("init")) ?: return
        wakeObserverInstance = instance

        val wakeName = b.cf.CFStringCreateWithCString(
            null,
            "NSWorkspaceScreensDidWakeNotification",
            CF_STRING_ENCODING_UTF8
        ) ?: return
        try {
            b.msgVoid.objc_msgSend(
                nc,
                b.objc.sel_registerName("addObserver:selector:name:object:"),
                instance,
                selWake,
                wakeName,
                null
            )
        } finally {
            b.cf.CFRelease(wakeName)
        }
    }

    private fun ensureSkyLightSpace(b: NativeBindings) {
        val sl = b.skyLight ?: return
        if (skyLightSpace != 0 && skyLightConnection != 0) return
        val cid = sl.SLSMainConnectionID()
        if (cid == 0) return
        val sid = sl.SLSSpaceCreate(cid, 1, 0)
        if (sid == 0) return
        sl.SLSSpaceSetAbsoluteLevel(cid, sid, LOCK_SCREEN_SPACE_LEVEL)
        createInt32CFArray(b, sid)?.let { spaceArray ->
            try {
                sl.SLSShowSpaces(cid, spaceArray)
            } finally {
                b.cf.CFRelease(spaceArray)
            }
        }
        skyLightConnection = cid
        skyLightSpace = sid
    }

    private fun ensureFirstMouseHook(b: NativeBindings) {
        if (!awtFirstMouseHooked.compareAndSet(false, true)) return
        val awtViewCls = b.objc.objc_getClass("AWTView") ?: return
        val sel = b.objc.sel_registerName("acceptsFirstMouse:")
        val method = b.objc.class_getInstanceMethod(awtViewCls, sel) ?: return
        val existingImp = b.objc.method_getImplementation(method)
        originalAcceptsFirstMouseImp = existingImp
        val added = b.objc.class_addMethod(awtViewCls, sel, acceptsFirstMouseHook, "B@:@")
        if (added == 0.toByte()) {
            originalAcceptsFirstMouseImp = b.objc.method_setImplementation(method, acceptsFirstMouseHook)
        }
    }

    private fun findNsWindowByTitle(b: NativeBindings, windowTitle: String): Pointer? {
        val nsAppCls = b.objc.objc_getClass("NSApplication") ?: return null
        val nsApp = b.msgPtr.objc_msgSend(nsAppCls, b.objc.sel_registerName("sharedApplication")) ?: return null
        val windows = b.msgPtr.objc_msgSend(nsApp, b.objc.sel_registerName("windows")) ?: return null
        val count = b.msgLong.objc_msgSend(windows, b.objc.sel_registerName("count"))
        val selObjectAtIndex = b.objc.sel_registerName("objectAtIndex:")
        val selTitle = b.objc.sel_registerName("title")
        val selUtf8 = b.objc.sel_registerName("UTF8String")

        for (i in 0 until count) {
            val win = b.msgPtr.objc_msgSend(windows, selObjectAtIndex, i) ?: continue
            val titlePtr = b.msgPtr.objc_msgSend(win, selTitle) ?: continue
            val utf8Ptr = b.msgPtr.objc_msgSend(titlePtr, selUtf8) ?: continue
            val title = runCatching { utf8Ptr.getString(0L, "UTF-8") }.getOrNull()
            if (title == windowTitle) {
                return win
            }
        }
        return null
    }

    private fun createInt32CFArray(b: NativeBindings, value: Int): Pointer? {
        val numMem = Memory(4L).apply { setInt(0L, value) }
        val numRef = b.cf.CFNumberCreate(null, CF_NUMBER_SINT32_TYPE, numMem) ?: return null
        return try {
            val ptrMem = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0L, numRef) }
            b.cf.CFArrayCreate(null, ptrMem, 1L, b.cfArrayCallbacks)
        } finally {
            b.cf.CFRelease(numRef)
        }
    }
}
