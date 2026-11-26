package com.vaulthome.app.x4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class X4SettingsTest {
    @Test fun defaultIsLastKnownLease() = assertEquals("http://192.168.1.27", X4Settings().baseUrl)

    @Test fun normalize() {
        assertEquals("http://192.168.1.31", X4Settings.normalizeBaseUrl(" 192.168.1.31/ "))
        assertEquals("http://192.168.1.31", X4Settings.normalizeBaseUrl("http://192.168.1.31/mcp/"))
        assertEquals("http://x4.local:8080", X4Settings.normalizeBaseUrl("x4.local:8080"))
        assertEquals(X4Settings.DEFAULT_X4_URL, X4Settings.normalizeBaseUrl(""))
        assertEquals(8080, X4Settings.port("http://x4.local:8080"))
        assertEquals(80, X4Settings.port("http://192.168.1.27"))
        assertEquals("192.168.1.27", X4Settings.host("http://192.168.1.27:80"))
    }

    @Test fun problems() {
        assertNull(X4Settings.problem("http://192.168.1.27"))
        assertNull(X4Settings.problem("http://10.0.0.5"))
        assertNull(X4Settings.problem("http://127.0.0.1:1234"))
        assertNotNull(X4Settings.problem("http://192.168.1.26")) // CrowPanel
        assertNotNull(X4Settings.problem("https://192.168.1.27"))
        assertNotNull(X4Settings.problem("http://8.8.8.8"))
        assertNotNull(X4Settings.problem("http://example.com"))
        assertTrue(X4Settings.isLanHost("172.20.1.1"))
        assertFalse(X4Settings.isLanHost("172.32.1.1"))
    }
}
