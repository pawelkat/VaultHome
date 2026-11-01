package com.vaulthome.app.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class AskSettingsTest {
    @Test fun defaults() {
        val s = AskSettings()
        assertEquals("http://192.168.1.18:8080", s.nitroBaseUrl)
        assertEquals(true, s.nitroEnabled)
        assertEquals(true, s.askEnabled)
        assertEquals(false, s.groundNitro)
    }

    @Test fun normalize() {
        assertEquals("http://192.168.1.18:8080", AskSettings.normalizeBaseUrl(" 192.168.1.18:8080/ "))
        assertEquals("http://nitro.lan:8080", AskSettings.normalizeBaseUrl("http://nitro.lan:8080/v1/"))
        assertEquals("https://x.example", AskSettings.normalizeBaseUrl("https://x.example"))
        assertEquals(AskSettings.DEFAULT_NITRO_URL, AskSettings.normalizeBaseUrl("  "))
    }
}
