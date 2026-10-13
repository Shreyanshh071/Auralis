package com.auralis.music.windows.together

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track

/** Room protocol boundary: VLC and an isolated in-memory player obey the same contract. */
internal interface RoomPlayback {
    val queue: DesktopPlaybackController.Queue
    val position: Long
    val length: Long
    val rate: Float
    val playing: Boolean
    val ready: Boolean
    var controls: ((String, Track?, Long) -> Boolean)?
    var queueEdit: ((Int, Int?) -> Boolean)?
    var willLoad: ((Track) -> Unit)?
    fun local(block: () -> Unit)
    fun play(track: Track, queue: List<Track>)
    fun sync(queue: List<Track>, index: Int)
    fun pause(paused: Boolean)
    fun seek(position: Long)
    fun speed(rate: Float)
    fun next()
    fun previous()
    fun add(track: Track)
    fun remove(index: Int)
    fun move(from: Int, to: Int)
    fun hold()
    fun releaseHold()
}

internal class VlcRoomPlayback(private val player: DesktopPlaybackController) : RoomPlayback {
    override val queue get() = player.queue.value
    override val position get() = player.clock.rawPositionMs()
    override val length get() = player.lengthMs.value
    override val rate get() = player.clock.speed()
    override val playing get() = player.clock.isPlaying()
    override val ready get() = !player.resolving.value && player.lengthMs.value > 0 &&
        player.state.value in setOf(VlcAudioPlayer.State.PLAYING, VlcAudioPlayer.State.PAUSED)
    override var controls: ((String, Track?, Long) -> Boolean)?
        get() = player.roomControl
        set(value) { player.roomControl = value }
    override var queueEdit: ((Int, Int?) -> Boolean)?
        get() = player.roomQueueEdit
        set(value) { player.roomQueueEdit = value }
    override var willLoad: ((Track) -> Unit)?
        get() = player.roomTrackWillLoad
        set(value) { player.roomTrackWillLoad = value }
    override fun local(block: () -> Unit) { player.fromRoom(block) }
    override fun play(track: Track, queue: List<Track>) = player.playFrom(track, queue, automatic = false)
    override fun sync(queue: List<Track>, index: Int) = player.syncRoomQueue(queue, index)
    override fun pause(paused: Boolean) = player.setPaused(paused)
    override fun seek(position: Long) = player.seekTo(position)
    override fun speed(rate: Float) = player.setRate(rate)
    override fun next() = player.next()
    override fun previous() = player.previous()
    override fun add(track: Track) = player.addToQueue(track)
    override fun remove(index: Int) = player.removeAt(index)
    override fun move(from: Int, to: Int) = player.moveAt(from, to)
    override fun hold() = player.holdStartForRoom()
    override fun releaseHold() = player.releaseRoomStart()
}
