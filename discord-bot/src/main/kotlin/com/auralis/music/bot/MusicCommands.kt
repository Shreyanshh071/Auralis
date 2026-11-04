package com.auralis.music.bot

import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.hooks.ListenerAdapter
import java.util.concurrent.ConcurrentHashMap

/** Controlling the bot takes being in a voice channel, and in its channel once it has one. */
fun canControl(memberChannelId: Long?, botChannelId: Long?): Boolean =
    memberChannelId != null && (botChannelId == null || memberChannelId == botChannelId)

/** The bot's `au` chat commands and buttons. Anything that controls playback needs the same voice channel. */
class MusicCommands(
    private val manager: AudioPlayerManager,
    private val catalog: AuralisCatalog,
    private val scope: CoroutineScope
) : ListenerAdapter() {
    private val players = ConcurrentHashMap<Long, GuildPlayer>()

    /** Where a command came from. */
    private inner class Request(val guild: Guild, val member: Member, val channel: GuildMessageChannel) {
        fun say(text: String) = channel.sendMessage(text).setAllowedMentions(emptyList()).queue(null, ::sendFailed)
        fun show(embed: MessageEmbed) = channel.sendMessageEmbeds(embed).queue(null, ::sendFailed)
        private fun sendFailed(error: Throwable) = Log.w(TAG, "Couldn't send to ${channel.name}: ${error.message}")
    }

    /** Runs a slow command, telling the channel instead of going quiet if it breaks. */
    private fun launchFor(request: Request, block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Command failed", e)
            runCatching { request.say(SOMETHING_BROKE) }
        }
    }

    /** Says goodbye to every server with music on, so a restart isn't mistaken for a crash. */
    fun announceShutdown() = players.values.forEach { player ->
        runCatching { player.announceNow("🔄 Restarting, so the music stopped. I'll be back in a moment; use `auplay` to start again.") }
    }

    private fun playerFor(guild: Guild): GuildPlayer = players.computeIfAbsent(guild.idLong) {
        GuildPlayer(guild, manager, catalog, scope) { closed -> players.remove(guild.idLong, closed) }
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!event.isFromGuild || event.author.isBot || event.isWebhookMessage) return
        val command = parseCommand(event.message.contentRaw) ?: return
        val member = event.member ?: return
        val request = Request(event.guild, member, event.guildChannel)
        try {
            handle(command, request)
        } catch (e: InsufficientPermissionException) {
            // Can't speak in this channel, so there's nowhere to say so.
            Log.w(TAG, "Missing ${e.permission} in ${event.guildChannel.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${command.name}", e)
            runCatching { request.say(SOMETHING_BROKE) }
        }
    }

    private fun handle(command: ChatCommand, request: Request) {
        val args = command.args
        val player = players[request.guild.idLong]
        when (command.name) {
            "commands", "help" -> request.show(helpEmbed())
            "play", "playnext", "playlist" -> {
                if (args.isBlank()) return request.say(usage(command.name))
                val findPlaylist = command.name == "playlist"
                queueFromChat(request, next = command.name == "playnext") {
                    if (findPlaylist) catalog.findPlaylist(args) else catalog.select(args)
                }
            }
            "search" -> {
                if (args.isBlank()) return request.say(usage("search"))
                search(request, args)
            }
            "queue" -> if (player != null) request.show(queueEmbed(player)) else request.say(NOTHING_PLAYING)
            "np" -> {
                val item = player?.current ?: return request.say(NOTHING_PLAYING)
                request.show(nowPlayingEmbed(item, player))
            }
            "lyrics" -> {
                val item = player?.current ?: return request.say(NOTHING_PLAYING)
                request.channel.sendTyping().queue(null) {}
                launchFor(request) { request.show(lyricsEmbed(item.track)) }
            }
            else -> control(request) { active -> playbackCommand(command, request, active) }
        }
    }

    private fun playbackCommand(command: ChatCommand, request: Request, player: GuildPlayer) {
        val args = command.args
        if (command.name in NEEDS_A_SONG && player.current == null) return request.say(NOTHING_PLAYING)
        when (command.name) {
            "pause" -> {
                if (player.paused) return request.say("⏸️ Already paused.")
                player.setPaused(true)
                request.say("⏸️ Paused.")
            }
            "resume" -> {
                if (!player.paused) return request.say("▶️ Already playing.")
                player.setPaused(false)
                request.say("▶️ Resumed.")
            }
            "skip" -> {
                val title = player.current?.track?.title.orEmpty()
                player.skip()
                request.say("⏭️ Skipped **${title.escapedMarkdown()}**.")
            }
            "stop" -> { player.stop(); request.say("⏹️ Stopped and cleared the queue.") }
            "leave" -> { player.close(); request.say("👋 Left the channel.") }
            "volume" -> {
                val percent = args.toIntOrNull()?.takeIf { it in 0..150 } ?: return request.say(usage("volume"))
                player.setVolume(percent)
                request.say("🔊 Volume set to $percent%.")
            }
            "seek" -> {
                val position = parseTime(args) ?: return request.say(usage("seek"))
                request.say(if (player.seek(position)) "⏩ Jumped to ${formatTime(position)}." else "🚫 Can't seek in this song.")
            }
            "shuffle" -> {
                if (player.upcoming.size < 2) return request.say("🤏 There aren't enough songs in the queue to shuffle.")
                player.shuffle()
                request.say("🔀 Shuffled the queue.")
            }
            "remove" -> {
                val position = args.toIntOrNull() ?: return request.say(usage("remove"))
                val removed = player.remove(position) ?: return request.say("🔍 Nothing at that position.")
                request.say("🗑️ Removed **${removed.track.title.escapedMarkdown()}**.")
            }
            "move" -> {
                val positions = args.split(WHITESPACE).mapNotNull { it.toIntOrNull() }
                if (positions.size != 2) return request.say(usage("move"))
                val moved = player.move(positions[0], positions[1]) ?: return request.say("🔍 Nothing at that position.")
                request.say("↕️ Moved **${moved.track.title.escapedMarkdown()}**.")
            }
            "loop" -> {
                player.loop = when (args.lowercase()) {
                    "off" -> LoopMode.OFF
                    "song", "track" -> LoopMode.TRACK
                    "queue" -> LoopMode.QUEUE
                    else -> return request.say(usage("loop"))
                }
                request.say(when (player.loop) {
                    LoopMode.OFF -> "➡️ Looping is off."
                    LoopMode.TRACK -> "🔂 Looping this song."
                    LoopMode.QUEUE -> "🔁 Looping the queue."
                })
            }
            "auto" -> request.say(if (player.toggleAutoplay()) "📻 Autoplay on: Auralis radio keeps going when the queue ends." else "📴 Autoplay off.")
        }
    }

    private fun queueFromChat(request: Request, next: Boolean, find: suspend () -> Selection?) {
        if (joinableChannel(request.guild, request.member) == null) return request.say(NOT_IN_VOICE)
        request.channel.sendTyping().queue(null) {}
        launchFor(request) {
            val found = runCatching { find() }
            val selection = found.getOrNull()
            request.say(when {
                selection != null -> queueAndDescribe(request, selection, next)
                found.exceptionOrNull() is PlaylistUnavailableException -> "🔒 I can't open that playlist: it's private or unavailable."
                found.isFailure -> UNREACHABLE
                else -> "🔍 Nothing found for that."
            })
        }
    }

    private fun search(request: Request, query: String) {
        request.channel.sendTyping().queue(null) {}
        launchFor(request) {
            val songs = runCatching { catalog.search(query) }.getOrNull()
            if (songs.isNullOrEmpty()) return@launchFor request.say(if (songs == null) UNREACHABLE else "🔍 Nothing found for that.")
            // The results are public now, so the menu remembers who may answer it.
            val menu = StringSelectMenu.create("$SEARCH_PICK${request.member.idLong}").setPlaceholder("🎵 Pick a song")
            songs.take(SEARCH_RESULTS).forEach { menu.addOption(it.title.take(100), it.id, it.artist.take(100).ifBlank { "Unknown artist" }) }
            request.channel.sendMessage("🔎 Results for your search:").setComponents(ActionRow.of(menu.build())).queue(null) {}
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (!event.componentId.startsWith("player:")) return
        val guild = event.guild ?: return
        val player = players[guild.idLong] ?: return event.reply(NOTHING_PLAYING).setEphemeral(true).queue()
        if (event.componentId == "player:queue") return event.replyEmbeds(queueEmbed(player)).setEphemeral(true).queue()
        if (!sameChannel(guild, event.member)) return event.reply(NOT_WITH_BOT).setEphemeral(true).queue()
        val who = event.member?.effectiveName?.escapedMarkdown() ?: "Someone"
        val title = player.current?.track?.title?.escapedMarkdown()
            ?: return event.reply(NOTHING_PLAYING).setEphemeral(true).queue()
        val message = when (event.componentId) {
            "player:pause" -> {
                player.togglePause()
                if (player.paused) "⏸️ $who paused the music." else "▶️ $who resumed the music."
            }
            "player:skip" -> { player.skip(); "⏭️ $who skipped **$title**." }
            "player:stop" -> { player.stop(); "⏹️ $who stopped the music and cleared the queue." }
            else -> return
        }
        event.reply(message).setAllowedMentions(emptyList()).queue()
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        if (!event.componentId.startsWith(SEARCH_PICK)) return
        val guild = event.guild ?: return
        val member = event.member ?: return
        if (event.componentId.removePrefix(SEARCH_PICK) != member.id) {
            return event.reply("🙅 Only the person who searched can pick.").setEphemeral(true).queue()
        }
        if (joinableChannel(guild, member) == null) return event.reply(NOT_IN_VOICE).setEphemeral(true).queue()
        val id = event.values.first()
        event.deferEdit().queue()
        event.message.delete().queue(null) {}
        val request = Request(guild, member, event.guildChannel)
        launchFor(request) {
            val track = runCatching { catalog.fromLink("https://youtu.be/$id") }.getOrNull()
                ?: return@launchFor request.say(UNREACHABLE)
            request.say(queueAndDescribe(request, Selection(listOf(track)), next = false))
        }
    }

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        val player = players[event.guild.idLong] ?: return
        // Kicked or disconnected by hand: nothing left to play into.
        if (event.member == event.guild.selfMember && event.channelJoined == null) {
            player.announce("🔌 I was disconnected from the voice channel.")
            player.close()
        } else player.updateIdleTimer()
    }

    private fun queueAndDescribe(request: Request, selection: Selection, next: Boolean): String {
        val guild = request.guild
        // Checked again: a playlist can take seconds to load, and they may have left by now.
        val voice = joinableChannel(guild, request.member) ?: return NOT_IN_VOICE
        val player = playerFor(guild)
        player.announceIn(request.channel)
        try {
            if (botChannel(guild) != voice) player.connect(voice)
        } catch (e: InsufficientPermissionException) {
            return "🚪 I can't join ${voice.asMention}: I need **Connect** and **Speak** there."
        }
        val position = player.enqueue(selection.tracks.map { QueuedTrack(it, request.member.effectiveName) }, next)
        val playlist = selection.playlistTitle
        if (playlist != null) {
            val verb = if (position == 0) "▶️ Playing" else "➕ Queued"
            return "$verb **${playlist.escapedMarkdown()}**${if (selection.shuffled) " shuffled" else ""}: ${selection.tracks.size} songs."
        }
        val track = selection.tracks.first()
        val title = "**${track.title.escapedMarkdown()}** by ${track.artist.escapedMarkdown()}"
        return if (position == 0) "▶️ Playing $title." else "➕ Queued $title at position $position."
    }

    /** Runs a playback command, refusing it when nothing is playing here or the member isn't with the bot. */
    private fun control(request: Request, action: (GuildPlayer) -> Unit) {
        val player = players[request.guild.idLong] ?: return request.say(NOTHING_PLAYING)
        if (!sameChannel(request.guild, request.member)) return request.say(NOT_WITH_BOT)
        action(player)
    }

    private fun botChannel(guild: Guild): AudioChannel? =
        players[guild.idLong]?.voiceChannel ?: guild.audioManager.connectedChannel

    private fun sameChannel(guild: Guild, member: Member?): Boolean =
        canControl(member?.voiceState?.channel?.idLong, botChannel(guild)?.idLong)

    /** The member's voice channel, unless the bot is busy in a different one. */
    private fun joinableChannel(guild: Guild, member: Member): AudioChannel? {
        val channel = member.voiceState?.channel ?: return null
        return channel.takeIf { sameChannel(guild, member) }
    }

    private fun helpEmbed(): MessageEmbed = EmbedBuilder().setColor(AURALIS_COLOR).setTitle("📖 Auralis commands").apply {
        commandSections().forEach { (heading, commands) -> addField(heading, commands, false) }
    }.build()

    private fun queueEmbed(player: GuildPlayer): MessageEmbed {
        val current = player.current
        val upcoming = player.upcoming
        val lines = upcoming.take(QUEUE_PAGE).mapIndexed { index, item ->
            "`${index + 1}.` ${item.track.title.escapedMarkdown().take(70)} - ${item.track.artist.escapedMarkdown().take(40)}"
        }
        return EmbedBuilder().setColor(AURALIS_COLOR).setTitle("📜 Queue")
            .setDescription(buildString {
                appendLine(current?.let { "🎶 **Now:** ${it.track.title.escapedMarkdown()}" } ?: "🔇 Nothing playing.")
                if (lines.isNotEmpty()) appendLine().append(lines.joinToString("\n"))
                if (upcoming.size > QUEUE_PAGE) append("\n➕ ...and ${upcoming.size - QUEUE_PAGE} more")
            })
            .setFooter("🔁 Loop: ${player.loop.name.lowercase()} | 📻 Autoplay: ${if (player.autoplay) "on" else "off"}")
            .build()
    }

    private suspend fun lyricsEmbed(track: Track): MessageEmbed {
        val lyrics = runCatching { catalog.lyrics(track) }
            .onFailure { Log.w("MusicCommands", "Lyrics failed: ${it.message}") }.getOrNull()
        val text = lyrics?.plainLyrics?.takeIf { it.isNotBlank() }
            ?: lyrics?.lines?.filterNot { it.isInstrumental }?.joinToString("\n") { it.text }
        val builder = EmbedBuilder().setColor(AURALIS_COLOR).setAuthor("🎤 Lyrics").setTitle(track.title.take(MessageEmbed.TITLE_MAX_LENGTH))
        return builder.setDescription(
            if (text.isNullOrBlank()) "😶 No lyrics found." else text.take(MessageEmbed.DESCRIPTION_MAX_LENGTH - 2).let { if (it.length < text.length) "$it..." else it }
        ).build()
    }

    private companion object {
        const val TAG = "MusicCommands"
        const val SOMETHING_BROKE = "💥 Something went wrong with that, please try again."
        val NEEDS_A_SONG = setOf("pause", "resume", "skip", "seek")
        const val SEARCH_PICK = "search:pick:"
        const val SEARCH_RESULTS = 10
        const val QUEUE_PAGE = 15
        const val NOT_IN_VOICE = "🎧 Join a voice channel first (the one I'm in, if I'm already playing)."
        const val NOT_WITH_BOT = "🎧 You need to be in my voice channel for that."
        const val NOTHING_PLAYING = "🔇 Nothing's playing."
        const val UNREACHABLE = "📡 Couldn't reach YouTube Music, try again in a moment."
        val WHITESPACE = Regex("\\s+")
    }
}
