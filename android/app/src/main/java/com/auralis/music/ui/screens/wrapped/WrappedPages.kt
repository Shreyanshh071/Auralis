package com.auralis.music.ui.screens.wrapped

import com.auralis.music.ui.i18n.str

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auralis.music.R
import kotlinx.coroutines.delay

private val Dim = Color.White.copy(alpha = 0.8f)
private val Faint = Color.White.copy(alpha = 0.7f)

/** Fades and slides its content in once [visible] turns true, after [delayMs]. */
@Composable
private fun Reveal(visible: Boolean, delayMs: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(1_000, delayMillis = delayMs)) + slideInVertically(tween(1_000, delayMillis = delayMs))
    ) { content() }
}

/** Latches true the first time the page is shown, so content doesn't re-hide on swipe back. */
@Composable
private fun rememberShown(isVisible: Boolean, delayMs: Long = 0): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(isVisible) {
        if (isVisible && !shown) {
            if (delayMs > 0) delay(delayMs)
            shown = true
        }
    }
    return shown
}

private fun minutesOf(ms: Long) = ms / 60_000L

private fun minutesLabel(minutes: Long) = if (minutes == 1L) "1 minute" else "$minutes minutes"

@Composable
private fun DisplayTitle(text: String, fontSize: Int = 40) {
    Text(
        text = text,
        fontFamily = WrappedDisplayFont,
        fontSize = fontSize.sp,
        lineHeight = (fontSize + 4).sp,
        color = Color.White,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: (@Composable () -> Unit)? = null) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        modifier = modifier.height(50.dp)
    ) {
        if (content != null) content()
        else Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
fun WrappedIntroPage(isVisible: Boolean, onNext: () -> Unit) {
    val shown = rememberShown(isVisible, 200)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Reveal(shown, 200) {
                Image(
                    painter = painterResource(R.drawable.ic_auralis_logo),
                    contentDescription = str(R.string.auralis),
                    modifier = Modifier.size(100.dp).clip(CircleShape)
                )
            }
            Spacer(Modifier.height(16.dp))
            Reveal(shown, 400) {
                // Offset grey copies behind the white title give it a stacked, extruded look.
                Box(Modifier.padding(horizontal = 16.dp)) {
                    val base = TextStyle(fontFamily = WrappedDisplayFont, fontSize = 50.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)
                    AutoResizingText("AURALIS", base.copy(color = Color.DarkGray), Modifier.padding(start = 2.dp, top = 2.dp))
                    AutoResizingText("AURALIS", base.copy(color = Color.Gray), Modifier.padding(start = 1.dp, top = 1.dp))
                    AutoResizingText("AURALIS", base.copy(color = Color.White))
                }
            }
            Spacer(Modifier.height(8.dp))
            Reveal(shown, 600) {
                Text(
                    str(R.string.it_s_time_to_see_what_you_ve_been_listen),
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(1_000, delayMillis = 1_000)) + slideInVertically(tween(1_000, delayMillis = 1_000)) { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
        ) {
            PillButton(str(R.string.let_s_go), onNext)
        }
    }
}

@Composable
fun WrappedMinutesTeasePage(message: MinutesMessage, isVisible: Boolean, onAdvance: () -> Unit) {
    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(3_500)
            onAdvance()
        }
    }
    val shown = rememberShown(isVisible)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(1_000)) + scaleIn(tween(1_000), initialScale = 0.9f)
        ) {
            Text(
                text = message.tease.uppercase(),
                modifier = Modifier.padding(horizontal = 24.dp),
                color = Color.White,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center,
                fontFamily = WrappedDisplayFont
            )
        }
    }
}

@Composable
fun WrappedMinutesPage(message: MinutesMessage, minutes: Long, isVisible: Boolean) {
    Box(Modifier.fillMaxSize()) {
        CornerOutlines(isVisible, perCorner = 4, spread = 150)
        Column(
            Modifier.fillMaxSize().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                message.tease,
                modifier = Modifier.padding(horizontal = 24.dp),
                color = Color.White,
                fontSize = 22.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            CountUpNumber(minutes, isVisible)
            Spacer(Modifier.height(16.dp))
            BoldSpansText(
                message.reveal,
                TextStyle(color = Dim, fontSize = 16.sp, textAlign = TextAlign.Center),
                Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

/** "You've listened to / N / unique songs" style page. */
@Composable
fun WrappedCountPage(title: String, count: Int, subtitle: String, shapes: List<FloatingShape>, isVisible: Boolean) {
    val shown = rememberShown(isVisible)
    Box(Modifier.fillMaxSize()) {
        FloatingShapes(shapes = shapes)
        Column(
            Modifier.fillMaxSize().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Reveal(shown, 200) {
                Text(title, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
            Spacer(Modifier.height(32.dp))
            CountUpNumber(count.toLong(), isVisible)
            Spacer(Modifier.height(16.dp))
            Reveal(shown, 600) {
                Text(subtitle, color = Dim, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
    }
}

/** One big artwork with a name under it: top song, top album, top artist. */
@Composable
fun WrappedHeroPage(
    heading: String,
    headingIsDisplay: Boolean,
    imageUrl: String?,
    imageShape: Shape,
    name: String?,
    caption: String,
    isVisible: Boolean,
    background: @Composable () -> Unit
) {
    val shown = rememberShown(isVisible)
    Box(Modifier.fillMaxSize()) {
        background()
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Reveal(shown, 200) {
                if (headingIsDisplay) DisplayTitle(heading)
                else Text(heading, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(32.dp))
            Reveal(shown, 400) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(imageShape)
                        .background(Color.White.copy(alpha = 0.08f))
                )
            }
            Spacer(Modifier.height(16.dp))
            Reveal(shown, 600) {
                Text(
                    name ?: str(R.string.no_data),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Reveal(shown, 900) {
                Text(caption, color = Dim, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

data class RankedRow(val title: String, val subtitle: String, val imageUrl: String?)

/** Numbered top-5 list (songs, albums or artists). */
@Composable
fun WrappedTopFivePage(
    heading: String,
    rows: List<RankedRow>,
    imageShape: Shape,
    isVisible: Boolean,
    background: @Composable () -> Unit
) {
    val shown = rememberShown(isVisible, 200)
    Box(Modifier.fillMaxSize()) {
        background()
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Reveal(shown, 200) { DisplayTitle(heading) }
            Spacer(Modifier.height(32.dp))
            if (rows.isEmpty()) {
                Reveal(shown, 400) { Text(str(R.string.no_data), color = Dim, fontSize = 16.sp) }
            }
            Column(Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, row ->
                    AnimatedVisibility(
                        visible = shown,
                        enter = fadeIn(tween(600, delayMillis = 400 + index * 200)) +
                            slideInVertically(tween(600, delayMillis = 400 + index * 200))
                    ) {
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}",
                                fontFamily = WrappedDisplayFont,
                                fontSize = 34.sp,
                                color = Dim,
                                modifier = Modifier.width(44.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            AsyncImage(
                                model = row.imageUrl,
                                contentDescription = row.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(60.dp).clip(imageShape).background(Color.White.copy(alpha = 0.08f))
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(row.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(row.subtitle, color = Faint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class PlaylistSaveState { Idle, Saving, Saved }

@Composable
fun WrappedPlaylistPage(
    year: Int,
    coverUrls: List<String?>,
    saveState: PlaylistSaveState,
    isVisible: Boolean,
    onSave: () -> Unit
) {
    val shown = rememberShown(isVisible, 200)
    Box(Modifier.fillMaxSize()) {
        FloatingShapes(shapes = listOf(FloatingShape.Circle))
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Reveal(shown, 0) {
                AutoResizingText(
                    str(R.string.your_personal_playlist_is_ready),
                    TextStyle(fontFamily = WrappedDisplayFont, fontSize = 40.sp, color = Color.White, textAlign = TextAlign.Center)
                )
            }
            Spacer(Modifier.height(32.dp))
            Reveal(shown, 200) { WrappedCover(year, coverUrls) }
            Spacer(Modifier.height(24.dp))
            Reveal(shown, 400) {
                Text(str(R.string.your_x_wrapped, year), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.height(40.dp))
            Reveal(shown, 600) {
                PillButton(
                    text = if (saveState == PlaylistSaveState.Saved) str(R.string.playlist_saved) else str(R.string.create_playlist_2),
                    onClick = { if (saveState == PlaylistSaveState.Idle) onSave() }
                ) {
                    if (saveState == PlaylistSaveState.Saving) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (saveState == PlaylistSaveState.Saved) str(R.string.playlist_saved) else str(R.string.create_playlist_2),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 2×2 collage of the year's top artwork under an "AURALIS WRAPPED" banner. */
@Composable
private fun WrappedCover(year: Int, coverUrls: List<String?>) {
    Column(
        Modifier
            .size(240.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AURALIS", fontFamily = WrappedDisplayFont, fontSize = 22.sp, color = Color(0xFFE8C27A))
        Text(str(R.string.wrapped_x, year), fontFamily = WrappedDisplayFont, fontSize = 12.sp, color = Color(0xFFE8C27A).copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Column {
                for (r in 0 until 2) {
                    Row(Modifier.weight(1f)) {
                        for (c in 0 until 2) {
                            AsyncImage(
                                model = coverUrls.getOrNull(r * 2 + c),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxSize().background(Color(0xFF2A1C12))
                            )
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFC9971C).copy(alpha = 0.30f)))
                )
            )
        }
    }
}

@Composable
fun WrappedConclusionPage(isVisible: Boolean, onClose: () -> Unit) {
    val shown = rememberShown(isVisible)
    Box(Modifier.fillMaxSize()) {
        FloatingShapes(shapes = listOf(FloatingShape.Line, FloatingShape.Circle))
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Reveal(shown, 0) {
                Image(
                    painter = painterResource(R.drawable.ic_auralis_logo),
                    contentDescription = str(R.string.auralis),
                    modifier = Modifier.size(80.dp).clip(CircleShape)
                )
            }
            Spacer(Modifier.height(16.dp))
            Reveal(shown, 200) {
                Text(str(R.string.thank_you_for_listening), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            Reveal(shown, 400) {
                Text(str(R.string.see_you_next_year_on_auralis), fontSize = 15.sp, color = Faint, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(32.dp))
            Reveal(shown, 600) { PillButton(str(R.string.close_wrapped), onClose) }
        }
    }
}

@Composable
fun WrappedEmptyPage(year: Int, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        FloatingShapes(shapes = listOf(FloatingShape.Circle))
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            DisplayTitle(str(R.string.nothing_to_wrap_yet), fontSize = 32)
            Spacer(Modifier.height(16.dp))
            Text(
                str(R.string.listen_to_some_music_in_x_and_your_wrapp, year),
                color = Dim,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            PillButton(str(R.string.close), onClose)
        }
    }
}

internal fun minutesCaption(prefix: String, ms: Long) = "$prefix ${minutesLabel(minutesOf(ms))}"
