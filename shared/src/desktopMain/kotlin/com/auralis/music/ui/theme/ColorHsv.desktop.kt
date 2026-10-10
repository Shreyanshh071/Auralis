package com.auralis.music.ui.theme

actual fun colorToHsv(argb: Int, hsv: FloatArray) {
    val hsb = java.awt.Color.RGBtoHSB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, null)
    hsv[0] = hsb[0] * 360f
    hsv[1] = hsb[1]
    hsv[2] = hsb[2]
}
