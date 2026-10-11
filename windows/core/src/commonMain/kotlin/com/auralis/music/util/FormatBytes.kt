package com.auralis.music.util

import java.text.DecimalFormat

/** "1.2 GB", "537 MB", "0 B" — the phone's storage size format. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    val df = if (digitGroups == 0) DecimalFormat("#") else DecimalFormat("#.#")
    return "${df.format(value)} ${units[digitGroups]}"
}
