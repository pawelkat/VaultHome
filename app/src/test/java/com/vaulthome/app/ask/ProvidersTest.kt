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

    private fun avail(
        wanted: Set<String> = setOf("vault"),
        settings: AskSettings = AskSettings(),
        alive: (String) -> Boolean? = { null },
        sample: (String) -> HealthSample? = { null },
        now: Long = 0,
    ): (Provider) -> Availability {
        val chain = ProviderAvailability.chain(settings, alive, sample) { now }
        return { ProviderAvailability.of(it, wanted, chain) }
    }

    @Test fun autoPickIsTheAvailableNitroVaultProvider() {
        val r = ProviderRouting.route(listOf(chat("towerx", "towerx-qwen"), chat("nitro5", "nitro5-qwen")), null, availability = avail())
        assertEquals(listOf("nitro5-qwen", "towerx-qwen"), r.order.map { it.id })
    }

    @Test fun manifestNotReadyMeansOfflineAndOthersAreOffered() {
        val r = ProviderRouting.route(listOf(chat("towerx", "towerx-qwen"), chat("nitro5", "nitro5-qwen", ready = false)), null, availability = avail())
        assertEquals(listOf("towerx-qwen"), r.order.map { it.id })
        assertEquals("offline", r.skipped.single().reason)
    }

    @Test fun withoutNitroOrderIsStableByIdNotByHost() {
        val r = ProviderRouting.route(listOf(chat("z", "z-chat"), chat("a", "a-chat"), chat("m", "m-chat")), null, availability = avail())
        assertEquals(listOf("a-chat", "m-chat", "z-chat"), r.order.map { it.id })
    }

    @Test fun pinWinsOverAutoPickButAnOfflinePinIsSkipped() {
        val all = listOf(chat("towerx", "towerx-qwen"), chat("nitro5", "nitro5-qwen"))
        assertEquals("towerx-qwen", ProviderRouting.route(all, "towerx-qwen", availability = avail()).order.first().id)
        assertEquals("nitro5-qwen", ProviderRouting.route(all, "gone", availability = avail()).order.first().id)
        val down = ProviderRouting.route(all, "towerx-qwen", availability = avail(alive = { if (it == "towerx") false else null }))
        assertEquals(listOf("nitro5-qwen"), down.order.map { it.id })
        assertEquals("offline", down.skipped.single().reason)
    }

    // --- availability mapping -------------------------------------------------------------------------------------

    private fun legacy(device: String) = ProviderRouting.fromSettings(AskSettings()).first { it.device == device }

    @Test fun noSignalMeansUnknownAndStillRoutable() {
        val a = avail()(legacy("towerx"))
        assertEquals(Availability.Unknown, a)
        assertTrue(a.routable)
    }

    @Test fun freshHealthMapsToAvailableOrOffline() {
        val ok = avail(sample = { HealthSample(true, "up", 1_000) }, now = 2_000)
        val bad = avail(sample = { HealthSample(false, "Nitro refused the connection", 1_000) }, now = 2_000)
        assertEquals(Availability.Available, ok(legacy("nitro5")))
        assertEquals(Availability.Offline, bad(legacy("towerx")))
        assertFalse(Availability.Offline.routable)
    }

    @Test fun staleHealthIsIgnored() {
        val old = avail(sample = { HealthSample(false, "x", 0) }, now = ProviderAvailability.HEALTH_TTL_MS + 1)
        assertEquals(Availability.Unknown, old(legacy("towerx")))
    }

    @Test fun presenceGoneMeansOfflineButAliveSaysNothing() {
        assertEquals(Availability.Offline, avail(alive = { false })(legacy("towerx")))
        assertEquals(Availability.Unknown, avail(alive = { true })(legacy("towerx")))
    }

    @Test fun settingsSwitchMeansDisabledAndBeatsEverything() {
        val a = avail(settings = AskSettings(excludedProviderIds = setOf("towerx-qwen")), sample = { HealthSample(true, "up", 0) })
        assertEquals(Availability.Disabled, a(legacy("towerx")))
        assertEquals(Availability.Available, a(legacy("nitro5")))
    }

    @Test fun exclusionIsKeyedByProviderIdNotDevice() {
        val other = chat("nitro5", "nitro5-code", skills = listOf("vault", "code"))
        val a = avail(settings = AskSettings(excludedProviderIds = setOf("nitro5-qwen")), sample = { HealthSample(true, "up", 0) })
        assertEquals(Availability.Disabled, a(legacy("nitro5")))
        assertEquals(Availability.Available, a(other))
    }

    @Test fun excludedProviderIsNotRoutedAndCannotStayPinned() {
        val all = listOf(chat("nitro5", "nitro5-qwen"), chat("towerx", "towerx-qwen"))
        val settings = AskSettings(pinnedProviderId = "towerx-qwen").exclude("towerx-qwen", true)
        assertEquals(null, settings.pinnedProviderId)
        val r = ProviderRouting.route(all, "towerx-qwen", availability = avail(settings = settings))
        assertEquals(listOf("nitro5-qwen"), r.order.map { it.id })
        assertEquals("disabled", r.skipped.single().reason)
        assertFalse(Availability.Disabled.routable)
    }

    @Test fun manifestOverridesProbes() {
        val ready = chat("towerx", "towerx-qwen")
        val notReady = chat("towerx", "towerx-qwen", ready = false)
        val bad = { _: String -> HealthSample(false, "x", 0) }
        assertEquals(Availability.Available, avail(sample = bad)(ready))
        assertEquals(Availability.Offline, avail(sample = { HealthSample(true, "up", 0) })(notReady))
    }

    @Test fun missingSkillMeansNoSkillOnlyForOtherwiseAvailableChatProviders() {
        val both = setOf("vault", "code")
        assertEquals(Availability.NoSkill, avail(wanted = both)(chat("nitro5", "n")))
        assertEquals(Availability.Available, avail(wanted = both)(chat("towerx", "t", skills = listOf("vault", "code"))))
        assertEquals(Availability.Offline, avail(wanted = both, alive = { false })(chat("nitro5", "n")))
    }

    @Test fun localProviderIsAlwaysAvailable() {
        val gemma = ProviderRouting.all(emptyMap(), AskSettings()).first { it.id == "s24-gemma" }
        assertEquals(Availability.Available, avail(alive = { false })(gemma))
    }

    @Test fun unavailableProvidersAreSkippedWithAReason() {
        val all = listOf(chat("nitro5", "n"), chat("towerx", "t"), chat("lap", "l", skills = listOf("code")))
        val r = ProviderRouting.route(
            all, "t",
            availability = avail(settings = AskSettings(excludedProviderIds = setOf("n")), alive = { if (it == "towerx") false else null }),
        )
        assertEquals(emptyList<String>(), r.order.filter { it.id != "l" }.map { it.id })
        assertEquals(mapOf("n" to "disabled", "t" to "offline", "l" to "no vault skill"), r.skipped.associate { it.provider.id to it.reason })
    }

    @Test fun embeddingIndexIsNeverAChatProvider() {
        val all = ProviderRouting.all(emptyMap(), AskSettings())
        assertEquals(listOf("nitro5-qwen", "towerx-qwen", "s24-gemma"), all.map { it.id })
        assertEquals(listOf("nitro5-qwen", "towerx-qwen"), ProviderRouting.route(all, null).order.map { it.id })
    }

    @Test fun manifestReplacesSettingsProviderForItsDeviceOnly() {
        val m = mapOf("towerx" to ProviderManifest.parse(caps).getOrThrow())
        val all = ProviderRouting.all(m, AskSettings())
        assertEquals(setOf("towerx-qwen", "towerx-gemma", "nitro5-qwen", "s24-gemma"), all.map { it.id }.toSet())
        assertFalse(all.first { it.id == "towerx-qwen" }.fromSettings)
        assertTrue(all.first { it.id == "nitro5-qwen" }.fromSettings)
    }

    @Test fun skillFilterAndRequestOptions() {
        val both = setOf("vault", "code")
        val r = ProviderRouting.route(listOf(chat("nitro5", "n"), chat("towerx", "t", skills = listOf("vault", "code"))), null, both, avail(wanted = both))
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

class ProviderProbeTest {
    @Test fun probeReportsOkAndDownPerDevice() = kotlinx.coroutines.runBlocking {
        val gw = object : NitroGateway {
            override suspend fun health(baseUrl: String) = if ("towerx" in baseUrl) Health.Down("Nitro refused the connection") else Health.Ok
            override suspend fun chat(baseUrl: String, messages: List<ChatMessage>, options: ChatOptions) = error("not used")
        }
        val seen = mutableMapOf<String, Pair<Boolean, String>>()
        val providers = ProviderRouting.all(emptyMap(), AskSettings(towerxBaseUrl = "https://towerx.example"))
        ProviderProbe.probe(gw, providers) { d, ok, detail -> seen[d] = ok to detail }
        assertEquals(mapOf("nitro5" to (true to "up"), "towerx" to (false to "Nitro refused the connection")), seen)
    }
}
