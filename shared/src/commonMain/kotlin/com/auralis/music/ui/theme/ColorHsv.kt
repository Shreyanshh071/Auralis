package com.auralis.music.ui.theme

/** [argb] to hue (0..360), saturation and value (0..1) in [hsv], as android.graphics.Color.colorToHSV. */
expect fun colorToHsv(argb: Int, hsv: FloatArray)
