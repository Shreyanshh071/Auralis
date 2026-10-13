package com.auralis.music.windows.dev

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.blur

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.repository.KeyValueSearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import com.auralis.music.desktop.library.DesktopHistoryRepository
import com.auralis.music.desktop.library.DesktopLibraryPlatform
import com.auralis.music.desktop.library.DesktopLibraryRepository
import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.ui.components.DesktopPlatformActions
import com.auralis.music.ui.screens.AboutScreen
import com.auralis.music.ui.screens.AiLyricsTranslationScreen
import com.auralis.music.ui.screens.AppearanceScreen
import com.auralis.music.ui.screens.ContentSettingsScreen
import com.auralis.music.ui.screens.DiscordIntegrationScreen
import com.auralis.music.ui.screens.PrivacySettingsScreen
import com.auralis.music.ui.screens.StorageSettingsScreen
import com.auralis.music.ui.screens.UpdaterScreen
import com.auralis.music.ui.screens.YouTubeAccountScreen
import com.auralis.music.ui.viewmodel.LibraryViewModel
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.windows.profile.DesktopProfile
import com.auralis.music.windows.profile.DesktopSettings
import com.auralis.music.windows.settings.DesktopSettingsPlatform
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Development check: draws Windows screens to PNG files without opening a window (Compose's
 * ImageComposeScene), so pages can be checked without touching the desktop or taking focus.
 * Uses a throwaway data folder; never the user's %APPDATA%.
 *
 * usage: renderScreens <outDir> [screen names...]
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    val only = args.drop(1).toSet()
    com.auralis.music.ui.i18n.DesktopStrings.install()
    val data = kotlin.io.path.createTempDirectory("auralis-render").toFile()
    val stores = DesktopDataStores(File(data, "settings"))
    val library = DesktopLibraryRepository(File(data, "library.json"))
    val history = DesktopHistoryRepository(File(data, "history.json"), library)
    val stats = com.auralis.music.desktop.library.DesktopListeningStats(File(data, "stats.json"))
    val sampleTrack = com.auralis.music.domain.model.Track(id = "preview", title = "Auralis preview song", artist = "Preview artist", duration = 180)
    kotlinx.coroutines.runBlocking { history.addToHistory(sampleTrack) }
    stats.record("preview", sampleTrack, System.currentTimeMillis(), 75_000)
    val search = SearchRepositoryImpl(
        InnerTubeClient(), SearchSuggestionsClient(),
        KeyValueSearchHistoryStore(PropertiesFileKeyValueStore(File(data, "search-history.properties")))
    )
    val messages = mutableListOf<String>()
    val actions = DesktopPlatformActions { messages += it }
    val libraryVm = LibraryViewModel(libraryRepository = library, historyRepository = history,
        platform = DesktopLibraryPlatform(PropertiesFileKeyValueStore(File(data, "flags.properties"))) { messages += it })
    val platform = DesktopSettingsPlatform(null, history, search, discord = null)

    // Representative fixtures only; rendering Home never fetches recommendations or reads user data.
    val starterHome = com.auralis.music.ui.viewmodel.HomeUiState()
    val previewSongs = starterHome.speedDialPages.flatten().mapNotNull { it.track }.distinctBy { it.id }
    val personalHome = starterHome.copy(
        recentTracks = previewSongs.take(8).map { com.auralis.music.domain.model.HistoryEntry(it) },
        quickPicks = previewSongs.drop(8).take(9),
        dailyDiscover = previewSongs.drop(17).map { com.auralis.music.domain.model.DailyDiscoverItem(previewSongs.first(), it) }
    )
    val homePreview: @Composable (Boolean) -> Unit = { personal ->
        com.auralis.music.windows.home.DesktopHomeScreen(
            if (personal) personalHome else starterHome.copy(quickPicks = previewSongs.drop(8).take(9)),
            onPlay = { _, _ -> }, onShortcut = {}, onAlbum = {}, onMenu = {}, onSearch = {}, onSurprise = {}, onChip = {}
        )
    }

    // The real cover's palette, extracted as the app does, for the player background previews.
    val coverPalette = runCatching {
        val url = com.auralis.music.ui.components.getOptimizedThumbnailUrl(previewSongs.first().thumbnail)!!
        com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use {
            com.auralis.music.windows.settings.extractArtworkPalette(javax.imageio.ImageIO.read(it.body!!.byteStream()))
        }
    }.getOrDefault(com.auralis.music.ui.theme.DefaultArtworkPalette)
    val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "create-playlist" to { com.auralis.music.windows.library.CreatePlaylistDialog(true, "Connected YouTube account", {}, { _, _ -> }) },
        "shortcuts" to {
            val pins = listOf(
                com.auralis.music.domain.model.SpeedDialItem(id = "album-local:preview", name = "gili gili",
                    type = com.auralis.music.domain.model.SpeedDialType.ALBUM, image = previewSongs.firstOrNull()?.thumbnail,
                    album = com.auralis.music.domain.model.PlaylistResult("local:preview", "gili gili", author = "Playlist · 97 songs"), isPinned = true),
                com.auralis.music.domain.model.SpeedDialItem(id = "artist-preview", name = "Billy Idol",
                    type = com.auralis.music.domain.model.SpeedDialType.ARTIST, image = previewSongs.getOrNull(1)?.thumbnail, isPinned = true))
            Box(Modifier.fillMaxSize().padding(16.dp)) { com.auralis.music.windows.home.HomeShortcuts(pins, pins, {}, {}, {}) }
        },
        "playlist-header" to {
            com.auralis.music.windows.player.ArtworkAppBackground(previewSongs.firstOrNull()?.thumbnail, coverPalette) {
            com.auralis.music.ui.library.LibraryScreen(
                currentTrackId = null, isPlaying = false,
                uiState = com.auralis.music.ui.viewmodel.LibraryUiState(selectedPlaylist =
                    com.auralis.music.domain.model.Playlist("sp_preview", "gili gili", tracks = previewSongs.take(16), coverUrl = previewSongs.firstOrNull()?.thumbnail)),
                onFilterSelect = {}, onCreatePlaylist = {}, onDeletePlaylist = {}, onPlaylistSelect = {},
                onTrackClick = { _, _ -> }, onFavoriteToggle = {})
            }
        },
        "library-artists" to {
            com.auralis.music.ui.library.LibraryScreen(
                currentTrackId = null, isPlaying = false,
                uiState = com.auralis.music.ui.viewmodel.LibraryUiState(savedArtists = listOf(
                    com.auralis.music.domain.model.SavedArtist("artist-preview", "Billy Idol", previewSongs.getOrNull(1)?.thumbnail, "1.23M subscribers"))),
                onFilterSelect = {}, onCreatePlaylist = {}, onDeletePlaylist = {}, onPlaylistSelect = {},
                onTrackClick = { _, _ -> }, onFavoriteToggle = {})
        },
        "youtube-library" to {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(232.dp).fillMaxHeight()) {
                    com.auralis.music.windows.LibrarySidebar(previewLibraryEntries(previewSongs), null, false, {})
                }
                Box(Modifier.weight(1f)) { homePreview(true) }
            }
        },
        "youtube-accounts" to {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
                homePreview(true)
                Box(Modifier.width(160.dp).height(36.dp)) {
                    val identity = com.auralis.music.windows.profile.YouTubeIdentity("Preview listener", "@listener", previewSongs.first().thumbnail)
                    com.auralis.music.windows.profile.YouTubeAccountMenu(
                        com.auralis.music.windows.profile.YouTubeLibraryState(identity = identity,
                            accounts = listOf(com.auralis.music.windows.profile.YouTubeAccountSummary("preview", identity)), activeId = "preview"),
                        {}, {}, {}, {}, {}, {})
                }
            }
        },
        "browser-signin" to {
            Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Box(androidx.compose.ui.Modifier.fillMaxSize().blur(18.dp)) { homePreview(true) }
                com.auralis.music.windows.profile.SignInGlassSurface {
                Column(androidx.compose.ui.Modifier.width(560.dp).padding(24.dp)) {
                    Text("Sign in to YouTube Music", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                    Spacer(androidx.compose.ui.Modifier.height(20.dp))
                    com.auralis.music.windows.profile.BrowserYouTubePicker(listOf(
                        com.auralis.music.windows.profile.BrowserYouTubeLogin.Profile("Firefox", "Personal", File(data, "fake.sqlite"), true),
                        com.auralis.music.windows.profile.BrowserYouTubeLogin.Profile("Zen", "Default", File(data, "fake.sqlite"), true)))
                }
                }
            }
        },
        "sidebar-expanded" to { Box(androidx.compose.ui.Modifier.width(280.dp).fillMaxHeight()) {
            com.auralis.music.windows.LibrarySidebar(previewLibraryEntries(previewSongs), "local:1", false, {})
        } },
        "sidebar-collapsed" to { Box(androidx.compose.ui.Modifier.width(64.dp).fillMaxHeight()) {
            com.auralis.music.windows.LibrarySidebar(previewLibraryEntries(previewSongs), "local:1", true, {})
        } },
        "dock" to { Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF181818)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            com.auralis.music.windows.GlassDock(com.auralis.music.windows.DesktopDestination.HOME, com.auralis.music.windows.DockTabs, {})
        } },
        "themes-colours" to { com.auralis.music.windows.settings.DesktopThemesAndColours {} },
        "song-context-menu" to {
            Box(androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp)) {
                com.auralis.music.windows.library.DesktopSongMenuContent(
                    listOf("Play next", "Add to queue", "Add to playlist", "Add to library", "Pin to Speed dial", "Download", "View artist", "View album", "Share").map {
                        com.auralis.music.ui.components.GlassMenuItem(it, androidx.compose.material.icons.Icons.Default.Add) {}
                    }, {}, background = { com.auralis.music.ui.components.CoverSheetBackground(previewSongs.firstOrNull()?.thumbnail) })
            }
        },
        "search-glass" to {
            Box(androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp)) {
                com.auralis.music.windows.search.SearchDropdownSurface(true, previewSongs.firstOrNull()?.thumbnail,
                    androidx.compose.ui.Modifier.width(480.dp).height(460.dp)) {
                    Column(androidx.compose.ui.Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("Search Auralis", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text("Mockingbird", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                        Text("Mockingbird Eminem", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                        Text("Heat Waves", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        "now-playing-sidebar" to {
            com.auralis.music.windows.player.ArtworkAppBackground(previewSongs.firstOrNull()?.thumbnail, com.auralis.music.ui.theme.ArtworkPalette(
                androidx.compose.ui.graphics.Color(0xFF7A3D92), androidx.compose.ui.graphics.Color(0xFF49315B), androidx.compose.ui.graphics.Color(0xFFAC5271))) {
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { homePreview(true) }
                    com.auralis.music.windows.player.NowPlayingSidebarContent(previewSongs.first(), {}, androidx.compose.ui.Modifier.width(300.dp).fillMaxHeight(),
                        com.auralis.music.ui.theme.ArtworkPalette(androidx.compose.ui.graphics.Color(0xFF7A3D92), androidx.compose.ui.graphics.Color(0xFF49315B), androidx.compose.ui.graphics.Color(0xFFAC5271))) {
                        com.auralis.music.windows.player.SidebarLyricsView(
                            com.auralis.music.domain.model.LyricsData(com.auralis.music.domain.model.SyncType.LINE_SYNC,
                                listOf("Let the music fill the room", "One more moment", "We follow every sound", "Until the morning comes").mapIndexed { index, text ->
                                    com.auralis.music.domain.model.LyricLine(index * 5000L, text)
                                }, provider = com.auralis.music.domain.model.LyricsProvider.LOCAL),
                            androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(6000L) }, {})
                    }
                }
            }
        },
        "lyrics-hover" to {
            Box(androidx.compose.ui.Modifier.width(320.dp).height(320.dp)
                .padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                com.auralis.music.windows.player.ArtworkAura(
                    com.auralis.music.ui.theme.ArtworkPalette(androidx.compose.ui.graphics.Color(0xFF266885), androidx.compose.ui.graphics.Color(0xFF49315B), androidx.compose.ui.graphics.Color(0xFFAC5271)),
                    androidx.compose.ui.Modifier.fillMaxSize())
                Column(androidx.compose.ui.Modifier.padding(8.dp)) {
                    com.auralis.music.windows.player.SidebarLyricLine("Let the music fill the room", false, true, {})
                    com.auralis.music.windows.player.SidebarLyricLine("One more moment", true, true, {})
                    com.auralis.music.windows.player.SidebarLyricLine("We follow every sound", false, true, {}, previewHovered = true)
                    com.auralis.music.windows.player.SidebarLyricLine("Until the morning comes", false, true, {})
                }
            }
        },
        "home" to { homePreview(true) },
        "home-service" to {
            val albums = previewSongs.take(12).mapIndexed { i, song -> com.auralis.music.domain.model.PlaylistResult("preview-album-$i", song.title, song.thumbnail, "Album · ${song.artist}") }
            com.auralis.music.windows.home.DesktopHomeScreen(personalHome,
                serviceRecents = (previewSongs + previewSongs).mapIndexed { i, track -> track.copy(id = "recent-$i") },
                quickPicks = previewSongs.take(12), favoriteIds = setOfNotNull(previewSongs.getOrNull(1)?.id),
                familiarArtists = listOf("Tame Impala" to 14, "Kanye West" to 9, "Radiohead" to 7, "Arijit Singh" to 5, "KK" to 3, "Karan Aujla" to 2)
                    .mapIndexed { i, (name, plays) -> com.auralis.music.windows.home.FamiliarArtist(name, plays, "UCpreview$i", previewSongs.getOrNull(i)?.thumbnail) },
                serviceShelves = listOf(
                    com.auralis.music.windows.home.DesktopHomeShelf("releases", "Albums & singles", albums = albums),
                    com.auralis.music.windows.home.DesktopHomeShelf("listen-again", "Listen again", "PREVIEW LISTENER", tracks = previewSongs.take(6)),
                    com.auralis.music.windows.home.DesktopHomeShelf("library", "From your library", albums = albums.reversed())),
                onPlay = { _, _ -> }, onShortcut = {}, onAlbum = {}, onMenu = {}, onSearch = {}, onSurprise = {}, onChip = {})
        },
        "home-wide" to { homePreview(true) },
        "home-narrow" to { homePreview(true) },
        "home-new" to { homePreview(false) },
        "home-grid" to { com.auralis.music.windows.home.DesktopHomeScreen(
            personalHome, recentsGrid = true,
            onPlay = { _, _ -> }, onShortcut = {}, onAlbum = {}, onMenu = {}, onSearch = {}, onSurprise = {}, onChip = {}) },
        "home-loading" to { com.auralis.music.windows.home.DesktopHomeScreen(
            com.auralis.music.ui.viewmodel.HomeUiState(speedDialPages = emptyList(), isLoading = true),
            onPlay = { _, _ -> }, onShortcut = {}, onAlbum = {}, onMenu = {}, onSearch = {}, onSurprise = {}, onChip = {}) },
        "album" to { com.auralis.music.ui.screens.AlbumScreen(
            com.auralis.music.domain.model.PlaylistResult("VLpreview", "Preview playlist", author = "Preview listener"),
            previewSongs.take(12), isLoading = false, currentTrackId = previewSongs.getOrNull(1)?.id, isPlaying = true,
            onTrackClick = { _, _ -> }, onFavoriteToggle = {}, onBack = {}) },
        "player-mesh" to { com.auralis.music.windows.player.PlayerBackdrop("Live Mesh", previewSongs.firstOrNull()?.thumbnail, coverPalette) },
        "player-glow" to { com.auralis.music.windows.player.PlayerBackdrop("Glow motion", null, coverPalette) },
        "player-dynamic" to { com.auralis.music.windows.player.PlayerBackdrop("Dynamic", previewSongs.firstOrNull()?.thumbnail, coverPalette) },
        "lyrics-fluid" to { LyricRendererPreview(fluid = true) },
        "lyrics-letters" to { LyricRendererPreview(fluid = false) },
        "lyrics-dots" to {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF2A2230)),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                listOf(0f, .2f, .5f, .8f, 1f).forEach { p ->
                    com.auralis.music.ui.lyrics.LyricsDots(p, elapsedMs = 1000L, remainingMs = 1000L, dotSize = 14.dp)
                }
            }
        },
        "player-gradient" to { com.auralis.music.windows.player.PlayerBackdrop("Gradient", null, coverPalette) },
        "familiar" to { Box(androidx.compose.ui.Modifier.padding(24.dp)) {
            com.auralis.music.windows.home.FamiliarArtistsRow(listOf("Sidney Gish" to 14, "Kanye West" to 9, "Radiohead" to 7, "Arijit Singh" to 5, "KK" to 3, "Karan Aujla" to 2)
                .mapIndexed { i, (name, plays) -> com.auralis.music.windows.home.FamiliarArtist(name, plays, "UCpreview$i", previewSongs.getOrNull(i)?.thumbnail) },
                {}, com.auralis.music.windows.home.FamiliarArtistActions({ if (it.plays > 8) true else false }, { false }, {}, {}, { false }, {}, {}, { false }, {}, {}),
                showControls = true)
        } },
        "titlebar-narrow" to { com.auralis.music.windows.WindowTitleBar(
            com.auralis.music.windows.TitleBarActions(true, false, {}, {}, {}, "Shreyanshh", {}, {}, {}, {}, search = {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val state = androidx.compose.runtime.remember { com.auralis.music.windows.search.DesktopSearchState(search, scope) }
                com.auralis.music.windows.search.FindMusicDropdown(state, null, false, { _, _ -> }, {}, {}, {}, {}, {}, bar = true)
            }),
            "Auralis", null, false, {}, {}, {}) },
        "titlebar" to { com.auralis.music.windows.WindowTitleBar(
            com.auralis.music.windows.TitleBarActions(true, false, {}, {}, {}, "Sign in", {}, {}, {}, {}, search = {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val state = androidx.compose.runtime.remember { com.auralis.music.windows.search.DesktopSearchState(search, scope) }
                com.auralis.music.windows.search.FindMusicDropdown(state, null, false, { _, _ -> }, {}, {}, {}, {}, {}, bar = true)
            }),
            "Auralis", null, false, {}, {}, {}) },
        "history" to { com.auralis.music.windows.history.HistorySheet(
            listOf(com.auralis.music.domain.model.HistoryEntry(sampleTrack, System.currentTimeMillis())), isPlaying = false,
            onTrackClick = { _, _ -> }, onRemoveFromHistory = {}, onClearHistory = {}, onDismiss = {}) },
        "stats" to { com.auralis.music.windows.history.ListeningStatsPage(stats, null, false, {}) },
        "wrapped" to { com.auralis.music.windows.history.ListeningStatsPage(stats, null, true, {}) },
        "together" to { com.auralis.music.windows.together.ListenTogetherPage(
            androidx.compose.runtime.remember { com.auralis.music.windows.together.DesktopTogether(null,
                com.auralis.music.windows.together.FirestoreRest.fromResource(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())) }, {}) },
        "profile" to { DesktopProfile(stores, libraryVm, history, search, hasActiveTrack = false, onDismiss = {}) },
        "settings" to { DesktopSettings(stores, history, search, hasActiveTrack = false, onOpenProfile = {}, onDismiss = {}) },
        "settings-liquidify" to { DesktopSettings(stores, history, search, hasActiveTrack = false, onOpenProfile = {}, onDismiss = {}) },
        "content-liquidify" to { ContentSettingsScreen(onDismiss = {}) },
        "profile-liquidify" to { DesktopProfile(stores, libraryVm, history, search, hasActiveTrack = false, onDismiss = {}) },
        "appearance" to { com.auralis.music.windows.settings.DesktopAppearanceSettings(onDismiss = {}) },
        "audio" to { com.auralis.music.windows.settings.DesktopAudioSettings(onDismiss = {}) },
        "account" to { com.auralis.music.windows.profile.AccountPage(androidx.compose.runtime.remember { com.auralis.music.windows.profile.DesktopAccount(File(data, "account.bin"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())) }, {}) },
        "content" to { ContentSettingsScreen(onDismiss = {}) },
        "privacy" to { PrivacySettingsScreen(onDismiss = {}) },
        "storage" to { com.auralis.music.windows.settings.DesktopStorageSettings(onDismiss = {}) },
        "ai-translation" to { AiLyricsTranslationScreen(onDismiss = {}) },
        "updater" to { UpdaterScreen(onDismiss = {}) },
        "about" to { AboutScreen(onDismiss = {}) },
        "discord" to { DiscordIntegrationScreen(onDismiss = {}) },
        "youtube-account" to { YouTubeAccountScreen(onDismiss = {}) },
    )
    for ((name, content) in screens) {
        if (only.isNotEmpty() && name !in only) continue
        val result = runCatching {
            val scene = ImageComposeScene(width = when (name) { "titlebar" -> 1280; "titlebar-narrow" -> 900; "home-service", "album" -> 1700; "home-wide" -> 1440; "home-narrow" -> 660; else -> 1040 },
                height = when (name) { "titlebar", "titlebar-narrow" -> 44; "dock" -> 140; "sidebar-expanded", "sidebar-collapsed" -> 520; "familiar" -> 980; "home-service", "home-wide", "album" -> 1000; else -> 760 }, density = Density(1f)) {
                com.auralis.music.ui.theme.AuralisDesktopTheme(platformActions = actions) {
                    CompositionLocalProvider(
                        com.auralis.music.data.datastore.LocalSettingsStores provides stores,
                        com.auralis.music.ui.settings.LocalSettingsPlatform provides platform,
                        com.auralis.music.ui.theme.LocalAppearanceSettingsStore provides stores.appearance
                    ) {
                        androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                            if (name.endsWith("-liquidify")) {
                                com.auralis.music.windows.player.ArtworkAppBackground(previewSongs.firstOrNull()?.thumbnail,
                                    com.auralis.music.ui.theme.ArtworkPalette(androidx.compose.ui.graphics.Color(0xFF7A3D92), androidx.compose.ui.graphics.Color(0xFF49315B), androidx.compose.ui.graphics.Color(0xFFAC5271))) { content() }
                            } else content()
                        }
                    }
                }
            }
            // Let effects, flows and enter animations settle over ~1.5 s of frames.
            var image = scene.render(0)
            val frameCount = if (name.endsWith("-liquidify") || name in setOf("now-playing-sidebar", "lyrics-hover", "browser-signin", "player-dynamic")) 12 else 90
            for (frame in 1..frameCount) {
                if (frame % 3 == 0) Thread.sleep(30)
                image = scene.render(frame * (1_500_000_000L / frameCount))
            }
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(png)
            scene.close()
        }
        println(if (result.isSuccess) "rendered $name" else "FAILED $name: ${result.exceptionOrNull()}")
        result.exceptionOrNull()?.printStackTrace()
    }
    if (messages.isNotEmpty()) println("messages: $messages")
    kotlin.system.exitProcess(0)
}

/** "You've been my muse for a long time", frozen part-way through "muse", in the phone's renderers. */
@androidx.compose.runtime.Composable
private fun LyricRendererPreview(fluid: Boolean) {
    val words = listOf("You've ", "been ", "my ", "muse ", "for ", "a ", "long ", "time").mapIndexed { i, w ->
        com.auralis.music.domain.model.LyricWord(w, i * 500L, 500L)
    }
    val line = com.auralis.music.domain.model.LyricLine(0L, "You've been my muse for a long time", words = words)
    val clock = androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(1_700L) }
    val style = androidx.compose.ui.text.TextStyle(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
        .background(androidx.compose.ui.graphics.Color(0xFF3A2A33)).padding(40.dp)) {
        if (fluid) com.auralis.music.ui.lyrics.renderers.FluidLyricLine(line, words, clock, style, true)
        else com.auralis.music.ui.lyrics.renderers.AppleMusicLyricsLine(true, line, 4_000L, words, true, clock.value,
            androidx.compose.ui.graphics.Color.White.copy(alpha = .35f), androidx.compose.ui.graphics.Color.White,
            androidx.compose.ui.text.style.TextAlign.Left, androidx.compose.ui.Alignment.Start, 34f, 1.3f)
    }
}

private fun previewLibraryEntries(songs: List<com.auralis.music.domain.model.Track>) = listOf(
    com.auralis.music.windows.LibraryEntry("liked", "Liked Songs", "Playlist · 214 songs", null, liked = true) {}) +
    listOf("Late night drive", "Gym", "Hindi hits", "Indie mix", "Lo-fi").mapIndexed { i, name ->
        com.auralis.music.windows.LibraryEntry("local:$i", name, "Playlist · ${12 + i * 7} songs", songs.getOrNull(i)?.thumbnail) {}
    }
