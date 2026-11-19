package com.auralis.music.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.theme.LocalReducedMotion

internal const val AURALIS_GITHUB_URL = "https://github.com/Shreyanshh071/Auralis"
internal const val AURALIS_LICENSE_URL = "$AURALIS_GITHUB_URL/blob/main/LICENSE"
internal const val AURALIS_CHAI_URL = "https://www.buymeachai.in/shreyanshh071"
internal const val AURALIS_DISCORD_URL = "https://discord.gg/6Q2YKz73S"

/** Trailing control of a tour card: a chevron for links, a switch for permissions, or nothing. */
internal sealed interface TourCardTrailing {
    data object Chevron : TourCardTrailing
    data object None : TourCardTrailing
    data class Toggle(val checked: Boolean) : TourCardTrailing
}

internal data class TourCard(
    val icon: ImageVector,
    val tile: Color,
    val title: String,
    val subtitle: String,
    val trailing: TourCardTrailing = TourCardTrailing.Chevron,
    val onClick: (() -> Unit)? = null,
)

/** Pastel icon tiles; their icons use a dark tint of the same hue. */
internal object TourTiles {
    val Yellow = Color(0xFFFFF09A)
    val Blue = Color(0xFF7FD3FF)
    val Indigo = Color(0xFFB8BFFF)
    val Pink = Color(0xFFFFA8E4)
    val Coral = Color(0xFFFFB2A6)
    val Orange = Color(0xFFFFB37A)
    val Green = Color(0xFF8EDF94)
    val Lavender = Color(0xFFC9B8FF)
}

/** A grouped list: large outer corners, tight joins between cards. */
@Composable
internal fun TourCardList(cards: List<TourCard>, ink: Color, modifier: Modifier = Modifier, footer: String? = null) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(cards, key = { _, card -> card.title }) { index, card ->
            val top = if (index == 0) 24.dp else 6.dp
            val bottom = if (index == cards.lastIndex) 24.dp else 6.dp
            TourCardRow(card, ink, RoundedCornerShape(top, top, bottom, bottom))
        }
        if (footer != null) item {
            Text(footer, color = ink.copy(alpha = .5f), fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun TourCardRow(card: TourCard, ink: Color, shape: RoundedCornerShape) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (pressed && !reduced) .97f else 1f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = if (pressed) .8f else .5f, stiffness = 500f), label = "tour card press")
    val click = card.onClick
    val trailing = card.trailing
    val interactive = when {
        click == null -> Modifier
        trailing is TourCardTrailing.Toggle -> Modifier.toggleable(trailing.checked, interactions, null, role = Role.Switch) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); click()
        }
        else -> Modifier.clickable(interactions, null, role = Role.Button) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); click()
        }
    }
    Row(Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
        .background(ink.copy(alpha = if (pressed) .11f else .075f)).then(interactive)
        .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(15.dp)).background(card.tile), contentAlignment = Alignment.Center) {
            Icon(card.icon, null, tint = darkTint(card.tile), modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.title, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(card.subtitle, color = ink.copy(alpha = .62f), fontSize = 13.sp, lineHeight = 18.sp)
        }
        when (trailing) {
            TourCardTrailing.Chevron -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ink.copy(alpha = .7f), modifier = Modifier.size(24.dp))
            is TourCardTrailing.Toggle -> TourSwitch(trailing.checked, card.tile, ink)
            TourCardTrailing.None -> Unit
        }
    }
}

/** Display-only switch; the whole card is the toggle target. */
@Composable
private fun TourSwitch(checked: Boolean, accent: Color, ink: Color) {
    val reduced = LocalReducedMotion.current
    val position by animateFloatAsState(if (checked) 1f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = .7f, stiffness = 500f), label = "tour switch")
    Box(Modifier.width(52.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
        .background(if (checked) accent else ink.copy(alpha = .08f))
        .border(1.5.dp, if (checked) accent else ink.copy(alpha = .45f), RoundedCornerShape(16.dp))
        .padding(4.dp)) {
        Box(Modifier.align(Alignment.CenterStart).graphicsLayer { translationX = position * 20.dp.toPx() }
            .size(24.dp).clip(RoundedCornerShape(12.dp))
            .background(if (checked) darkTint(accent) else ink.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
            Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.Close, null,
                tint = if (checked) accent else Color(0xFF1C1A1A), modifier = Modifier.size(15.dp))
        }
    }
}

private fun darkTint(tile: Color): Color = Color(
    red = tile.red * .32f, green = tile.green * .28f, blue = tile.blue * .3f, alpha = 1f)

internal fun Context.openTourLink(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** What Auralis offers, with its licence and source one tap away. */
@Composable
internal fun TourFeaturesPage(ink: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cards = remember(context) {
        listOf(
            TourCard(Icons.Rounded.FormatAlignCenter, TourTiles.Pink, "Smart lyrics",
                "Synced, word-by-word lyrics for millions of songs.", TourCardTrailing.None),
            TourCard(Icons.Rounded.Download, TourTiles.Green, "Offline playback",
                "Tap ⋮ beside a song or playlist → Download. Play it from Library, even without internet.", TourCardTrailing.None),
            TourCard(Icons.Rounded.Hd, TourTiles.Orange, "Premium quality",
                "Stream and download in the best quality available.", TourCardTrailing.None),
            TourCard(Icons.Rounded.Update, TourTiles.Blue, "Dynamic updates",
                "Always up to date with the built-in updater.", TourCardTrailing.None),
            TourCard(Icons.Rounded.Gavel, TourTiles.Indigo, "License",
                "GNU General Public License v3.0", TourCardTrailing.None),
            TourCard(Icons.Rounded.Code, TourTiles.Lavender, "GitHub",
                "Open-source project", TourCardTrailing.None),
        )
    }
    TourCardList(cards, ink, modifier)
}

/** Stars, chai and Discord: the ways to help Auralis grow. */
@Composable
internal fun TourCommunityPage(ink: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cards = remember(context) {
        listOf(
            TourCard(Icons.Rounded.Star, TourTiles.Yellow, "Star on GitHub",
                "Help more people find Auralis by starring the repository.") { context.openTourLink(AURALIS_GITHUB_URL) },
            TourCard(Icons.Rounded.Forum, TourTiles.Indigo, "Join our Discord",
                "Get the latest updates and chat with the community.") { context.openTourLink(AURALIS_DISCORD_URL) },
            TourCard(Icons.Rounded.EmojiFoodBeverage, TourTiles.Coral, "Buy me a chai",
                "Support development directly.") { context.openTourLink(AURALIS_CHAI_URL) },
        )
    }
    TourCardList(cards, ink, modifier,
        footer = "Auralis is free and open source. Every star, message and chai keeps it going.")
}
