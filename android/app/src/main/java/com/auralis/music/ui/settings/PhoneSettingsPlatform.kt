package com.auralis.music.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import com.auralis.music.data.network.UpdateChecker
import com.auralis.music.data.network.discord.DiscordGatewayManager
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.i18n.AppLanguage
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.screens.UpdateFlow
import com.auralis.music.ui.screens.UpdateFlowScreen
import com.auralis.music.ui.screens.UpdatePhase
import com.auralis.music.util.StorageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [SettingsPlatform] on the phone: exactly what the Settings pages called before they were
 * shared. [context] is the activity, as the pages had (language changes and the updater use it).
 */
class PhoneSettingsPlatform(private val context: Context) : SettingsPlatform {
    override val appVersion: String get() = UpdateChecker.getCurrentVersion(context)

    override val openSystemAppSettings: (() -> Unit) = {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    override fun systemColorScheme(isDark: Boolean): ColorScheme? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null

    override val appLanguage = object : AppLanguageControl {
        override fun currentLabel(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val locales = context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
                if (locales != null && !locales.isEmpty) return locales[0].let { it.getDisplayName(it) }
                return str(Strings.system_default)
            }
            val tag = AppLanguage.storedTag(context)
            if (tag.isBlank()) return str(Strings.system_default)
            return java.util.Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
        }
        override fun currentTag() = AppLanguage.currentTag(context)
        override fun available() = AppLanguage.available(context)
        override fun apply(tag: String) = AppLanguage.apply(context, tag)
    }

    override val storage = object : StorageControl {
        override fun downloadedSongsBytes(): Long = StorageUtils.getDownloadedSongsSizeBytes()
        override fun songCacheBytes() = StorageUtils.getSongCacheSizeBytes(context)
        override fun imageCacheBytes() = StorageUtils.getImageCacheSizeBytes(context)
        override fun clearSongCache() = StorageUtils.clearSongCache(context)
        override fun clearImageCache() = StorageUtils.clearImageCache(context)
    }

    override val history = object : HistoryControl {
        override suspend fun clearListenHistory() {
            val db = com.auralis.music.data.local.AuralisDatabase.getInstance(context)
            db.historyDao().clearHistory()
            db.playCountDao().clearPlayCounts()
            db.playbackEventDao().clearAllEvents()
        }
        override suspend fun clearSearchHistory() {
            com.auralis.music.data.local.AuralisDatabase.getInstance(context).searchHistoryDao().clearSearchHistory()
        }
    }

    override val updater = object : UpdaterControl {
        override val isChecking: StateFlow<Boolean> get() = updateChecking
        override fun checkNow() = UpdateFlow.open(context)
        @Composable
        override fun UpdateFlowOverlay(onDismiss: () -> Unit) = UpdateFlowScreen(onDismiss = onDismiss)
    }

    override val discord = object : DiscordControl {
        override val note: String? = null
        override fun connect(onResult: (Boolean, String, String) -> Unit) =
            DiscordGatewayManager.getInstance(context).beginAuthorization(onResult)
        override fun disconnect() = DiscordGatewayManager.getInstance(context).disconnect()
        override fun refresh() = DiscordGatewayManager.getInstance(context).pushPresence()
    }

    private companion object {
        /** One shared view of the update flow's phase. */
        val updateChecking: StateFlow<Boolean> by lazy {
            UpdateFlow.phase.map { it == UpdatePhase.Checking }
                .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), SharingStarted.Eagerly, UpdateFlow.phase.value == UpdatePhase.Checking)
        }
    }

    override val nowPlaying = object : NowPlayingControl {
        private val player get() = AuralisAudioPlayer.getInstance(context)
        override val track: StateFlow<Track?> get() = player.currentTrack
        override val isPlaying: StateFlow<Boolean> get() = player.isPlaying
        override val positionMs: StateFlow<Long> get() = player.playbackPositionMs
        override val durationMs: StateFlow<Long> get() = player.durationMs
        override fun seekTo(ms: Long) = player.seekTo(ms)
    }
}
