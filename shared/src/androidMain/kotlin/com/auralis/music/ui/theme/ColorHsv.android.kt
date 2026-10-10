package com.auralis.music.ui.theme

actual fun colorToHsv(argb: Int, hsv: FloatArray) = android.graphics.Color.colorToHSV(argb, hsv)
