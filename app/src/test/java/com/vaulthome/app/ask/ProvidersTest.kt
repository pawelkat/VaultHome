package com.vaulthome.app.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    private val caps = """
        {"schemaVersion":1,"device":"towerx","providers":[
          {"id":"towerx-qwen","kind":"llm-chat","title":"Qwen on TowerX","description":"d","skills":["vault","code"],
           "tools":{"vault":["vault_search"]},"endpoint":"https://towerx.tailf52603.ts.net/v1/chat/completions",
           "transport":"chat-proxy","scope":"tailnet","access":"read-only","ready":true,"n_ctx":16384,"max_tokens_suggest":2048,"root":null},
          {"id":"towerx-gemma","kind":"embedding-index","title":"Gemma","skills":["search"],"endpoint":"local",
           "transport":"local-index","scope":"local","access":"read-only","ready":true,"n_ctx":null,"max_tokens_suggest":null}
        ]}
    """.trimIndent()

    private fun chat(device: String, id: String, skills: List<String> = listOf("vault"), ready: Boolean = true) = Provider(
        id, device, "llm-chat", id, "", skills, "https://$device/v1/chat/completions", "chat-proxy", "tailnet", "read-only", ready, null, null,
    )

    @Test fun parsesSchemaV1() {
        val list = ProviderManifest.parse(caps).getOrThrow()
        assertEquals(listOf("towerx-qwen", "towerx-gemma"), list.map { it.id })
        val q = list[0]
        assertTrue(q.isChat)
        assertEquals(listOf("vault", "code"), q.skills)
        assertEquals(16384, q.nCtx)
        assertEquals(2048, q.maxTokensSuggest)
        assertEquals("https://towerx.tailf52603.ts.net", q.baseUrl)
        assertEquals("TowerX", q.hostLabel)
        assertFalse(list[1].isChat)
        assertNull(list[1].nCtx)
    }

    @Test fun unsupportedOrBrokenManifestIsAnError() {
        assertTrue(ProviderManifest.parse("""{"schemaVersion":2,"device":"x","providers":[]}""").isFailure)
        assertTrue(ProviderManifest.parse("not json").isFailure)
        assertTrue(ProviderManifest.parse("""{"schemaVersion":1,"providers":[]}""").isFailure)
    }

    @Test fun readyDefaultsToFalseAndBadEntriesAreDropped() {
        val list = ProviderManifest.parse(
            """{"schemaVersion":1,"device":"d","providers":[{"id":"a","kind":"llm-chat"},{"kind":"llm-chat"},{"id":"b"}]}""",
        ).getOrThrow()
        assertEquals(listOf("a"), list.map { it.id })
        assertFalse(list[0].ready)
    }

    @Test fun capsKey() = assertEquals("vault/caps/nitro5", ProviderManifest.key("nitro5"))

    @Test fun autoPickIsTheReadyNitroVaultProvider() {
        val r = ProviderRouting.route(listOf(chat("towerx", "towerx-qwen"), chat("nitro5", "nitro5-qwen")), null)
        assertEquals(listOf("nitro5-qwen", "towerx-qwen"), r.order.map { it.id })
    }

    @Test fun notReadyNitroIsLeftOutAndOthersAreOffered() {
        val r = ProviderRouting.route(listOf(chat("towerx", "towerx-qwen"), chat("nitro5", "nitro5-qwen", ready = false)), null)
        assertEquals(listOf("towerx-qwen"), r.order.map { it.id })
        assertEquals("not ready", r.skipped.single().reason)
    }

    @Test fun withoutNitroOrderIsStableByIdNotByHost() {
        val r = ProviderRouting.route(listOf(chat("z", "z-chat"), chat("a", "a-chat"), chat("m", "m-chat")), null)
        assertEquals(listOf("a-chat", "m-chat", "z-chat"), r.order.map { it.id })
    }

    @Test fun pinWinsOverAutoPickButAnUnusablePinIsIgnored() {
        val all = listOf(chat("towerx", "towerx-qwen"), chat("nitro5", "nitro5-qwen"))
        assertEquals("towerx-qwen", ProviderRouting.route(all, "towerx-qwen").order.first().id)
        assertEquals("nitro5-qwen", ProviderRouting.route(all, "gone").order.first().id)
        val down = ProviderRouting.route(all, "towerx-qwen") { if (it == "towerx") false else null }
        assertEquals(listOf("nitro5-qwen"), down.order.map { it.id })
    }

    @Test fun embeddingIndexIsNeverAChatProvider() {
        val all = ProviderRouting.all(emptyMap(), AskSettings(nitroEnabled = false, towerxEnabled = false))
        assertEquals(listOf("s24-gemma"), all.map { it.id })
        assertTrue(ProviderRouting.route(all, null).order.isEmpty())
    }

    @Test fun manifestReplacesSettingsProviderForItsDeviceOnly() {
        val m = mapOf("towerx" to ProviderManifest.parse(caps).getOrThrow())
        val all = ProviderRouting.all(m, AskSettings())
        assertEquals(setOf("towerx-qwen", "towerx-gemma", "nitro5-qwen", "s24-gemma"), all.map { it.id }.toSet())
        assertFalse(all.first { it.id == "towerx-qwen" }.fromSettings)
        assertTrue(all.first { it.id == "nitro5-qwen" }.fromSettings)
    }

    @Test fun settingsSwitchTurnsAHostOff() {
        val m = mapOf("towerx" to ProviderManifest.parse(caps).getOrThrow())
        val all = ProviderRouting.all(m, AskSettings(towerxEnabled = false))
        assertTrue(all.none { it.device == "towerx" })
    }

    @Test fun skillFilterAndRequestOptions() {
        val both = setOf("vault", "code")
        val r = ProviderRouting.route(listOf(chat("nitro5", "n"), chat("towerx", "t", skills = listOf("vault", "code"))), null, both)
        assertEquals(listOf("t"), r.order.map { it.id })
        assertEquals("no code skill", r.skipped.single().reason)
        val p = ProviderManifest.parse(caps).getOrThrow()[0]
        assertEquals(ChatOptions(2048, null) to null, ProviderRouting.options(p, setOf("vault")))
        assertEquals(ChatOptions(2048, listOf("code", "vault")), ProviderRouting.options(p, both).first)
        assertEquals(ChatOptions(2048, listOf("code")), ProviderRouting.options(p, setOf("code")).first)
    }

    @Test fun monitorTracksPresenceFetchesCapsAndDropsOnLeave() {
        val asked = mutableListOf<String>()
        ProviderMonitor.onSessionClosed()
        ProviderMonitor.selfDevice = "s24"
        ProviderMonitor.caller = { sel, _, _ -> asked += sel; caps }
        ProviderMonitor.onToken("vault/presence/towerx", true)
        assertEquals(true, ProviderMonitor.alive("towerx"))
        // The query runs on a worker thread: refresh directly for a deterministic check.
        ProviderMonitor.refresh("towerx")
        assertEquals(listOf("towerx-qwen", "towerx-gemma"), ProviderMonitor.state.value.manifests["towerx"]?.map { it.id })
        assertTrue(asked.all { it == "vault/caps/towerx" })
        ProviderMonitor.onToken("vault/presence/towerx", false)
        assertEquals(false, ProviderMonitor.alive("towerx"))
        assertNull(ProviderMonitor.state.value.manifests["towerx"])
        ProviderMonitor.onToken("vault/presence/s24", true)
        ProviderMonitor.onSessionClosed()
        assertNull(ProviderMonitor.alive("s24"))
        ProviderMonitor.caller = null
    }

    @Test fun monitorKeepsErrorForBadCapsAndIgnoresForeignKeys() {
        ProviderMonitor.onSessionClosed()
        ProviderMonitor.caller = { _, _, _ -> "nope" }
        ProviderMonitor.onToken("devices/towerx-power", true)
        assertTrue(ProviderMonitor.state.value.alive.isEmpty())
        ProviderMonitor.onToken("vault/presence/nitro5", true)
        ProviderMonitor.refresh("nitro5")
        assertTrue(ProviderMonitor.state.value.errors.containsKey("nitro5"))
        ProviderMonitor.onToken("vault/presence/*", false)
        assertTrue(ProviderMonitor.state.value.alive.isEmpty())
        ProviderMonitor.caller = null
    }

    @Test fun chatJsonCarriesMaxTokensAndOptionalSkills() {
        val plain = org.json.JSONObject(ChatJson.request(listOf(ChatMessage("user", "q"))))
        assertEquals(1024, plain.getInt("max_tokens"))
        assertFalse(plain.has("skills"))
        val sized = org.json.JSONObject(ChatJson.request(listOf(ChatMessage("user", "q")), ChatOptions(2048, listOf("code", "vault"))))
        assertEquals(2048, sized.getInt("max_tokens"))
        assertEquals("code", sized.getJSONArray("skills").getString(0))
        assertEquals("vault", sized.getJSONArray("skills").getString(1))
    }

    @Test fun skillsSettingParsing() {
        assertEquals(setOf("vault"), AskSettings.parseSkills(null))
        assertEquals(setOf("vault"), AskSettings.parseSkills("search,bogus"))
        assertEquals(setOf("code", "vault"), AskSettings.parseSkills("code,vault"))
    }
}
