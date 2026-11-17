package com.auralis.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.components.ArtworkCard
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the hero ring is telling the user. */
internal enum class OrbitState { IDLE, CONNECTING, LIVE, ERROR }

/** One listener bubble riding the ring. */
internal data class OrbitMember(val initial: String, val color: Color, val isHost: Boolean)

/** Characters a room code can contain (see ListenTogetherManager.generateRoomCode); 6 long. */
internal const val ROOM_CODE_LENGTH = 6

internal fun sanitizeRoomCode(raw: String): String =
    raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(ROOM_CODE_LENGTH)

internal fun parseAvatarColor(hex: String, fallback: Color): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    fallback
}

/**
 * The hero of Listen Together: the shared song inside a ring, with everyone in the room riding it.
 *
 * - IDLE: a slow breathing arc around whatever this phone is playing (what a new room would share).
 * - CONNECTING: a sweeping arc chasing itself.
 * - LIVE: a full, slowly turning gradient ring; listener bubbles orbit while music plays.
 * - ERROR: the ring settles into the error colour.
 */
@Composable
internal fun TogetherOrbit(
    artworkUrl: String?,
    contentDescription: String?,
    state: OrbitState,
    isPlaying: Boolean,
    accent: Color,
    trackColor: Color,
    members: List<OrbitMember> = emptyList(),
    size: Dp = 224.dp,
    modifier: Modifier = Modifier
) {
    val errorColor = MaterialTheme.colorScheme.error
    val ringColor by animateColorAsState(
        if (state == OrbitState.ERROR) errorColor else accent, tween(400), label = "orbitRingColor"
    )
    val infinite = rememberInfiniteTransition(label = "orbit")
    val sweepRotation = infinite.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "orbitSweep"
    )
    val liveRotation = infinite.animateFloat(
        0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "orbitLive"
    )
    val breathe = infinite.animateFloat(
        .35f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orbitBreathe"
    )
    val memberSpin = infinite.animateFloat(
        0f, 360f, infiniteRepeatable(tween(48_000, easing = LinearEasing)), label = "orbitMembers"
    )
    val fill by animateFloatAsState(
        when (state) {
            OrbitState.IDLE -> .28f
            OrbitState.CONNECTING -> .30f
            OrbitState.LIVE, OrbitState.ERROR -> 1f
        },
        spring(dampingRatio = .7f, stiffness = Spring.StiffnessLow), label = "orbitFill"
    )
    // The artwork breathes with the music; it rests when paused.
    val artPulse = infinite.animateFloat(
        .985f, 1.015f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orbitArt"
    )
    val pulsing = state == OrbitState.LIVE && isPlaying

    val ringWidth = size * .065f
    val artInset = size * .15f
    val bubble = size * .155f

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = ringWidth.toPx()
            val inset = stroke / 2f + bubble.toPx() * .18f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            when (state) {
                OrbitState.CONNECTING -> rotate(sweepRotation.value) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(ringColor.copy(alpha = 0f), ringColor, ringColor)),
                        startAngle = 0f, sweepAngle = 360f * fill, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                OrbitState.IDLE -> rotate(-90f) {
                    drawArc(
                        ringColor.copy(alpha = breathe.value), 0f, 360f * fill, false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                OrbitState.LIVE -> rotate(if (isPlaying) liveRotation.value else 0f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(ringColor, ringColor.copy(alpha = .45f), ringColor.copy(alpha = .85f), ringColor)
                        ),
                        startAngle = 0f, sweepAngle = 360f * fill, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                OrbitState.ERROR -> drawArc(ringColor, -90f, 360f * fill, false, topLeft, arcSize, style = Stroke(stroke))
            }
        }

        Box(
            Modifier
                .padding(artInset)
                .fillMaxSize()
                .graphicsLayer {
                    val s = if (pulsing) artPulse.value else 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(trackColor),
            contentAlignment = Alignment.Center
        ) {
            if (!artworkUrl.isNullOrBlank()) {
                ArtworkCard(
                    url = artworkUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = size,
                    contentDescription = contentDescription
                )
            } else {
                Icon(Icons.Default.Groups, null, tint = accent, modifier = Modifier.size(size * .22f))
            }
        }

        // Listener bubbles on the ring, evenly spaced, drifting round while the room plays.
        val shown = members.take(8)
        if (shown.isNotEmpty()) {
            val spinning = state == OrbitState.LIVE && isPlaying
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val radiusPx = (constraints.maxWidth / 2f) - with(androidx.compose.ui.platform.LocalDensity.current) {
                    (ringWidth / 2 + bubble * .18f).toPx()
                }
                val bubblePx = with(androidx.compose.ui.platform.LocalDensity.current) { bubble.toPx() }
                val width = constraints.maxWidth / 2f
                val height = constraints.maxHeight / 2f
                shown.forEachIndexed { index, member ->
                    Box(
                        Modifier
                            .offset {
                                // Read the spin here (layout), so drifting never recomposes the page.
                                val spin = if (spinning) memberSpin.value else 0f
                                val angle = Math.toRadians((-90.0 + spin + index * 360.0 / shown.size))
                                val cx = width + radiusPx * cos(angle).toFloat()
                                val cy = height + radiusPx * sin(angle).toFloat()
                                IntOffset((cx - bubblePx / 2).roundToInt(), (cy - bubblePx / 2).roundToInt())
                            }
                            .size(bubble)
                            .clip(CircleShape)
                            .background(member.color)
                            .border(if (member.isHost) 2.5.dp else 1.5.dp, if (member.isHost) accent else Color.White.copy(alpha = .7f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            member.initial,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = (bubble.value * .42f).sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * A compact at-a-glance tile: small label over a bold value. [emphasis] tints the tile when the
 * value needs attention (live, syncing, slow).
 */
@Composable
internal fun TogetherStatusTile(
    label: String,
    value: String,
    icon: ImageVector,
    emphasis: Color?,
    foreground: Color,
    card: Color,
    modifier: Modifier = Modifier
) {
    val container by animateColorAsState(emphasis?.copy(alpha = .18f) ?: card, tween(300), label = "tileBg")
    val valueColor by animateColorAsState(emphasis ?: foreground, tween(300), label = "tileFg")
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(container)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(icon, null, tint = valueColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, color = foreground.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1)
        Text(
            value,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Six code boxes. Editable: one hidden text field drives them, so typing, deleting and pasting a
 * whole code all work; the box being typed into is outlined. Read-only: shows a room's code.
 * [onComplete] fires once all six characters are in.
 */
@Composable
internal fun RoomCodeBoxes(
    code: String,
    accent: Color,
    foreground: Color,
    card: Color,
    modifier: Modifier = Modifier,
    onCodeChange: ((String) -> Unit)? = null,
    onComplete: (String) -> Unit = {},
    enabled: Boolean = true
) {
    val editable = onCodeChange != null
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }

    Box(modifier.fillMaxWidth()) {
        if (editable) {
            BasicTextField(
                value = TextFieldValue(code, TextRange(code.length)),
                onValueChange = { value ->
                    val clean = sanitizeRoomCode(value.text)
                    if (clean != code) {
                        onCodeChange?.invoke(clean)
                        if (clean.length == ROOM_CODE_LENGTH) {
                            keyboard?.hide()
                            onComplete(clean)
                        }
                    }
                },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { if (code.length >= 4) onComplete(code) }),
                // Invisible: the boxes below draw the text. Kept full-size so a tap anywhere focuses it.
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                modifier = Modifier
                    .matchParentSize()
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (editable) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled
                    ) {
                        focus.requestFocus()
                        keyboard?.show()
                    } else Modifier
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(ROOM_CODE_LENGTH) { index ->
                val char = code.getOrNull(index)
                val active = editable && focused && (index == code.length || (index == ROOM_CODE_LENGTH - 1 && code.length == ROOM_CODE_LENGTH))
                val border by animateColorAsState(
                    when {
                        active -> accent
                        char != null -> accent.copy(alpha = .35f)
                        else -> Color.Transparent
                    },
                    tween(180), label = "codeBoxBorder"
                )
                val pop by animateFloatAsState(
                    if (char != null) 1f else .92f,
                    spring(dampingRatio = .45f, stiffness = 700f), label = "codeBoxPop"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(.82f)
                        .scale(pop)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (char != null) accent.copy(alpha = .12f) else card)
                        .border(2.dp, border, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = char?.toString() ?: "",
                        color = foreground,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** A short rule summary pill under "Start a room". */
@Composable
internal fun RuleChip(text: String, accent: Color, foreground: Color) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = .12f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = foreground.copy(alpha = .85f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** A pill that pulses its dot, for "LIVE" in the room header. */
@Composable
internal fun LivePill(text: String, color: Color) {
    val infinite = rememberInfiniteTransition(label = "livePill")
    val dot = infinite.animateFloat(
        .35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "liveDot"
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = .16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .graphicsLayer { alpha = dot.value }
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
