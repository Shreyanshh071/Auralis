package com.auralis.music.windows.media

import com.auralis.music.util.Log
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The Windows media overlay (System Media Transport Controls): the song in the volume flyout and
 * on the lock screen, and the keyboard's media keys. It talks to the Windows Runtime through JNA,
 * so it needs no native helper.
 *
 * Every Windows Runtime call runs on one background thread in the multithreaded apartment.
 * Button presses arrive on a Windows thread pool thread and are passed to [onButton].
 */
class WindowsMediaControls private constructor(
    private val smtc: Pointer,
    private val onButton: (Button) -> Unit
) {
    enum class Button { PLAY, PAUSE, STOP, NEXT, PREVIOUS }
    enum class Status(val value: Int) { CLOSED(0), CHANGING(1), STOPPED(2), PLAYING(3), PAUSED(4) }

    private var handler: ButtonHandler? = null

    private fun start() {
        call(smtc, SMTC_PUT_IS_ENABLED, 1)
        call(smtc, SMTC_PUT_IS_PLAY_ENABLED, 1)
        call(smtc, SMTC_PUT_IS_PAUSE_ENABLED, 1)
        call(smtc, SMTC_PUT_IS_NEXT_ENABLED, 1)
        call(smtc, SMTC_PUT_IS_PREVIOUS_ENABLED, 1)
        val h = ButtonHandler { button ->
            when (button) {
                0 -> onButton(Button.PLAY)
                1 -> onButton(Button.PAUSE)
                2 -> onButton(Button.STOP)
                6 -> onButton(Button.NEXT)
                7 -> onButton(Button.PREVIOUS)
            }
        }
        handler = h
        val token = LongByReference()
        val hr = call(smtc, SMTC_ADD_BUTTON_PRESSED, h.pointer, token.pointer)
        if (hr != 0) Log.w(TAG, "add_ButtonPressed failed: 0x%08x".format(hr))
    }

    fun setStatus(status: Status) = onWinRtThread { call(smtc, SMTC_PUT_PLAYBACK_STATUS, status.value) }

    fun setCanGoNext(enabled: Boolean) = onWinRtThread { call(smtc, SMTC_PUT_IS_NEXT_ENABLED, if (enabled) 1 else 0) }

    /** Title, artist and artwork (an https URL) shown in the overlay. */
    fun setMetadata(title: String, artist: String, artworkUrl: String?) = onWinRtThread {
        val updater = PointerByReference()
        if (call(smtc, SMTC_GET_DISPLAY_UPDATER, updater.pointer) != 0) return@onWinRtThread
        val du = updater.value
        try {
            call(du, DU_PUT_TYPE, MEDIA_PLAYBACK_TYPE_MUSIC)
            val props = PointerByReference()
            if (call(du, DU_GET_MUSIC_PROPERTIES, props.pointer) == 0) {
                val mp = props.value
                withHString(title) { call(mp, MUSIC_PUT_TITLE, it) }
                withHString(artist) { call(mp, MUSIC_PUT_ARTIST, it) }
                release(mp)
            }
            val thumb = artworkUrl?.let(::streamReferenceForUri)
            call(du, DU_PUT_THUMBNAIL, thumb)
            thumb?.let(::release)
            call(du, DU_UPDATE)
        } finally {
            release(du)
        }
    }

    fun clear() = onWinRtThread {
        call(smtc, SMTC_PUT_PLAYBACK_STATUS, Status.CLOSED.value)
        val updater = PointerByReference()
        if (call(smtc, SMTC_GET_DISPLAY_UPDATER, updater.pointer) == 0) {
            call(updater.value, DU_CLEAR_ALL)
            call(updater.value, DU_UPDATE)
            release(updater.value)
        }
    }

    private fun streamReferenceForUri(url: String): Pointer? {
        val uriFactory = activationFactory("Windows.Foundation.Uri", IID_URI_FACTORY) ?: return null
        val streamStatics = activationFactory("Windows.Storage.Streams.RandomAccessStreamReference", IID_STREAM_REFERENCE_STATICS)
        try {
            if (streamStatics == null) return null
            val uri = PointerByReference()
            if (withHString(url) { call(uriFactory, URI_FACTORY_CREATE_URI, it, uri.pointer) } != 0) return null
            val ref = PointerByReference()
            val hr = call(streamStatics, STREAM_REFERENCE_CREATE_FROM_URI, uri.value, ref.pointer)
            release(uri.value)
            return if (hr == 0) ref.value else null
        } finally {
            release(uriFactory)
            streamStatics?.let(::release)
        }
    }

    /**
     * A COM object implementing TypedEventHandler<SystemMediaTransportControls,
     * SystemMediaTransportControlsButtonPressedEventArgs>. The callbacks are kept in fields so the
     * garbage collector never frees code Windows still calls.
     */
    private class ButtonHandler(private val onButton: (Int) -> Unit) {
        fun interface QueryInterface : Callback { fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int }
        fun interface RefCount : Callback { fun invoke(self: Pointer): Int }
        fun interface Invoke : Callback { fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int }

        private val queryInterface = QueryInterface { self, riid, out ->
            val iid = readGuid(riid)
            if (iid == IID_IUNKNOWN || iid == IID_IAGILE_OBJECT || iid == IID_BUTTON_PRESSED_HANDLER) {
                out.setPointer(0, self)
                0
            } else {
                out.setPointer(0, null)
                E_NOINTERFACE
            }
        }
        // Lives as long as the app; Windows' reference count does not need to free it.
        private val addRef = RefCount { 2 }
        private val release = RefCount { 1 }
        private val invoke = Invoke { _, _, args ->
            if (args != null) {
                val button = Memory(4)
                if (call(args, ARGS_GET_BUTTON, button) == 0) {
                    runCatching { onButton(button.getInt(0)) }.onFailure { Log.w(TAG, "Media button failed: ${it.message}") }
                }
            }
            0
        }

        private val vtable = Memory(4L * Native.POINTER_SIZE).apply {
            setPointer(0, com.sun.jna.CallbackReference.getFunctionPointer(queryInterface))
            setPointer(1L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(addRef))
            setPointer(2L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(release))
            setPointer(3L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(invoke))
        }
        val pointer: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtable) }
    }

    companion object {
        private const val TAG = "WindowsMediaControls"
        private const val E_NOINTERFACE = 0x80004002.toInt()
        private const val MEDIA_PLAYBACK_TYPE_MUSIC = 1

        // Vtable slots: IUnknown (0-2) and IInspectable (3-5) come first.
        private const val RELEASE = 2
        private const val INTEROP_GET_FOR_WINDOW = 6
        private const val SMTC_PUT_PLAYBACK_STATUS = 7
        private const val SMTC_GET_DISPLAY_UPDATER = 8
        private const val SMTC_PUT_IS_ENABLED = 11
        private const val SMTC_PUT_IS_PLAY_ENABLED = 13
        private const val SMTC_PUT_IS_PAUSE_ENABLED = 17
        private const val SMTC_PUT_IS_PREVIOUS_ENABLED = 25
        private const val SMTC_PUT_IS_NEXT_ENABLED = 27
        private const val SMTC_ADD_BUTTON_PRESSED = 32
        private const val DU_PUT_TYPE = 7
        private const val DU_PUT_THUMBNAIL = 11
        private const val DU_GET_MUSIC_PROPERTIES = 12
        private const val DU_CLEAR_ALL = 16
        private const val DU_UPDATE = 17
        private const val MUSIC_PUT_TITLE = 7
        private const val MUSIC_PUT_ARTIST = 11
        private const val ARGS_GET_BUTTON = 6
        private const val URI_FACTORY_CREATE_URI = 6
        private const val STREAM_REFERENCE_CREATE_FROM_URI = 7

        private val IID_IUNKNOWN = UUID.fromString("00000000-0000-0000-c000-000000000046")
        private val IID_IAGILE_OBJECT = UUID.fromString("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90")
        private val IID_SMTC_INTEROP = UUID.fromString("ddb0472d-c911-4a1f-86d9-dc3d71a95f5a")
        private val IID_SMTC = UUID.fromString("99fa3ff4-1742-42a6-902e-087d41f965ec")
        private val IID_URI_FACTORY = UUID.fromString("44a9796f-723e-4fdf-a218-033e75b0c084")
        private val IID_STREAM_REFERENCE_STATICS = UUID.fromString("857309dc-3fbf-4e7d-986f-ef3b1a07a964")
        private val IID_BUTTON_PRESSED_HANDLER = parameterizedIid(
            "pinterface({9de1c534-6ae1-11e0-84e1-18a905bcc53f};" +
                "rc(Windows.Media.SystemMediaTransportControls;{99fa3ff4-1742-42a6-902e-087d41f965ec});" +
                "rc(Windows.Media.SystemMediaTransportControlsButtonPressedEventArgs;{b7f47116-a56f-4dc8-9e11-92031f4a87c2}))"
        )

        private val combase by lazy { NativeLibrary.getInstance("combase") }
        private val winRtThread = Executors.newSingleThreadExecutor { r ->
            Thread({
                // RO_INIT_MULTITHREADED
                combase.getFunction("RoInitialize").invokeInt(arrayOf(1))
                r.run()
            }, "windows-media-controls").apply { isDaemon = true }
        }

        private fun onWinRtThread(block: () -> Unit) {
            winRtThread.execute { runCatching(block).onFailure { Log.w(TAG, "Media overlay call failed: ${it.message}") } }
        }

        /** Connects to the overlay for [windowHandle] (the app's HWND); null when unavailable. */
        fun create(windowHandle: Long, onButton: (Button) -> Unit): WindowsMediaControls? {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
            return runCatching {
                winRtThread.submit<WindowsMediaControls?> {
                    val interop = activationFactory("Windows.Media.SystemMediaTransportControls", IID_SMTC_INTEROP)
                        ?: return@submit null
                    val out = PointerByReference()
                    val hr = call(interop, INTEROP_GET_FOR_WINDOW, Pointer(windowHandle), guidMemory(IID_SMTC), out.pointer)
                    release(interop)
                    if (hr != 0) {
                        Log.w(TAG, "GetForWindow failed: 0x%08x".format(hr))
                        return@submit null
                    }
                    WindowsMediaControls(out.value, onButton).also { it.start() }
                }.get()
            }.onFailure { Log.w(TAG, "Media overlay unavailable: ${it.message}") }.getOrNull()
        }

        private fun activationFactory(className: String, iid: UUID): Pointer? {
            val out = PointerByReference()
            val hr = withHString(className) {
                combase.getFunction("RoGetActivationFactory").invokeInt(arrayOf(it, guidMemory(iid), out.pointer))
            }
            if (hr != 0) {
                Log.w(TAG, "RoGetActivationFactory($className) failed: 0x%08x".format(hr))
                return null
            }
            return out.value
        }

        private fun <T> withHString(text: String, block: (Pointer?) -> T): T {
            val out = PointerByReference()
            val chars = Memory((text.length + 1) * 2L).apply { setWideString(0, text) }
            combase.getFunction("WindowsCreateString").invokeInt(arrayOf(chars, text.length, out.pointer))
            try {
                return block(out.value)
            } finally {
                combase.getFunction("WindowsDeleteString").invokeInt(arrayOf(out.value))
            }
        }

        /** Calls slot [index] of a COM object's vtable. Returns the HRESULT. */
        private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
            val fn = obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)
            return Function.getFunction(fn).invokeInt(arrayOf(obj, *args))
        }

        private fun release(obj: Pointer) {
            call(obj, RELEASE)
        }

        private fun guidMemory(uuid: UUID): Memory {
            val m = Memory(16)
            val msb = uuid.mostSignificantBits
            val lsb = uuid.leastSignificantBits
            m.setInt(0, (msb ushr 32).toInt())
            m.setShort(4, (msb ushr 16).toShort())
            m.setShort(6, msb.toShort())
            for (i in 0 until 8) m.setByte(8L + i, (lsb ushr (56 - 8 * i)).toByte())
            return m
        }

        private fun readGuid(p: Pointer): UUID {
            val d1 = p.getInt(0).toLong() and 0xffffffffL
            val d2 = p.getShort(4).toLong() and 0xffffL
            val d3 = p.getShort(6).toLong() and 0xffffL
            var lsb = 0L
            for (i in 0 until 8) lsb = (lsb shl 8) or (p.getByte(8L + i).toLong() and 0xffL)
            return UUID((d1 shl 32) or (d2 shl 16) or d3, lsb)
        }

        /** The IID Windows gives a parameterized interface (RFC 4122 v5 over its signature). */
        internal fun parameterizedIid(signature: String): UUID {
            val namespace = byteArrayOf(
                0x11, 0xf4.toByte(), 0x7a, 0xd5.toByte(), 0x7b, 0x73, 0x42, 0xc0.toByte(),
                0xab.toByte(), 0xae.toByte(), 0x87.toByte(), 0x8b.toByte(), 0x1e, 0x16, 0xad.toByte(), 0xee.toByte()
            )
            val hash = MessageDigest.getInstance("SHA-1").digest(namespace + signature.toByteArray(Charsets.UTF_8))
            hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
            hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
            var msb = 0L
            var lsb = 0L
            for (i in 0 until 8) msb = (msb shl 8) or (hash[i].toLong() and 0xffL)
            for (i in 8 until 16) lsb = (lsb shl 8) or (hash[i].toLong() and 0xffL)
            return UUID(msb, lsb)
        }
    }
}
