package com.vaulthome.app.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class AskSettingsTest {
    @Test fun defaults() {
        val s = AskSettings()
        assertEquals("http://192.168.1.18:8082", s.nitroBaseUrl)
        assertEquals(emptySet<String>(), s.excludedProviderIds)
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
        assertEquals(AskTarget.Remote, AskSettings(excludedProviderIds = setOf(ProviderRouting.TOWERX_PROVIDER_ID)).target)
        assertEquals(AskTarget.Local, AskSettings(excludedProviderIds = setOf(ProviderRouting.NITRO_PROVIDER_ID, ProviderRouting.TOWERX_PROVIDER_ID)).target)
    }

    @Test fun excludeUnpinsAndParseRoundTrips() {
        val pinned = AskSettings(pinnedProviderId = "towerx-qwen")
        val off = pinned.exclude("towerx-qwen", true)
        assertEquals(setOf("towerx-qwen"), off.excludedProviderIds)
        assertEquals(null, off.pinnedProviderId)
        assertEquals(setOf("nitro5-qwen"), off.exclude("nitro5-qwen", true).exclude("towerx-qwen", false).excludedProviderIds)
        assertEquals(setOf("a", "b"), AskSettings.parseIds(" b, a, b, "))
        assertEquals("", AskSettings.encodeIds(emptySet()))
        assertEquals("a,b", AskSettings.encodeIds(setOf("b", "a")))
    }

    @Test fun migrateExcludedOnceFromOldFlags() {
        assertEquals(emptySet<String>(), AskSettings.migrateExcluded(null, nitroEnabled = true, towerxEnabled = true))
        assertEquals(setOf("nitro5-qwen"), AskSettings.migrateExcluded(null, nitroEnabled = false, towerxEnabled = true))
        assertEquals(setOf("towerx-qwen"), AskSettings.migrateExcluded(null, nitroEnabled = true, towerxEnabled = false))
        assertEquals(setOf("nitro5-qwen", "towerx-qwen"), AskSettings.migrateExcluded(null, nitroEnabled = false, towerxEnabled = false))
        // Already migrated: a present value (even blank) is kept; the old flags are ignored.
        assertEquals(emptySet<String>(), AskSettings.migrateExcluded("", nitroEnabled = false, towerxEnabled = false))
        assertEquals(setOf("mac-qwen"), AskSettings.migrateExcluded("mac-qwen", nitroEnabled = false))
    }

    @Test fun topBarBadgeLabels() {
        assertEquals("Remote · Nitro", AskBadge.label(AskTarget.Remote))
        assertEquals("Local · on-device", AskBadge.label(AskTarget.Local))
        assertEquals("Remote · Nitro", AskBadge.label(AskSettings().target))
    }
}
