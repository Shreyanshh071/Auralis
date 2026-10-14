package com.auralis.music.windows.media

import com.auralis.music.windows.NativeWindowFrame
import com.auralis.music.util.Log
import com.sun.jna.*
import com.sun.jna.Function
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Explorer thumbnail toolbar, independent of the lock-screen/media-key SMTC controls. */
internal class TaskbarPlaybackControls(
    private val frame: NativeWindowFrame, handle: Long,
    private val previous: () -> Unit, private val toggle: () -> Unit, private val next: () -> Unit
) : AutoCloseable {
    private val user = NativeLibrary.getInstance("user32")
    private val hwnd = Pointer(handle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    // ITaskbarList requires STA (COINIT_APARTMENTTHREADED = 2)
    private val initialized = Ole32.INSTANCE.CoInitializeEx(null, 2).toInt() >= 0
    private val taskbar = PointerByReference().also {
        val result = Ole32.INSTANCE.CoCreateInstance(Guid.CLSID("{56FDF344-FD6D-11D0-958A-006097C9A090}"), null, 1,
            Guid.IID("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}"), it)
        check(result.toInt() >= 0) { "ITaskbarList3: ${result.toInt().toUInt().toString(16)}" }
    }.value
    private val createdMessage = user.getFunction("RegisterWindowMessageW").invokeInt(arrayOf(WString("TaskbarButtonCreated")))
    private val icons = listOf(icon(0), icon(1), icon(2), icon(3)) // previous, play, pause, next
    private var added = false
    private var closed = false
    private var playing = false
    private var available = false
    private var hasNext = false

    init {
        val hr = call(3)
        // HrInit returns S_OK (0) on Win10 or E_NOTIMPL (0x80004001) on Win11 XAML taskbar
        check(hr >= 0 || hr == 0x80004001.toInt()) { "Taskbar HrInit failed: 0x${hr.toUInt().toString(16)}" }
        frame.onTaskbarMessage = { message, w ->
            when {
                message == createdMessage -> { scope.launch { added = false; refresh() }; false }
                message == 0x111 && ((w ushr 16) and 0xffff) == 0x1800L && (w and 0xffff).toInt() in 4101..4103 -> {
                    val command = (w and 0xffff).toInt()
                    scope.launch {
                        if (available) when (command) {
                            4101 -> previous()
                            4102 -> toggle()
                            4103 -> if (hasNext) next()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        refresh()
    }

    internal val isRegistered get() = added

    fun update(hasTrack: Boolean, isPlaying: Boolean, canGoNext: Boolean) {
        available = hasTrack; playing = isPlaying; hasNext = canGoNext; refresh()
    }

    private fun call(slot: Int, vararg args: Any?): Int = Function.getFunction(
        taskbar.getPointer(0).getPointer(slot * Native.POINTER_SIZE.toLong()), Function.ALT_CONVENTION
    ).invokeInt(arrayOf(taskbar, *args))

    private fun refresh() {
        if (closed) return
        val buttons = buttons(playing, available, hasNext, icons)
        var result = call(if (added) 16 else 15, hwnd, 3, buttons)
        // An initial TaskbarButtonCreated message can arrive after our first successful add.
        // Updating the existing toolbar also handles that ordering without losing state.
        if (!added && result < 0) result = call(16, hwnd, 3, buttons)
        if (result >= 0) {
            if (!added) Log.i("AuralisTaskbar", "Previous, Play/Pause and Next toolbar installed")
            added = true
        }
        // Explorer can create the taskbar button after the window's Compose content starts.
        // Its TaskbarButtonCreated message retries registration, including after Explorer restarts.
    }

    override fun close() {
        if (closed) return
        if (added) call(16, hwnd, 3, buttons(playing, false, false, icons, hidden = true))
        closed = true
        frame.onTaskbarMessage = null
        scope.cancel()
        call(2) // IUnknown::Release
        icons.forEach { user.getFunction("DestroyIcon").invokeInt(arrayOf(it)) }
        if (initialized) Ole32.INSTANCE.CoUninitialize()
    }

    companion object {
        // THUMBBUTTON uses 8-byte alignment on Win64: mask,id,bitmap,padding,HICON,WCHAR[260],flags,padding.
        internal fun buttons(playing: Boolean, available: Boolean, next: Boolean, icons: List<Pointer>, hidden: Boolean = false): Memory {
            val stride = if (Native.POINTER_SIZE == 8) 552L else 540L
            val iconOffset = if (Native.POINTER_SIZE == 8) 16L else 12L
            return Memory(stride * 3).apply {
                clear()
                for (i in 0..2) {
                    val base = stride * i
                    setInt(base, 0xE) // THB_ICON | THB_TOOLTIP | THB_FLAGS
                    setInt(base + 4, 4101 + i)
                    setPointer(base + iconOffset, icons[when (i) { 0 -> 0; 1 -> if (playing) 2 else 1; else -> 3 }])
                    val tip = when (i) { 0 -> "Previous"; 1 -> if (playing) "Pause" else "Play"; else -> "Next" }
                    tip.forEachIndexed { j, c -> setShort(base + iconOffset + Native.POINTER_SIZE + j * 2L, c.code.toShort()) }
                    val enabled = available && (i != 2 || next)
                    setInt(base + iconOffset + Native.POINTER_SIZE + 520, if (hidden) 8 else if (enabled) 0 else 1)
                }
            }
        }

        private fun icon(kind: Int): Pointer {
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                color = Color.WHITE; stroke = BasicStroke(2f)
                when (kind) {
                    0 -> { fillRect(6, 7, 3, 18); fillPolygon(Polygon(intArrayOf(24, 11, 24), intArrayOf(6,16,26), 3)) }
                    1 -> fillPolygon(Polygon(intArrayOf(9, 25, 9), intArrayOf(5,16,27), 3))
                    2 -> { fillRect(8, 6, 6, 20); fillRect(18, 6, 6, 20) }
                    3 -> { fillPolygon(Polygon(intArrayOf(8, 21, 8), intArrayOf(6,16,26), 3)); fillRect(23,7,3,18) }
                }
                dispose()
            }
            val pixels = Memory(32L * 32 * 4)
            val mask = Memory(128).apply { clear() }
            for (y in 0..31) for (x in 0..31) {
                val pixel = image.getRGB(x,y)
                pixels.setInt((y * 32L + x) * 4, pixel)
                if ((pixel ushr 24) == 0) {
                    val offset = y * 4L + x / 8
                    mask.setByte(offset, (mask.getByte(offset).toInt() or (0x80 ushr (x % 8))).toByte())
                }
            }
            return checkNotNull(NativeLibrary.getInstance("user32").getFunction("CreateIcon")
                .invokePointer(arrayOf(null, 32, 32, 1.toByte(), 32.toByte(), mask, pixels))) { "Taskbar icon creation failed" }
        }
    }
}
