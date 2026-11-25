package com.auralis.music.ui.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.music.R
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicBackground

/** One ticked song riding behind the dragged one. Position is read while drawing only. */
internal class PlaylistTrainCar(val item: PlaylistTrackItem, startY: Float) {
    var y by mutableFloatStateOf(startY)
    var velocity = 0f
    var tilt by mutableFloatStateOf(0f)
}

/** The springs behind the group-drag train. */
internal object PlaylistTrainPhysics {
    /** Cars drawn behind the dragged song; the rest only count in its "+N" badge. */
    const val VISIBLE_CARS = 4

    /**
     * Moves [car] one frame toward [target] on an under-damped spring. Each car further back is a
     * little softer, so the train stretches out on a quick move and wobbles as it bunches up.
     */
    fun step(car: PlaylistTrainCar, target: Float, carIndex: Int, dt: Float) {
        // Stiff enough that the cars stay close behind; still a touch under-damped for the wobble.
        val stiffness = (620f - carIndex * 70f).coerceAtLeast(380f)
        val damping = 2f * kotlin.math.sqrt(stiffness) * 0.72f
        // Small sub-steps keep the spring stable on a slow frame.
        val steps = kotlin.math.ceil(dt / 0.008f).toInt().coerceIn(1, 6)
        val h = dt / steps
        repeat(steps) {
            val accel = stiffness * (target - car.y) - damping * car.velocity
            car.velocity += accel * h
            car.y += car.velocity * h
        }
        car.tilt = tilt(car.velocity, maxDegrees = 2.5f)
    }

    /** A sideways lean for a vertical speed (px/s), so a moving card swings a little. */
    fun tilt(velocity: Float, maxDegrees: Float): Float =
        (velocity / 1600f).coerceIn(-1f, 1f) * maxDegrees
}

/** The round tick beside each song while the order is unlocked. */
@Composable
internal fun PlaylistSelectTick(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, tween(160), label = "tickFill")
    // A small pop when ticked.
    val pop by animateFloatAsState(
        if (selected) 1f else 0.9f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "tickPop"
    )
    val ring = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .size(30.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onClickLabel = str(R.string.playlist_select_song),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { scaleX = pop; scaleY = pop }
                .clip(CircleShape)
                .background(LIME_TEXT.copy(alpha = fill))
                .border(1.5.dp, if (fill < 0.5f) ring else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (fill > 0.01f) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp).graphicsLayer { alpha = fill; scaleX = fill; scaleY = fill }
                )
            }
        }
    }
}

/** A song row lifted off the list: the dragged song, or a ticked song riding behind it. */
@Composable
internal fun PlaylistDraggedRowVisual(track: Track, ticked: Boolean, carried: Int, asCard: Boolean) {
    val minutes = track.duration / 60
    val seconds = track.duration % 60
    val subtitle = if (track.duration > 0 && track.duration != 210L) {
        "${track.artist} • $minutes:${if (seconds < 10) "0" else ""}$seconds"
    } else track.artist
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (asCard) {
                    Modifier
                        .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = .45f), spotColor = Color.Black.copy(alpha = .45f))
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
                } else Modifier.background(MaterialTheme.dynamicBackground)
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaylistSelectTick(selected = ticked, onClick = {}, modifier = Modifier.padding(end = 10.dp))
        ArtworkCard(
            sizeToConstraints = true,
            url = track.thumbnail,
            modifier = Modifier.size(48.dp),
            cornerRadius = 8.dp,
            contentDescription = track.title,
            fallbackTrack = track
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (carried > 0) {
            Text(
                text = "+$carried",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(LIME_TEXT)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = str(R.string.drag_to_reorder_song),
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp)
            )
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
