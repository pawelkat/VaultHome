package com.vaulthome.app.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    private val caps = """
        {"@context":"https://pawelkat.github.io/vault-ontology/context/caps-v2.jsonld","schemaVersion":2,"@graph":[
          {"@id":"https://pawelkat.github.io/vault-ontology/id/towerx-qwen","@type":"LlmChatProvider",
           "label":"Qwen on TowerX","comment":"d","providesSkill":["VaultSkill","CodeSkill"],
           "hostedOn":{"@id":"https://pawelkat.github.io/vault-ontology/id/towerx","@type":"Device","deviceId":"towerx"},
           "endpointUrl":"https://towerx.tailf52603.ts.net/v1/chat/completions",
           "transport":"ChatProxy","scope":"Tailnet","accessMode":"ReadOnly","ready":true,"nCtx":16384,"maxTokensSuggest":2048,
           "codeRoot":"/home/pawel/projects"},
          {"@id":"https://pawelkat.github.io/vault-ontology/id/towerx-gemma","@type":"EmbeddingIndexProvider",
           "label":"Gemma","comment":"","providesSkill":["SearchSkill"],
           "hostedOn":{"@id":"https://pawelkat.github.io/vault-ontology/id/towerx","@type":"Device","deviceId":"towerx"},
           "transport":"LocalIndex","scope":"Local","accessMode":"ReadOnly","ready":true}
        ]}
    """.trimIndent()

    private val v1Caps = """
        {"schemaVersion":1,"device":"towerx","providers":[
          {"id":"towerx-qwen","kind":"llm-chat","title":"Qwen on TowerX","skills":["vault"],
           "endpoint":"https://towerx.tailf52603.ts.net/v1/chat/completions","transport":"chat-proxy"}
        ]}
    """.trimIndent()

    private fun chat(device: String, id: String, skills: List<String> = listOf(Skills.VAULT), ready: Boolean = true) = Provider(
        id, device, Provider.KIND_CHAT, id, "", skills, "https://$device/v1/chat/completions", Provider.TRANSPORT_PROXY, Provider.SCOPE_TAILNET, Provider.ACCESS_READ_ONLY, ready, null, null,
    )

    @Test fun parsesSchemaV2() {
        val list = ProviderManifest.parse(caps).getOrThrow()
        assertEquals(listOf("towerx-qwen", "towerx-gemma"), list.map { it.id })
        val q = list[0]
        assertTrue(q.isChat)
        assertEquals(listOf("VaultSkill", "CodeSkill"), q.skills)
        assertEquals(16384, q.nCtx)
        assertEquals(2048, q.maxTokensSuggest)
        assertEquals("https://towerx.tailf52603.ts.net", q.baseUrl)
        assertEquals("TowerX", q.hostLabel)
        assertFalse(list[1].isChat)
        assertNull(list[1].nCtx)
    }

    @Test fun unsupportedOrBrokenManifestIsAnError() {
        assertTrue(ProviderManifest.parse(v1Caps).isFailure)
        assertTrue(ProviderManifest.parse("not json").isFailure)
        assertTrue(ProviderManifest.parse("""{"schemaVersion":1,"providers":[]}""").isFailure)
        assertTrue(ProviderManifest.parse("""{"schemaVersion":3,"@graph":[]}""").isFailure)
    }

    @Test fun readyDefaultsToFalseAndBadEntriesAreDropped() {
        val list = ProviderManifest.parse(
            """{"schemaVersion":2,"@graph":[{"@id":"a","@type":"LlmChatProvider","hostedOn":{"deviceId":"d"}},{"@type":"LlmChatProvider","hostedOn":{"deviceId":"d"}},{"@id":"b"}]}""",
        ).getOrThrow()
        assertEquals(listOf("a"), list.map { it.id })
        assertFalse(list[0].ready)
    }

    @Test fun optionalLinkBaseIsReadAndUnknownKeysAreIgnored() {
        val list = ProviderManifest.parse(
            """{"schemaVersion":2,"@graph":[
                {"@id":"t","@type":"LlmChatProvider","hostedOn":{"deviceId":"towerx"},
                 "endpointUrl":"https://towerx.tailf52603.ts.net/v1/chat/completions",
                 "transport":"ChatProxy","linkBase":"https://towerx.tailf52603.ts.net/vault/","extra":1}
            ]}"""
        ).getOrThrow()
        assertEquals("https://towerx.tailf52603.ts.net/vault/", list.single().linkBase)
        assertNull(ProviderManifest.parse(
            """{"schemaVersion":2,"@graph":[{"@id":"a","@type":"LlmChatProvider","hostedOn":{"deviceId":"d"}}]}"""
        ).getOrThrow().single().linkBase)
    }

    @Test fun capsKey() = assertEquals("vault/caps/nitro5", ProviderManifest.key("nitro5"))

    private fun avail(
        wanted: Set<String> = setOf(Skills.VAULT),
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

    private fun legacy(device: String) = ProviderRouting.fromRegistry(com.vaulthome.app.devices.DeviceRegistryJson.bundled().devices).first { it.device == device }

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
        val other = chat("nitro5", "nitro5-code", skills = listOf(Skills.VAULT, Skills.CODE))
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
        val both = setOf(Skills.VAULT, Skills.CODE)
        assertEquals(Availability.NoSkill, avail(wanted = both)(chat("nitro5", "n")))
        assertEquals(Availability.Available, avail(wanted = both)(chat("towerx", "t", skills = listOf(Skills.VAULT, Skills.CODE))))
        assertEquals(Availability.Offline, avail(wanted = both, alive = { false })(chat("nitro5", "n")))
    }

    @Test fun localProviderIsAlwaysAvailable() {
        val gemma = ProviderRouting.all(emptyMap(), AskSettings()).first { it.id == "s24-gemma" }
        assertEquals(Availability.Available, avail(alive = { false })(gemma))
    }

    @Test fun unavailableProvidersAreSkippedWithAReason() {
        val all = listOf(chat("nitro5", "n"), chat("towerx", "t"), chat("lap", "l", skills = listOf(Skills.CODE)))
        val r = ProviderRouting.route(
            all, "t",
            availability = avail(settings = AskSettings(excludedProviderIds = setOf("n")), alive = { if (it == "towerx") false else null }),
        )
        assertEquals(emptyList<String>(), r.order.filter { it.id != "l" }.map { it.id })
        assertEquals(mapOf("n" to "disabled", "t" to "offline", "l" to "no VaultSkill skill"), r.skipped.associate { it.provider.id to it.reason })
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
        val both = setOf(Skills.VAULT, Skills.CODE)
        val r = ProviderRouting.route(listOf(chat("nitro5", "n"), chat("towerx", "t", skills = listOf(Skills.VAULT, Skills.CODE))), null, both, avail(wanted = both))
        assertEquals(listOf("t"), r.order.map { it.id })
        assertEquals("no CodeSkill skill", r.skipped.single().reason)
        val p = ProviderManifest.parse(caps).getOrThrow()[0]
        assertEquals(ChatOptions(2048, null) to null, ProviderRouting.options(p, setOf(Skills.VAULT)))
        assertEquals(ChatOptions(2048, listOf(Skills.CODE, Skills.VAULT)), ProviderRouting.options(p, both).first)
        assertEquals(ChatOptions(2048, listOf(Skills.CODE)), ProviderRouting.options(p, setOf(Skills.CODE)).first)
    }

    @Test fun tokenCapFallsBackTo1024OnlyForNitroWhenNCtxIsUnknown() {
        val nitro = chat("nitro5", "nitro5-qwen") // fromSettings-like: nCtx and suggest both missing
        val tower = chat("towerx", "towerx-qwen")
        val other = chat("box", "box-qwen")
        assertEquals(ChatOptions(1024, null) to "nCtx unknown: using 1024 tokens", ProviderRouting.options(nitro, setOf(Skills.VAULT)))
        assertEquals(ChatOptions(null, null) to null, ProviderRouting.options(tower, setOf(Skills.VAULT)))
        assertEquals(ChatOptions(null, null) to null, ProviderRouting.options(other, setOf(Skills.VAULT)))
        // maxTokensSuggest still wins when present, even if nCtx is missing; no fallback note.
        val suggested = chat("towerx", "t").copy(maxTokensSuggest = 2048)
        assertEquals(ChatOptions(2048, null) to null, ProviderRouting.options(suggested, setOf(Skills.VAULT)))
        val nitroSuggested = chat("nitro5", "n").copy(maxTokensSuggest = 1536)
        assertEquals(ChatOptions(1536, null) to null, ProviderRouting.options(nitroSuggested, setOf(Skills.VAULT)))
        // nCtx known, no suggest: omit max_tokens (server decides); no note.
        val known = chat("nitro5", "n").copy(nCtx = 16384)
        assertEquals(ChatOptions(null, null) to null, ProviderRouting.options(known, setOf(Skills.VAULT)))
    }

    @Test fun monitorTracksPresenceFetchesCapsAndKeepsStaleOnLeave() {
        val asked = mutableListOf<String>()
        ProviderMonitor.resetForTests()
        ProviderMonitor.selfDevice = "s24"
        ProviderMonitor.caller = { sel, _, _ -> asked += sel; caps }
        ProviderMonitor.onToken("vault/presence/towerx", true)
        assertEquals(true, ProviderMonitor.alive("towerx"))
        // The query runs on a worker thread: refresh directly for a deterministic check.
        ProviderMonitor.refresh("towerx")
        val live = ProviderMonitor.state.value.manifests["towerx"]!!
        assertEquals(listOf("towerx-qwen", "towerx-gemma"), live.map { it.id })
        assertEquals(CapsSource.Zenoh, live[0].capsSource)
        assertFalse(live[0].capsStale)
        assertTrue(asked.all { it == "vault/caps/towerx" })
        ProviderMonitor.onToken("vault/presence/towerx", false)
        assertEquals(false, ProviderMonitor.alive("towerx"))
        val stale = ProviderMonitor.state.value.manifests["towerx"]!!
        assertEquals(listOf("towerx-qwen", "towerx-gemma"), stale.map { it.id })
        assertTrue(stale.all { it.capsStale })
        ProviderMonitor.onToken("vault/presence/s24", true)
        ProviderMonitor.onSessionClosed()
        assertNull(ProviderMonitor.alive("s24"))
        assertTrue(ProviderMonitor.state.value.manifests["towerx"]!!.all { it.capsStale })
        ProviderMonitor.resetForTests()
    }

    @Test fun monitorKeepsErrorForBadCapsAndIgnoresForeignKeys() {
        ProviderMonitor.resetForTests()
        ProviderMonitor.caller = { _, _, _ -> "nope" }
        ProviderMonitor.onToken("devices/towerx-power", true)
        assertTrue(ProviderMonitor.state.value.alive.isEmpty())
        ProviderMonitor.onToken("vault/presence/nitro5", true)
        ProviderMonitor.refresh("nitro5")
        assertTrue(ProviderMonitor.state.value.errors.containsKey("nitro5"))
        assertNull(ProviderMonitor.state.value.manifests["nitro5"])
        ProviderMonitor.onToken("vault/presence/*", false)
        assertTrue(ProviderMonitor.state.value.alive.isEmpty())
        ProviderMonitor.resetForTests()
    }

    @Test fun monitorFallsBackToCachedCapsWhenZenohMisses() {
        ProviderMonitor.resetForTests()
        ProviderMonitor.caller = { _, _, _ -> caps }
        ProviderMonitor.onToken("vault/presence/towerx", true)
        ProviderMonitor.refresh("towerx", now = 1_000)
        assertFalse(ProviderMonitor.state.value.manifests["towerx"]!!.first().capsStale)
        ProviderMonitor.caller = { _, _, _ -> null }
        ProviderMonitor.refresh("towerx", now = 2_000)
        val list = ProviderMonitor.state.value.manifests["towerx"]!!
        assertEquals(listOf("towerx-qwen", "towerx-gemma"), list.map { it.id })
        assertEquals(CapsSource.Zenoh, list[0].capsSource)
        assertTrue(list[0].capsStale)
        assertEquals(1_000L, list[0].capsAtMs)
        assertTrue(ProviderMonitor.state.value.errors["towerx"]!!.contains("last-known"))
        ProviderMonitor.resetForTests()
    }

    @Test fun monitorSynthesizesCapsFromHealthAndPropsWhenNoAdvertisedDocument() {
        ProviderMonitor.resetForTests()
        ProviderMonitor.caller = { _, _, _ -> null }
        ProviderMonitor.bases = { listOf("https://towerx.tailf52603.ts.net") }
        ProviderMonitor.http = CapsHttp { url, _ ->
            when {
                url.endsWith("/caps") || url.endsWith("/caps.json") -> 404 to "File Not Found"
                url.endsWith("/health") -> 200 to """{"status":"ok","llama":"ok","vault_searchd":"ok"}"""
                url.endsWith("/props") -> 200 to """{"default_generation_settings":{"n_ctx":16384,"params":{}}}"""
                else -> null
            }
        }
        ProviderMonitor.onToken("vault/presence/towerx", true)
        ProviderMonitor.refresh("towerx", now = 5_000)
        val p = ProviderMonitor.state.value.manifests["towerx"]!!.single()
        assertEquals("towerx-qwen", p.id)
        assertEquals(16384, p.nCtx)
        assertEquals(listOf(Skills.VAULT), p.skills)
        assertEquals(CapsSource.HealthProps, p.capsSource)
        assertFalse(p.capsStale)
        assertFalse(p.fromSettings)
        assertEquals("https://towerx.tailf52603.ts.net", p.baseUrl)
        ProviderMonitor.resetForTests()
    }

    @Test fun monitorPrefersHttpCapsOverHealthProps() {
        ProviderMonitor.resetForTests()
        ProviderMonitor.caller = { _, _, _ -> null }
        ProviderMonitor.bases = { listOf("https://towerx.tailf52603.ts.net") }
        ProviderMonitor.http = CapsHttp { url, _ ->
            when {
                url.endsWith("/caps") -> 200 to caps
                url.endsWith("/health") -> 200 to """{"status":"ok"}"""
                else -> null
            }
        }
        ProviderMonitor.onToken("vault/presence/towerx", true)
        ProviderMonitor.refresh("towerx", now = 9_000)
        val list = ProviderMonitor.state.value.manifests["towerx"]!!
        assertEquals(listOf("towerx-qwen", "towerx-gemma"), list.map { it.id })
        assertEquals(CapsSource.HttpCaps, list[0].capsSource)
        assertEquals(16384, list[0].nCtx)
        ProviderMonitor.resetForTests()
    }

    @Test fun capsAgeLineAndPropsNctx() {
        assertEquals("just now", CapsAge.label(1_000, 3_000))
        assertEquals("12s ago", CapsAge.label(0, 12_000))
        assertEquals("2h ago", CapsAge.label(0, 2 * 3_600_000L))
        assertEquals("caps Zenoh · 12s ago", CapsAge.line(CapsSource.Zenoh, 0, false, 12_000))
        assertEquals("caps Zenoh (stale, 2h ago)", CapsAge.line(CapsSource.Zenoh, 0, true, 2 * 3_600_000L))
        assertEquals("caps devices.json", CapsAge.line(CapsSource.Registry, null, false, 0))
        assertEquals(16384, CapsFromProbe.nCtxFromProps("""{"default_generation_settings":{"n_ctx":16384}}"""))
        assertNull(CapsFromProbe.nCtxFromProps("""{"default_generation_settings":{}}"""))
        assertTrue(CapsFromProbe.healthOk(200, """{"status":"ok","llama":"ok"}"""))
        assertFalse(CapsFromProbe.healthOk(404, "File Not Found"))
    }

    @Test fun chatJsonCarriesMaxTokensAndOptionalSkills() {
        val plain = org.json.JSONObject(ChatJson.request(listOf(ChatMessage("user", "q"))))
        assertFalse("max_tokens is omitted when unset, so the server decides", plain.has("max_tokens"))
        assertFalse(plain.has("skills"))
        val capped = org.json.JSONObject(ChatJson.request(listOf(ChatMessage("user", "q")), ChatOptions(1024)))
        assertEquals(1024, capped.getInt("max_tokens"))
        val sized = org.json.JSONObject(ChatJson.request(listOf(ChatMessage("user", "q")), ChatOptions(2048, listOf(Skills.CODE, Skills.VAULT))))
        assertEquals(2048, sized.getInt("max_tokens"))
        assertEquals(Skills.CODE, sized.getJSONArray("skills").getString(0))
        assertEquals(Skills.VAULT, sized.getJSONArray("skills").getString(1))
    }

    @Test fun skillsSettingParsing() {
        assertEquals(setOf(Skills.VAULT), AskSettings.parseSkills(null))
        assertEquals(setOf(Skills.VAULT), AskSettings.parseSkills("search,bogus"))
        assertEquals(setOf(Skills.CODE, Skills.VAULT), AskSettings.parseSkills("code,vault"))
        assertEquals(setOf(Skills.CODE, Skills.VAULT), AskSettings.parseSkills("CodeSkill,VaultSkill"))
        assertEquals(setOf(Skills.CODE, Skills.VAULT), AskSettings.parseSkills("code,VaultSkill"))
    }
}

class ProviderProbeTest {
    @Test fun probeReportsOkAndDownPerDevice() = kotlinx.coroutines.runBlocking {
        val gw = object : NitroGateway {
            override suspend fun health(baseUrl: String) = if ("towerx" in baseUrl) Health.Down("Nitro refused the connection") else Health.Ok
            override suspend fun chat(baseUrl: String, messages: List<ChatMessage>, options: ChatOptions) = error("not used")
        }
        val seen = mutableMapOf<String, Pair<Boolean, String>>()
        val providers = ProviderRouting.all(emptyMap(), AskSettings())
        ProviderProbe.probe(gw, providers) { d, ok, detail -> seen[d] = ok to detail }
        assertEquals(mapOf("nitro5" to (true to "up"), "towerx" to (false to "Nitro refused the connection")), seen)
    }
}
