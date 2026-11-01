package com.vaulthome.app.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class AskSettingsTest {
    @Test fun defaults() {
        val s = AskSettings()
        assertEquals("http://192.168.1.18:8082", s.nitroBaseUrl)
        assertEquals(true, s.nitroEnabled)
        assertEquals(true, s.askEnabled)
    }

    @Test fun normalize() {
        assertEquals("http://192.168.1.18:8080", AskSettings.normalizeBaseUrl(" 192.168.1.18:8080/ "))
        assertEquals("http://nitro.lan:8080", AskSettings.normalizeBaseUrl("http://nitro.lan:8080/v1/"))
        assertEquals("https://x.example", AskSettings.normalizeBaseUrl("https://x.example"))
        assertEquals(AskSettings.DEFAULT_NITRO_URL, AskSettings.normalizeBaseUrl("  "))
    }

    @Test fun tailscaleUrlIsHttpsAndStable() {
        assertEquals("https://nitro5.tailf52603.ts.net", AskSettings.TAILSCALE_NITRO_URL)
        assertEquals(AskSettings.TAILSCALE_NITRO_URL, AskSettings.normalizeBaseUrl("https://nitro5.tailf52603.ts.net/v1/"))
    }

    @Test fun migratesLegacyRawLlamaDefaultOnly() {
        assertEquals(AskSettings.DEFAULT_NITRO_URL, AskSettings.migrateStoredUrl(null))
        assertEquals(AskSettings.DEFAULT_NITRO_URL, AskSettings.migrateStoredUrl("http://192.168.1.18:8080"))
        assertEquals("http://nitro.lan:8080", AskSettings.migrateStoredUrl("http://nitro.lan:8080"))
        assertEquals(AskSettings.TAILSCALE_NITRO_URL, AskSettings.migrateStoredUrl(AskSettings.TAILSCALE_NITRO_URL))
    }

    @Test fun askModeNeedsAskEnabled() {
        assertEquals(false, AskSettings().askActive)
        assertEquals(true, AskSettings(askMode = true).askActive)
        assertEquals(false, AskSettings(askEnabled = false, askMode = true).askActive)
        assertEquals(AskTarget.Remote, AskSettings().target)
        assertEquals(AskTarget.Local, AskSettings(nitroEnabled = false).target)
    }

    @Test fun topBarBadgeLabels() {
        assertEquals("Remote · Nitro", AskBadge.label(AskTarget.Remote))
        assertEquals("Local · on-device", AskBadge.label(AskTarget.Local))
        assertEquals("Remote · Nitro", AskBadge.label(AskSettings().target))
    }
}
