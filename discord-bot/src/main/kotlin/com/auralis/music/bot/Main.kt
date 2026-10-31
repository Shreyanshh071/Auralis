package com.auralis.music.bot

import club.minnced.discord.jdave.interop.JDaveSessionFactory
import com.auralis.music.data.network.provider.AmllLyricsSource
import com.auralis.music.desktop.playback.DesktopStreamResolver
import com.auralis.music.desktop.playback.LocalStreamRelay
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Streams per server playing at once; the relay's default of four suits a single player. */
private const val RELAY_STREAMS = 256

/** usage: auralis-discord-bot.jar, or --check "song name or link" to test playback without Discord. */
fun main(args: Array<String>) {
    LocalStreamRelay.maxStreams = RELAY_STREAMS
    AmllLyricsSource.indexCacheDir = File(System.getProperty("user.home"), ".auralis-discord-bot/cache").apply { mkdirs() }
    DesktopStreamResolver.warmUp()

    val manager = DefaultAudioPlayerManager().apply {
        registerSourceManager(HttpAudioSourceManager())
        configuration.resamplingQuality = AudioConfiguration.ResamplingQuality.HIGH
    }
    val catalog = AuralisCatalog()

    if (args.firstOrNull() == "--check") exitProcess(if (check(args.drop(1).joinToString(" "), catalog, manager)) 0 else 1)

    val token = System.getenv("DISCORD_BOT_TOKEN")?.takeIf { it.isNotBlank() } ?: dotEnv("DISCORD_BOT_TOKEN")
    if (token.isNullOrBlank()) {
        System.err.println("Set DISCORD_BOT_TOKEN in the environment or in a .env file here.")
        exitProcess(1)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val commands = MusicCommands(manager, catalog, scope)
    val jda = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
        // Our hook below shuts JDA down, so the goodbye is sent before the connection closes.
        .setEnableShutdownHook(false)
        .enableCache(CacheFlag.VOICE_STATE)
        .setMemberCachePolicy(MemberCachePolicy.VOICE)
        .setAudioModuleConfig(AudioModuleConfig().withDaveSessionFactory(JDaveSessionFactory()))
        .addEventListeners(commands)
        .build()
        .awaitReady()
    Runtime.getRuntime().addShutdownHook(Thread {
        commands.announceShutdown()
        jda.shutdown()
    })
    // Commands are read from chat; an empty list removes any slash commands still registered.
    jda.updateCommands().queue()
    println("Auralis is online as ${jda.selfUser.name} in ${jda.guilds.size} server(s).")
    println("Invite: " + jda.getInviteUrl(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS,
        Permission.VOICE_CONNECT, Permission.VOICE_SPEAK))
}

/** Finds, resolves and decodes a song the way a server's player would, and reports each step. */
private fun check(query: String, catalog: AuralisCatalog, manager: AudioPlayerManager): Boolean = runBlocking {
    val selection = catalog.select(query.ifBlank { "Never Gonna Give You Up" })
    selection?.playlistTitle?.let { println("PLAYLIST -> $it: ${selection.tracks.size} songs") }
    val track = selection?.tracks?.firstOrNull()
    println("SEARCH -> ${track?.let { "${if (it.isMusicVideo) "video" else "song"}: ${it.title} by ${it.artist} (${it.id})" } ?: "nothing"}")
    if (track == null) return@runBlocking false
    val url = catalog.streamUrl(track)
    println("STREAM -> ${if (url == null) "none" else "ok"}")
    if (url == null) return@runBlocking false

    val loaded = CompletableFuture<AudioTrack?>()
    manager.loadItem(url, object : AudioLoadResultHandler {
        override fun trackLoaded(track: AudioTrack) { loaded.complete(track) }
        override fun playlistLoaded(playlist: AudioPlaylist) { loaded.complete(playlist.tracks.firstOrNull()) }
        override fun noMatches() { loaded.complete(null) }
        override fun loadFailed(exception: FriendlyException) { println("LOAD -> ${exception.message}"); loaded.complete(null) }
    })
    val audioTrack = loaded.get(30, TimeUnit.SECONDS)
    println("LOAD -> ${audioTrack?.let { "ok, ${formatTime(it.duration)} of ${it.sourceManager?.sourceName}" } ?: "failed"}")
    if (audioTrack == null) return@runBlocking false

    val player = manager.createPlayer()
    player.playTrack(audioTrack)
    var frames = 0
    val deadline = System.currentTimeMillis() + 15_000
    while (frames < 250 && System.currentTimeMillis() < deadline) {
        val frame = player.provide(100, TimeUnit.MILLISECONDS)
        if (frame != null && frame.dataLength > 0) frames++
    }
    player.destroy()
    println("DECODE -> $frames Opus frames (${frames * 20 / 1000}s of audio)")
    println("DAVE -> ${runCatching { JDaveSessionFactory(); "ok" }.getOrElse { "failed: $it" }}")
    frames >= 250
}

private fun dotEnv(key: String): String? = sequenceOf(File(".env"), File("../.env")).firstOrNull { it.isFile }
    ?.readLines()?.firstOrNull { it.trim().startsWith("$key=") }
    ?.substringAfter('=')?.trim()?.trim('"')
