package com.vaulthome.app.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultLinksTest {
    private val hosts = VaultLinks.hostsOf("https://nitro5.tailf52603.ts.net", "http://192.168.1.18:8082")
    private fun t(s: String) = VaultLinks.target(s, hosts)

    @Test fun nitroVaultUrlsBecomeVaultPaths() {
        assertEquals("Daily/2026-11-01.md", t("https://nitro5.tailf52603.ts.net/vault/Daily/2026-11-01.md"))
        assertEquals("Daily/2026-11-01", t("https://nitro5.tailf52603.ts.net/vault/Daily/2026-11-01#Morning"))
        assertEquals("Notes/VaultHome Watch.md", t("https://nitro5.tailf52603.ts.net/vault/Notes/VaultHome%20Watch.md?x=1"))
        assertEquals("Notes/A.md", t("http://192.168.1.18:8082/vault/Notes/A.md"))
        assertEquals("Notes/Zażółć 🚀.md", t("https://NITRO5.tailf52603.ts.net/vault/Notes/Za%C5%BC%C3%B3%C5%82%C4%87%20%F0%9F%9A%80.md"))
    }

    @Test fun relativeAndBarePaths() {
        assertEquals("Daily/2026-11-01.md", t("/vault/Daily/2026-11-01.md"))
        assertEquals("Daily/2026-11-01.md", t("vault/Daily/2026-11-01.md"))
        assertEquals("Projects/VaultHome.md", t("Projects/VaultHome.md#Ask"))
        assertEquals("Notes/VaultHome Watch", t("obsidian://open?vault=Vault&file=Notes/VaultHome%20Watch"))
    }

    @Test fun webLinksAreNotVaultLinks() {
        assertNull(t("https://example.com/vault/Daily/2026-11-01.md"))
        assertNull(t("https://nitro5.tailf52603.ts.net/health"))
        assertNull(t("https://youtube.com/watch?v=x"))
        assertNull(t("mailto:a@b.c"))
        assertNull(t("#heading"))
        assertNull(t("Projects/VaultHome"))
    }

    @Test fun resolvesAgainstLocalIndex() {
        val paths = listOf("Daily/2026-11-01.md", "Notes/PureGym.md")
        assertEquals("Daily/2026-11-01.md", WikiLinks.resolve(t("https://nitro5.tailf52603.ts.net/vault/Daily/2026-11-01")!!, paths))
        assertEquals("Notes/PureGym.md", WikiLinks.resolve(t("/vault/Notes/PureGym.md")!!, paths))
    }
}
