package com.auralis.music.data.connect

/**
 * Display names for the device picker. A device shows its nickname, else the name its platform
 * reports, else a plain fallback ("Android phone", "Windows computer"). Devices that end up with
 * the same name remain distinguishable. Matching models use a short numbered label.
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

    private fun name(device: ConnectDevice): String =
        clean(device.nickname) ?: clean(device.name) ?: fallback(device.platform, device.type)

    // A model name is the visible identity. Internal IDs stay in the protocol only.
    fun base(device: ConnectDevice): String = if (device.type == ConnectDeviceType.COMPUTER)
        name(device).substringBefore(" (").substringBefore(" · ") else name(device)

    fun labels(devices: List<ConnectDevice>): Map<String, String> = buildMap {
        devices.distinctBy { it.id }.groupBy { base(it).lowercase() }.values.forEach { group ->
            group.sortedWith(compareBy({ it.createdMs }, { it.id })).forEachIndexed { index, device ->
                put(device.id, base(device) + if (index == 0) "" else " (${index + 1})")
            }
        }
    }
}