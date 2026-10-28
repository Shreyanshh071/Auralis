package com.auralis.music.windows.connect

import com.auralis.music.data.connect.ConnectDeviceNames

internal fun desktopDeviceName(host: String?, manufacturer: String?, family: String?, product: String?): String {
    fun useful(raw: String?): String? = ConnectDeviceNames.clean(raw)?.takeUnless {
        it.lowercase() in setOf("default string", "system product name", "system family", "to be filled by o.e.m.", "unknown", "not specified")
    }
    val model = useful(family) ?: useful(product)?.let { listOfNotNull(useful(manufacturer), it).joinToString(" ") }
    val hostname = useful(host)
    return when {
        model != null -> model
        hostname != null -> hostname
        else -> "Windows computer"
    }
}
