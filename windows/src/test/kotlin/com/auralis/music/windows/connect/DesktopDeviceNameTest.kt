package com.auralis.music.windows.connect

import kotlin.test.*

class DesktopDeviceNameTest {
    @Test fun usesLaptopModelWithoutHostnameOrAccountIdentity() {
        assertEquals("LOQ 15IRX9", desktopDeviceName("Laptop-1", "LENOVO", "LOQ 15IRX9", "83DV"))
        assertEquals("Dell XPS 13", desktopDeviceName("Work-PC", "Dell", null, "XPS 13"))
    }
    @Test fun missingHardwareInfoStillHasAnHonestDeviceName() {
        assertEquals("My-PC", desktopDeviceName("My-PC", null, "System Family", "System Product Name"))
        assertEquals("Windows computer", desktopDeviceName(null, null, null, null))
    }
}
