package com.auralis.music.bot

import com.auralis.music.util.Log
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.utils.MarkdownSanitizer

const val AURALIS_COLOR = 0x8A2BE2

fun String.escapedMarkdown(): String = MarkdownSanitizer.escape(this)

/** 1:05 or 1:02:05. */
fun formatTime(ms: Long): String {
    val total = ms / 1000
    val (h, m, s) = Triple(total / 3600, total / 60 % 60, total % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Reads 90, 1:30 or 1:02:05 as milliseconds; null for anything else. */
fun parseTime(text: String): Long? {
    val parts = text.trim().split(':').map { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    if (parts.size > 3) return null
    return parts.fold(0L) { total, part -> total * 60 + part } * 1000
}

fun nowPlayingEmbed(item: QueuedTrack, player: GuildPlayer): MessageEmbed {
    val track = item.track
    val duration = player.durationMs.takeIf { it > 0 } ?: (track.duration * 1000)
    return EmbedBuilder().setColor(AURALIS_COLOR)
        .setAuthor(if (player.paused) "⏸️ Paused" else "🎶 Now playing")
        .setTitle(track.title.take(MessageEmbed.TITLE_MAX_LENGTH), "https://music.youtube.com/watch?v=${track.id}")
        .setDescription(track.artist.escapedMarkdown())
        .setThumbnail(track.thumbnail.takeIf { it.startsWith("http") })
        .addField("⏱️ Length", formatTime(duration), true)
        .addField("🙋 Requested by", item.requestedBy?.escapedMarkdown() ?: "📻 Autoplay", true)
        .addField("⏭️ Up next", player.upcoming.firstOrNull()?.track?.title?.escapedMarkdown()?.take(100) ?: "Nothing queued", true)
        .build()
}

fun playerButtons(paused: Boolean): ActionRow = ActionRow.of(
    Button.secondary("player:pause", if (paused) "▶️ Resume" else "⏸️ Pause"),
    Button.secondary("player:skip", "⏭️ Skip"),
    Button.danger("player:stop", "⏹️ Stop"),
    Button.secondary("player:queue", "📜 Queue")
)

/** Keeps one live now-playing message per server, retiring the previous one's buttons. */
class NowPlayingMessages {
    @Volatile var channel: GuildMessageChannel? = null
    @Volatile private var messageId: Long? = null

    fun nowPlaying(item: QueuedTrack, player: GuildPlayer) {
        finish()
        channel?.sendMessageEmbeds(nowPlayingEmbed(item, player))?.setComponents(playerButtons(player.paused))
            ?.queue({ messageId = it.idLong }, {})
    }

    fun refresh(item: QueuedTrack?, player: GuildPlayer) {
        val id = messageId ?: return
        if (item == null) return
        channel?.editMessageEmbedsById(id, nowPlayingEmbed(item, player))?.setComponents(playerButtons(player.paused))?.queue(null) {}
    }

    /** Takes the buttons off the last message, whose song is over. */
    fun finish() {
        val id = messageId ?: return
        messageId = null
        channel?.editMessageComponentsById(id)?.queue(null) {}
    }

    fun say(text: String) {
        channel?.sendMessage(text)?.setAllowedMentions(emptyList())?.queue(null) {
            Log.w("NowPlayingMessages", "Couldn't send a message: ${it.message}")
        }
    }

    fun sayNow(text: String) {
        channel?.sendMessage(text)?.setAllowedMentions(emptyList())?.complete()
    }
}
