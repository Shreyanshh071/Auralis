package com.auralis.music.domain.model

data class DiscordRpcSettings(
    val isLoggedIn: Boolean = false,
    val discordUsername: String = "",
    val discordDiscriminator: String = "",
    val discordAvatarUrl: String = "",
    val discordToken: String = "",
    val enableRichPresence: Boolean = false,
    val activityStatus: String = "Online", // Online, Idle, DND
    val updateIntervalSeconds: Int = 20,
    val platform: String = "Android", // Android, Desktop, Web
    val activityName: String = "Auralis",
    val activityDetails: String = "Song title",
    val activityState: String = "Artist name",
    val showRpcWhenPaused: Boolean = false,
    val activityType: String = "Listening", // Listening, Playing, Streaming
    val largeImage: String = "Album artwork",
    val largeText: String = "Album name",
    val smallImage: String = "Artist artwork"
)
