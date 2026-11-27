package com.vaulthome.app.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultLinksTest {
    private val paths = listOf(
        "Daily/2026-11-01.md",
        "Notes/PureGym.md",
        "Notes/VaultHome Watch.md",
        "Notes/A.md",
        "Notes/Zażółć 🚀.md",
        "Trips/Madeira trip 2024-01.md",
        "Projects/VaultHome.md",
        "Archive/Notes/PureGym.md",
    )

    private fun r(raw: String, bases: Collection<String> = emptyList()) = VaultLinks.resolve(raw, paths, bases)

    @Test fun nitroStyleVaultUrlsOpenInApp() {
        assertEquals("Daily/2026-11-01.md", r("https://nitro5.tailf52603.ts.net/vault/Daily/2026-11-01.md"))
        assertEquals("Daily/2026-11-01.md", r("https://nitro5.tailf52603.ts.net/vault/Daily/2026-11-01#Morning"))
        assertEquals("Notes/VaultHome Watch.md", r("https://nitro5.tailf52603.ts.net/vault/Notes/VaultHome%20Watch.md?x=1"))
        assertEquals("Notes/A.md", r("http://192.168.1.18:8082/vault/Notes/A.md"))
        assertEquals("Notes/Zażółć 🚀.md", r("https://NITRO5.tailf52603.ts.net/vault/Notes/Za%C5%BC%C3%B3%C5%82%C4%87%20%F0%9F%9A%80.md"))
    }

    @Test fun towerxStyleVaultUrlsOpenInAppWithoutAHostAllowlist() {
        // Same /vault/<path> shape as Nitro; the host is not special-cased.
        assertEquals(
            "Trips/Madeira trip 2024-01.md",
            r("https://towerx.tailf52603.ts.net/vault/Trips/Madeira%20trip%202024-01.md"),
        )
        assertEquals(
            "Notes/PureGym.md",
            r("https://towerx.tailf52603.ts.net/vault/Notes/PureGym.md#Hours"),
        )
        assertEquals("Daily/2026-11-01.md", r("https://towerx.tailf52603.ts.net/files/Daily/2026-11-01.md"))
    }

    @Test fun relativeBareAndObsidianAndVaultSchemes() {
        assertEquals("Daily/2026-11-01.md", r("/vault/Daily/2026-11-01.md"))
        assertEquals("Daily/2026-11-01.md", r("vault/Daily/2026-11-01.md"))
        assertEquals("Projects/VaultHome.md", r("Projects/VaultHome.md#Ask"))
        assertEquals("Notes/VaultHome Watch.md", r("obsidian://open?vault=Vault&file=Notes/VaultHome%20Watch"))
        assertEquals("Notes/A.md", r("vault://Notes/A.md"))
        assertEquals("Notes/A.md", r("vault:///Notes/A.md"))
        assertEquals("Notes/PureGym.md", r("vault:Notes/PureGym"))
    }

    @Test fun unknownPrefixIsResolvedByLongestSuffixAgainstTheVault() {
        assertEquals("Notes/PureGym.md", r("https://some-host.example/media/export/Notes/PureGym.md"))
        // Longer suffix wins over a shorter one (Notes/PureGym, not Archive/Notes/PureGym).
        assertEquals("Notes/PureGym.md", r("https://x.example/vault/Notes/PureGym.md"))
        assertEquals("Archive/Notes/PureGym.md", r("https://x.example/vault/Archive/Notes/PureGym.md"))
    }

    @Test fun linkBaseFromCapsIsStrippedWhenPresent() {
        val base = listOf("https://towerx.tailf52603.ts.net/vault/")
        assertEquals("Trips/Madeira trip 2024-01.md", r("https://towerx.tailf52603.ts.net/vault/Trips/Madeira%20trip%202024-01.md", base))
        // http vs https on the same host still matches the base.
        assertEquals("Notes/A.md", r("http://towerx.tailf52603.ts.net/vault/Notes/A.md", base))
    }

    @Test fun nonVaultUrlsStayExternal() {
        assertNull(r("https://youtube.com/watch?v=x"))
        assertNull(r("https://example.com/blog/hello"))
        assertNull(r("https://nitro5.tailf52603.ts.net/health"))
        assertNull(r("https://nitro5.tailf52603.ts.net/v1/chat/completions"))
        assertNull(r("mailto:a@b.c"))
        assertNull(r("#heading"))
        assertNull(r("Projects/VaultHome")) // bare path without .md is a wikilink, not a markdown href
        // A /vault/ URL for a note that is not in this vault stays unresolved (browser fallback).
        assertNull(r("https://towerx.tailf52603.ts.net/vault/Notes/DoesNotExist.md"))
    }

    @Test fun candidateStillExtractsAPathWithoutAnIndex() {
        assertEquals(
            "Trips/Madeira trip 2024-01.md",
            VaultLinks.candidate("https://towerx.tailf52603.ts.net/vault/Trips/Madeira%20trip%202024-01.md"),
        )
        assertEquals("Daily/2026-11-01.md", VaultLinks.candidate("https://nitro5.tailf52603.ts.net/vault/Daily/2026-11-01.md"))
        assertEquals("health", VaultLinks.candidate("https://nitro5.tailf52603.ts.net/health"))
        assertNull(VaultLinks.resolve("https://nitro5.tailf52603.ts.net/health", paths))
    }

    @Test fun linkBasesOfAppendsVaultPrefixToProxyRoots() {
        val bases = VaultLinks.linkBasesOf(
            listOf("https://towerx.tailf52603.ts.net", "https://nitro5.tailf52603.ts.net/v1"),
            extra = listOf("https://custom.example/files"),
        )
        assertEquals(
            listOf(
                "https://towerx.tailf52603.ts.net/vault/",
                "https://nitro5.tailf52603.ts.net/vault/",
                "https://custom.example/files/",
            ),
            bases,
        )
    }
}
