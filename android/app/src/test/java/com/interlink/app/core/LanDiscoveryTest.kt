package com.interlink.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class LanDiscoveryTest {
    private fun name(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    @Test
    fun parsesDesktopBeacon() {
        val device = parseLanBeacon("ILANN|${name("Patrick's Laptop")}|laptop|27341", "172.20.10.3", 1234)
        assertEquals("Patrick's Laptop", device?.name)
        assertEquals("laptop", device?.role)
        assertEquals("172.20.10.3", device?.ip)
        assertEquals(27341, device?.port)
        assertEquals(1234L, device?.lastSeen)
    }

    @Test
    fun rejectsMalformedBeacons() {
        assertNull(parseLanBeacon("ILWHOIS?", "172.20.10.3", 0))
        assertNull(parseLanBeacon("ILANN|${name("Laptop")}|server|27341", "172.20.10.3", 0))
        assertNull(parseLanBeacon("ILANN|${name("Laptop")}|laptop|not-a-port", "172.20.10.3", 0))
        assertNull(parseLanBeacon("ILANN|%%%|laptop|27341", "172.20.10.3", 0))
    }
}
