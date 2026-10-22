package com.auralis.music.data.connect

/**
 * Display names for the device picker. A device shows its nickname, else the name its platform
 * reports, else a plain fallback ("Android phone", "Windows computer"). Devices that end up with
 * the same name get " (2)", " (3)"... in the order they were first registered, so they stay apart.
 */
object ConnectDeviceNames {
    fun fallback(platform: ConnectPlatform, type: ConnectDeviceType): String = when (type) {
        ConnectDeviceType.PHONE -> if (platform == ConnectPlatform.ANDROID) "Android phone" else "Phone"
        ConnectDeviceType.TABLET -> if (platform == ConnectPlatform.ANDROID) "Android tablet" else "Tablet"
        ConnectDeviceType.COMPUTER -> if (platform == ConnectPlatform.WINDOWS) "Windows computer" else "Computer"
        ConnectDeviceType.SPEAKER -> "Speaker"
        ConnectDeviceType.BROWSER -> "Web browser"
    }

    /** Collapses whitespace and control characters; blank becomes null. */
    fun clean(raw: String?): String? = raw
        ?.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }?.joinToString("")
        ?.replace(Regex(" +"), " ")?.trim()?.take(60)?.takeIf { it.isNotBlank() }

    fun base(device: ConnectDevice): String =
        clean(device.nickname) ?: clean(device.name) ?: fallback(device.platform, device.type)

    /** Device id to the label shown for it, distinct for every device in [devices]. */
    fun labels(devices: List<ConnectDevice>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        devices.distinctBy { it.id }.groupBy { base(it).lowercase() }.values.forEach { group ->
            val ordered = group.sortedWith(compareBy({ it.createdMs }, { it.id }))
            ordered.forEachIndexed { i, d -> result[d.id] = if (i == 0) base(d) else "${base(d)} (${i + 1})" }
        }
        return result
    }
}
