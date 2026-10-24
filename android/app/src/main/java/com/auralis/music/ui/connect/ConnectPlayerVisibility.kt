package com.auralis.music.ui.connect

import com.auralis.music.data.connect.ConnectUi

/** An available device or an old session is not a current connection. */
internal fun shouldShowPlayerDevicePill(ui: ConnectUi): Boolean = ui.enabled &&
    (ui.transfer?.failed == false || ui.output?.let { it.active && !it.isThis } == true)
