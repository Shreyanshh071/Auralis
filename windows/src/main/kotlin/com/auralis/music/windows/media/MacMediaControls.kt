package com.auralis.music.windows.media

import com.auralis.music.util.Log
import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * macOS implementation of [DesktopMediaControls] using Apple's MediaPlayer framework:
 * - [MPNowPlayingInfoCenter] sets system "Now Playing" metadata (title, artist, duration, artwork, playback rate).
 * - [MPRemoteCommandCenter] intercepts media key events, macOS Control Center actions, Lock Screen controls,
 *   and third-party status bar integrations (such as Boring Notch).
 */
class MacMediaControls private constructor(
    private val onButton: (DesktopMediaControls.Button) -> Unit,
    private val onSeek: ((Long) -> Unit)?
) : DesktopMediaControls {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var artworkJob: Job? = null

    @Volatile private var currentTitle: String = ""
    @Volatile private var currentArtist: String = ""
    @Volatile private var currentArtworkUrl: String? = null
    @Volatile private var currentArtwork: Pointer? = null
    @Volatile private var currentDurationSec: Double = 0.0
    @Volatile private var currentElapsedSec: Double = 0.0
    @Volatile private var currentStatus: DesktopMediaControls.Status = DesktopMediaControls.Status.CLOSED

    private var targetInstance: Pointer? = null
    private var commandCenter: Pointer? = null
    private var nextTrackCommand: Pointer? = null
    private var previousTrackCommand: Pointer? = null

    // Callbacks must be held in fields to prevent GC while registered in native runtime
    private val callbacks = mutableListOf<Callback>()

    private fun init() {
        val objc = NativeLibrary.getInstance("objc")
        val cf = NativeLibrary.getInstance("CoreFoundation")
        val mp = NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
        val appkit = NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit")

        val getClass = objc.getFunction("objc_getClass")
        val selRegister = objc.getFunction("sel_registerName")
        val msgSend = objc.getFunction("objc_msgSend")
        val allocateClass = objc.getFunction("objc_allocateClassPair")
        val registerClass = objc.getFunction("objc_registerClassPair")
        val addMethod = objc.getFunction("class_addMethod")

        // 1. Create or reuse Objective-C target class
        val className = "AuralisMacMediaHandler_" + classIdCounter.incrementAndGet()
        val nsObjectClass = getClass.invokePointer(arrayOf("NSObject")) ?: return
        val handlerClass = allocateClass.invokePointer(arrayOf(nsObjectClass, className, 0L)) ?: return

        fun addAction(selName: String, action: (Pointer?) -> Int): Pointer {
            val cb = object : CommandCallback {
                override fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Int = action(event)
            }
            callbacks.add(cb)
            val sel = selRegister.invokePointer(arrayOf(selName))
            addMethod.invoke(java.lang.Boolean::class.java, arrayOf(handlerClass, sel, cb, "i@:@"))
            return sel
        }

        val playSel = addAction("handlePlay:") {
            onButton(DesktopMediaControls.Button.PLAY)
            0 // MPRemoteCommandHandlerStatusSuccess
        }
        val pauseSel = addAction("handlePause:") {
            onButton(DesktopMediaControls.Button.PAUSE)
            0
        }
        val toggleSel = addAction("handleTogglePlayPause:") {
            if (currentStatus == DesktopMediaControls.Status.PLAYING) {
                onButton(DesktopMediaControls.Button.PAUSE)
            } else {
                onButton(DesktopMediaControls.Button.PLAY)
            }
            0
        }
        val nextSel = addAction("handleNext:") {
            onButton(DesktopMediaControls.Button.NEXT)
            0
        }
        val prevSel = addAction("handlePrev:") {
            onButton(DesktopMediaControls.Button.PREVIOUS)
            0
        }
        val seekSel = addAction("handleSeek:") { event ->
            val seek = onSeek
            if (event != null && seek != null) {
                runCatching {
                    val positionTimeSel = selRegister.invokePointer(arrayOf("positionTime"))
                    val seconds = msgSend.invoke(java.lang.Double::class.java, arrayOf(event, positionTimeSel)) as Double
                    seek((seconds * 1000.0).toLong())
                }
            }
            0
        }

        registerClass.invoke(arrayOf(handlerClass))
        val newSel = selRegister.invokePointer(arrayOf("new"))
        val instance = msgSend.invokePointer(arrayOf(handlerClass, newSel)) ?: return
        targetInstance = instance

        // 2. Setup MPRemoteCommandCenter
        val remoteCenterClass = getClass.invokePointer(arrayOf("MPRemoteCommandCenter")) ?: return
        val sharedCenterSel = selRegister.invokePointer(arrayOf("sharedCommandCenter"))
        val center = msgSend.invokePointer(arrayOf(remoteCenterClass, sharedCenterSel)) ?: return
        commandCenter = center

        val addTargetSel = selRegister.invokePointer(arrayOf("addTarget:action:"))
        val setEnabledSel = selRegister.invokePointer(arrayOf("setEnabled:"))

        fun bindCommand(commandName: String, actionSel: Pointer): Pointer? {
            val cmdSel = selRegister.invokePointer(arrayOf(commandName))
            val cmd = msgSend.invokePointer(arrayOf(center, cmdSel)) ?: return null
            msgSend.invoke(arrayOf(cmd, setEnabledSel, true))
            msgSend.invoke(arrayOf(cmd, addTargetSel, instance, actionSel))
            return cmd
        }

        bindCommand("playCommand", playSel)
        bindCommand("pauseCommand", pauseSel)
        bindCommand("togglePlayPauseCommand", toggleSel)
        nextTrackCommand = bindCommand("nextTrackCommand", nextSel)
        previousTrackCommand = bindCommand("previousTrackCommand", prevSel)
        bindCommand("changePlaybackPositionCommand", seekSel)
    }

    override fun setStatus(status: DesktopMediaControls.Status) {
        currentStatus = status
        updateNowPlayingInfo()
    }

    override fun setCanGoNext(enabled: Boolean) {
        val cmd = nextTrackCommand ?: return
        runCatching {
            val objc = NativeLibrary.getInstance("objc")
            val selRegister = objc.getFunction("sel_registerName")
            val msgSend = objc.getFunction("objc_msgSend")
            msgSend.invoke(arrayOf(cmd, selRegister.invokePointer(arrayOf("setEnabled:")), enabled))
        }
    }

    override fun setCanGoPrevious(enabled: Boolean) {
        val cmd = previousTrackCommand ?: return
        runCatching {
            val objc = NativeLibrary.getInstance("objc")
            val selRegister = objc.getFunction("sel_registerName")
            val msgSend = objc.getFunction("objc_msgSend")
            msgSend.invoke(arrayOf(cmd, selRegister.invokePointer(arrayOf("setEnabled:")), enabled))
        }
    }

    override fun setPosition(elapsedSeconds: Double, durationSeconds: Double) {
        currentElapsedSec = elapsedSeconds.coerceAtLeast(0.0)
        if (durationSeconds > 0.0) currentDurationSec = durationSeconds
        updateNowPlayingInfo()
    }

    override fun setMetadata(
        title: String,
        artist: String,
        artworkUrl: String?,
        durationSeconds: Double,
        elapsedSeconds: Double
    ) {
        val changedTrack = title != currentTitle || artist != currentArtist
        currentTitle = title
        currentArtist = artist
        if (durationSeconds > 0.0) currentDurationSec = durationSeconds
        if (elapsedSeconds >= 0.0) currentElapsedSec = elapsedSeconds

        if (changedTrack) {
            currentArtwork = null
            currentArtworkUrl = null
            artworkJob?.cancel()
        }

        updateNowPlayingInfo()

        if (artworkUrl != null && artworkUrl != currentArtworkUrl) {
            currentArtworkUrl = artworkUrl
            loadArtworkAsync(artworkUrl)
        }
    }

    override fun clear() {
        artworkJob?.cancel()
        currentArtwork = null
        currentArtworkUrl = null
        currentTitle = ""
        currentArtist = ""
        currentDurationSec = 0.0
        currentElapsedSec = 0.0
        currentStatus = DesktopMediaControls.Status.CLOSED

        runCatching {
            val objc = NativeLibrary.getInstance("objc")
            val mp = NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
            val getClass = objc.getFunction("objc_getClass")
            val selRegister = objc.getFunction("sel_registerName")
            val msgSend = objc.getFunction("objc_msgSend")

            val infoCenterClass = getClass.invokePointer(arrayOf("MPNowPlayingInfoCenter")) ?: return@runCatching
            val infoCenter = msgSend.invokePointer(arrayOf(infoCenterClass, selRegister.invokePointer(arrayOf("defaultCenter")))) ?: return@runCatching

            msgSend.invoke(arrayOf(infoCenter, selRegister.invokePointer(arrayOf("setNowPlayingInfo:")), null))
            msgSend.invoke(arrayOf(infoCenter, selRegister.invokePointer(arrayOf("setPlaybackState:")), 0L))
        }
    }

    override fun close() {
        clear()
        scope.cancel()
    }

    private fun loadArtworkAsync(url: String) {
        artworkJob?.cancel()
        artworkJob = scope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                val conn = URI(url).toURL().openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.inputStream.use { it.readBytes() }
            }.getOrNull() ?: return@launch

            if (!isActive || currentArtworkUrl != url) return@launch

            val artworkPtr = createMediaItemArtwork(bytes) ?: return@launch
            currentArtwork = artworkPtr
            updateNowPlayingInfo()
        }
    }

    private fun createMediaItemArtwork(bytes: ByteArray): Pointer? = runCatching {
        val objc = NativeLibrary.getInstance("objc")
        val cf = NativeLibrary.getInstance("CoreFoundation")
        val mp = NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
        val appkit = NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit")

        val getClass = objc.getFunction("objc_getClass")
        val selRegister = objc.getFunction("sel_registerName")
        val msgSend = objc.getFunction("objc_msgSend")
        val cfData = cf.getFunction("CFDataCreate")
        val cfRelease = cf.getFunction("CFRelease")

        val dataMem = Memory(bytes.size.toLong())
        dataMem.write(0, bytes, 0, bytes.size)
        val cfDataRef = cfData.invokePointer(arrayOf(null, dataMem, bytes.size.toLong())) ?: return null

        try {
            val nsImageClass = getClass.invokePointer(arrayOf("NSImage")) ?: return null
            val allocSel = selRegister.invokePointer(arrayOf("alloc"))
            val initWithDataSel = selRegister.invokePointer(arrayOf("initWithData:"))
            val nsImageAlloc = msgSend.invokePointer(arrayOf(nsImageClass, allocSel)) ?: return null
            val nsImage = msgSend.invokePointer(arrayOf(nsImageAlloc, initWithDataSel, cfDataRef)) ?: return null

            val artworkClass = getClass.invokePointer(arrayOf("MPMediaItemArtwork")) ?: return null
            val artworkAlloc = msgSend.invokePointer(arrayOf(artworkClass, allocSel)) ?: return null
            val initWithImageSel = selRegister.invokePointer(arrayOf("initWithImage:"))
            msgSend.invokePointer(arrayOf(artworkAlloc, initWithImageSel, nsImage))
        } finally {
            cfRelease.invoke(arrayOf(cfDataRef))
        }
    }.onFailure { Log.w(TAG, "Failed to create artwork: ${it.message}") }.getOrNull()

    @Synchronized
    private fun updateNowPlayingInfo() {
        if (currentTitle.isBlank() && currentArtist.isBlank() && currentStatus == DesktopMediaControls.Status.CLOSED) {
            return
        }

        runCatching {
            val objc = NativeLibrary.getInstance("objc")
            val cf = NativeLibrary.getInstance("CoreFoundation")
            val mp = NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")

            val getClass = objc.getFunction("objc_getClass")
            val selRegister = objc.getFunction("sel_registerName")
            val msgSend = objc.getFunction("objc_msgSend")
            val cfString = cf.getFunction("CFStringCreateWithCString")
            val cfNumber = cf.getFunction("CFNumberCreate")
            val cfDict = cf.getFunction("CFDictionaryCreate")
            val cfRelease = cf.getFunction("CFRelease")

            val infoCenterClass = getClass.invokePointer(arrayOf("MPNowPlayingInfoCenter")) ?: return
            val defaultCenterSel = selRegister.invokePointer(arrayOf("defaultCenter"))
            val infoCenter = msgSend.invokePointer(arrayOf(infoCenterClass, defaultCenterSel)) ?: return

            // 1. Playback state (1 = Playing, 2 = Paused, 3 = Stopped)
            val stateVal = when (currentStatus) {
                DesktopMediaControls.Status.PLAYING -> 1L
                DesktopMediaControls.Status.PAUSED -> 2L
                DesktopMediaControls.Status.CHANGING -> 1L
                DesktopMediaControls.Status.STOPPED, DesktopMediaControls.Status.CLOSED -> 3L
            }
            val setPlaybackStateSel = selRegister.invokePointer(arrayOf("setPlaybackState:"))
            msgSend.invoke(arrayOf(infoCenter, setPlaybackStateSel, stateVal))

            // 2. Build metadata dictionary
            val kTitle = mp.getGlobalVariableAddress("MPMediaItemPropertyTitle").getPointer(0)
            val kArtist = mp.getGlobalVariableAddress("MPMediaItemPropertyArtist").getPointer(0)
            val kRate = mp.getGlobalVariableAddress("MPNowPlayingInfoPropertyPlaybackRate").getPointer(0)
            val kDuration = mp.getGlobalVariableAddress("MPMediaItemPropertyPlaybackDuration").getPointer(0)
            val kElapsed = mp.getGlobalVariableAddress("MPNowPlayingInfoPropertyElapsedPlaybackTime").getPointer(0)
            val kArtwork = mp.getGlobalVariableAddress("MPMediaItemPropertyArtwork").getPointer(0)

            val utf8 = 0x08000100 // kCFStringEncodingUTF8
            val vTitle = cfString.invokePointer(arrayOf(null, currentTitle, utf8))
            val vArtist = cfString.invokePointer(arrayOf(null, currentArtist, utf8))

            val kCFNumberDoubleType = 13L
            val rateVal = if (currentStatus == DesktopMediaControls.Status.PLAYING) 1.0 else 0.0
            val rateMem = Memory(8).apply { setDouble(0, rateVal) }
            val vRate = cfNumber.invokePointer(arrayOf(null, kCFNumberDoubleType, rateMem))

            val durMem = Memory(8).apply { setDouble(0, currentDurationSec) }
            val vDuration = cfNumber.invokePointer(arrayOf(null, kCFNumberDoubleType, durMem))

            val elapMem = Memory(8).apply { setDouble(0, currentElapsedSec) }
            val vElapsed = cfNumber.invokePointer(arrayOf(null, kCFNumberDoubleType, elapMem))

            val artworkPtr = currentArtwork
            val count = if (artworkPtr != null) 6 else 5

            val keys = Memory(count * 8L)
            keys.setPointer(0 * 8L, kTitle)
            keys.setPointer(1 * 8L, kArtist)
            keys.setPointer(2 * 8L, kRate)
            keys.setPointer(3 * 8L, kDuration)
            keys.setPointer(4 * 8L, kElapsed)
            if (artworkPtr != null) keys.setPointer(5 * 8L, kArtwork)

            val values = Memory(count * 8L)
            values.setPointer(0 * 8L, vTitle)
            values.setPointer(1 * 8L, vArtist)
            values.setPointer(2 * 8L, vRate)
            values.setPointer(3 * 8L, vDuration)
            values.setPointer(4 * 8L, vElapsed)
            if (artworkPtr != null) values.setPointer(5 * 8L, artworkPtr)

            val kCFTypeDictKeyCallBacks = cf.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")
            val kCFTypeDictValCallBacks = cf.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")

            val dict = cfDict.invokePointer(
                arrayOf(null, keys, values, count.toLong(), kCFTypeDictKeyCallBacks, kCFTypeDictValCallBacks)
            )

            if (dict != null) {
                try {
                    val setNowPlayingInfoSel = selRegister.invokePointer(arrayOf("setNowPlayingInfo:"))
                    msgSend.invoke(arrayOf(infoCenter, setNowPlayingInfoSel, dict))
                } finally {
                    cfRelease.invoke(arrayOf(dict))
                }
            }

            if (vTitle != null) cfRelease.invoke(arrayOf(vTitle))
            if (vArtist != null) cfRelease.invoke(arrayOf(vArtist))
            if (vRate != null) cfRelease.invoke(arrayOf(vRate))
            if (vDuration != null) cfRelease.invoke(arrayOf(vDuration))
            if (vElapsed != null) cfRelease.invoke(arrayOf(vElapsed))
        }.onFailure { Log.w(TAG, "Failed to update now playing info: ${it.message}") }
    }

    interface CommandCallback : Callback {
        fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Int
    }

    companion object {
        private const val TAG = "MacMediaControls"
        private val classIdCounter = AtomicInteger(0)

        fun create(
            onButton: (DesktopMediaControls.Button) -> Unit,
            onSeek: ((Long) -> Unit)? = null
        ): MacMediaControls? {
            val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
            if (!isMac) return null

            return runCatching {
                MacMediaControls(onButton, onSeek).also { it.init() }
            }.onFailure {
                Log.w(TAG, "macOS media controls unavailable: ${it.message}")
            }.getOrNull()
        }
    }
}
