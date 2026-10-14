package com.auralis.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// The phone downloads Outfit and Plus Jakarta Sans from Google Fonts; desktop bundles the same
// static files (resources/fonts, SIL Open Font License) so text looks identical offline.

private fun bundled(file: String, weight: FontWeight): androidx.compose.ui.text.font.Font {
    val bytes = checkNotNull(DesktopFontsAnchor::class.java.getResourceAsStream("/fonts/$file")) {
        "Missing bundled font $file"
    }.use { it.readBytes() }
    return Font(identity = file, data = bytes, weight = weight)
}

private object DesktopFontsAnchor

val OutfitFontFamily: FontFamily by lazy {
    FontFamily(
        bundled("Outfit-Regular.ttf", FontWeight.Normal),
        bundled("Outfit-Medium.ttf", FontWeight.Medium),
        bundled("Outfit-SemiBold.ttf", FontWeight.SemiBold),
        bundled("Outfit-Bold.ttf", FontWeight.Bold),
        bundled("Outfit-ExtraBold.ttf", FontWeight.ExtraBold),
        bundled("Outfit-Black.ttf", FontWeight.Black)
    )
}

val PlusJakartaSansFontFamily: FontFamily by lazy {
    FontFamily(
        bundled("PlusJakartaSans-Regular.ttf", FontWeight.Normal),
        bundled("PlusJakartaSans-Medium.ttf", FontWeight.Medium),
        bundled("PlusJakartaSans-SemiBold.ttf", FontWeight.SemiBold),
        bundled("PlusJakartaSans-Bold.ttf", FontWeight.Bold)
    )
}

/**
 * Nunito (SIL Open Font License, Latin subset): rounded strokes, for figures that should read soft
 * (the Stats numbers; Outfit's were too sharp there).
 */
val NunitoFontFamily: FontFamily by lazy {
    FontFamily(
        bundled("Nunito-SemiBold.ttf", FontWeight.SemiBold),
        bundled("Nunito-Bold.ttf", FontWeight.Bold),
        bundled("Nunito-ExtraBold.ttf", FontWeight.ExtraBold)
    )
}

/** The phone's type scale with the bundled fonts. */
val DesktopTypography: Typography by lazy { auralisTypography(OutfitFontFamily, PlusJakartaSansFontFamily) }
