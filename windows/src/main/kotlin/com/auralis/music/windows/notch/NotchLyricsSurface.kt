package com.auralis.music.windows.notch

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.rememberTextMeasurer
import com.auralis.music.ui.theme.DesktopTypography
import com.auralis.music.ui.theme.scriptSafeTypography
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import com.auralis.music.domain.model.LyricsAnimationMode
import kotlin.math.ceil
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.lyrics.PlayerLyricsContent
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.util.Base64

/** Persistent Compose scene: the native shell displays the player's actual rendered ink.
 * Create, render and close on Swing, just like the main player. No second animation engine.
 */
internal class NotchLyricsSurface(val width: Int = 400, glyphMotion: Boolean = true) : AutoCloseable {
    private val position = mutableLongStateOf(0)
    private var line by mutableStateOf<LyricLine?>(null)
    private var nextTime by mutableStateOf<Long?>(null)
    private var sync by mutableStateOf(SyncType.RICHSYNC)
    private var appearance by mutableStateOf(AppearanceSettings())
    private val scene = ImageComposeScene(width * 2, 44, density = Density(2f), coroutineContext = Dispatchers.Swing) {
        CompositionLocalProvider(LocalAppearanceSettings provides appearance,
            com.auralis.music.ui.lyrics.LocalLyricsGlyphMotion provides glyphMotion) {
            MaterialTheme(typography = scriptSafeTypography(DesktopTypography, java.util.Locale.getDefault())) {
                val spicy = LyricsAnimationMode.fromDisplayName(appearance.lyricsAnimation) == LyricsAnimationMode.SPICY_LYRICS
                val style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    letterSpacing = if (spicy) 0.sp else (-.5).sp)
                val measurer = rememberTextMeasurer()
                Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.CenterStart) {
                    line?.let { lyric ->
                        val measured = remember(lyric.text, style) { measurer.measure(lyric.text, style, softWrap = false, maxLines = 1) }
                        val inkWidth = maxOf(width.toFloat() - 8, ceil(measured.size.width / 2f) + 12)
                        val spans = remember(lyric) {
                            LyricsEngine.mapWordsToLineSpans(lyric.text, lyric.words.orEmpty().flatMap { word ->
                                val pieces = word.syllables
                                if (!pieces.isNullOrEmpty() && pieces.joinToString("") { it.word }.trim() == word.word.trim()) pieces else listOf(word)
                            })
                        }
                        val active = spans.lastOrNull { position.longValue >= it.word.time }
                        val caret = active?.let { span ->
                            val from = measured.getHorizontalPosition(span.startIndex, true)
                            val to = measured.getHorizontalPosition(span.endIndex, true)
                            val fraction = span.word.duration?.takeIf { it > 0 }?.let {
                                ((position.longValue - span.word.time).toFloat() / it).coerceIn(0f, 1f)
                            } ?: 1f
                            (from + (to - from) * fraction) / 2f
                        } ?: 0f
                        val scroll = (caret - width * .55f).coerceIn(0f, maxOf(0f, inkWidth - width + 8))
                        Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                            .offset(x = (4 - scroll).dp).width(inkWidth.dp)) {
                            PlayerLyricsContent(lyric, sync, true, true, position, style,
                                Color.White, Color.White, if (spicy) .4f else .3f,
                                TextAlign.Start, Alignment.Start, 1.3f, nextTime)
                        }
                    }
                }
            }
        }
    }

    fun render(lyric: LyricLine, syncType: SyncType, positionMs: Long, settings: AppearanceSettings, nextLineTime: Long? = null): String {
        nextTime = nextLineTime
        line = lyric
        sync = syncType
        position.longValue = positionMs
        appearance = settings
        return scene.render(System.nanoTime()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Base64.getEncoder().encodeToString(it.bytes) }
        }
    }

    override fun close() { scene.close() }
}
