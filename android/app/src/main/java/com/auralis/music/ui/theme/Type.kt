package com.auralis.music.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.auralis.music.R

// ============================================================================
// 🔤 GOOGLE FONTS PROVIDER & FONT FAMILIES
// ============================================================================

val GoogleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val OutfitFont = GoogleFont("Outfit")
val PlusJakartaSansFont = GoogleFont("Plus Jakarta Sans")

/**
 * Display & Heading Font Family: Outfit (Modern geometric styling with negative tracking)
 */
val OutfitFontFamily = FontFamily(
    Font(googleFont = OutfitFont, fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = OutfitFont, fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = OutfitFont, fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = OutfitFont, fontProvider = GoogleFontProvider, weight = FontWeight.Bold),
    Font(googleFont = OutfitFont, fontProvider = GoogleFontProvider, weight = FontWeight.ExtraBold),
    Font(googleFont = OutfitFont, fontProvider = GoogleFontProvider, weight = FontWeight.Black)
)

/**
 * Body & Label Font Family: Plus Jakarta Sans (Crisp, clean legibility for dense UI)
 */
val PlusJakartaSansFontFamily = FontFamily(
    Font(googleFont = PlusJakartaSansFont, fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = PlusJakartaSansFont, fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = PlusJakartaSansFont, fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = PlusJakartaSansFont, fontProvider = GoogleFontProvider, weight = FontWeight.Bold)
)

val Typography = auralisTypography(OutfitFontFamily, PlusJakartaSansFontFamily)
