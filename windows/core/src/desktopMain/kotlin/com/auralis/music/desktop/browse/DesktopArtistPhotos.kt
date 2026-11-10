package com.auralis.music.desktop.browse

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.auralis.music.data.network.AppleArtistPhoto
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The same verified Apple portraits as Android, decoded before the visible photo changes. */
internal object DesktopArtistPhotos {
    private val cache by lazy {
        PropertiesFileKeyValueStore(File(desktopAppDataDir(), "cache/apple-artist-photos.properties"))
    }

    suspend fun resolve(page: ArtistPage): String? = withContext(Dispatchers.IO) {
        val key = page.artist.name.trim().lowercase(Locale.ROOT)
        val saved = cache.getString(key, "")?.takeIf { AppleArtistPhoto.isArtistPhoto(it) }
        val photo = saved ?: AppleArtistPhoto.find(page.artist.name,
            page.topSongs.map { it.title } + (page.albums + page.singles).map { it.title })
            ?: return@withContext null
        val context = PlatformContext.INSTANCE
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context).data(photo).size(1200).build())
        if (result !is SuccessResult) return@withContext null
        if (saved == null) cache.putStrings(mapOf(key to photo))
        photo
    }
}
