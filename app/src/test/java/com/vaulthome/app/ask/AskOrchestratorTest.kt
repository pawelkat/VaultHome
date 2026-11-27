package com.vaulthome.app.ask

import com.vaulthome.app.search.SearchHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

private val NITRO_URL = TailnetUrls.derived("nitro5", TailnetUrls.FALLBACK_SUFFIX)
private val TOWERX_URL = TailnetUrls.derived("towerx", TailnetUrls.FALLBACK_SUFFIX)

class AskOrchestratorTest {
    private val hits = listOf(
        SearchHit("Notes/VaultHome Watch.md", "Open loops", "install watch APK", 0.9f),
        SearchHit("Notes/VaultHome Watch.md", "Open loops", "duplicate chunk", 0.8f),
        SearchHit("Notes/VaultHome.md", "", "Home-screen companion", 0.7f),
    )

    private class FakeNitro(var health: Health = Health.Ok, var chatError: Exception? = null) : NitroGateway {
        var healthCalls = 0
        val chats = mutableListOf<List<ChatMessage>>()
        val options = mutableListOf<ChatOptions>()
        override suspend fun health(baseUrl: String): Health { healthCalls++; return health }
        override suspend fun chat(baseUrl: String, messages: List<ChatMessage>, options: ChatOptions): ChatReply {
            chats += messages
            this.options += options
            chatError?.let { throw it }
            return ChatReply("Install the watch APK.", "qwen")
        }
    }

    private class ReadyEngine : AnswerEngine {
        var seen: List<Citation> = emptyList()
        override suspend fun isReady() = true
        override suspend fun answer(question: String, citations: List<Citation>): String { seen = citations; return "local answer" }
        override val unavailableReason = ""
    }

    private fun orchestrator(
        nitro: NitroGateway,
        settings: AskSettings = AskSettings(excludedProviderIds = setOf(ProviderRouting.TOWERX_PROVIDER_ID)),
        search: suspend (String) -> List<SearchHit> = { hits },
        local: AnswerEngine = LocalAnswerEngineStub,
    ) = AskOrchestrator(nitro, { settings }, search, local)

    /** Health and chat per base URL: TowerX vs Nitro. */
    private class TwoHosts(val towerx: Health, val nitro: Health = Health.Ok) : NitroGateway {
        val asked = mutableListOf<String>()
        val options = mutableListOf<ChatOptions>()
        override suspend fun health(baseUrl: String) = if (baseUrl == TOWERX_URL) towerx else nitro
        override suspend fun chat(baseUrl: String, messages: List<ChatMessage>, options: ChatOptions): ChatReply {
            asked += baseUrl
            this.options += options
            return ChatReply(if (baseUrl == TOWERX_URL) "from towerx" else "from nitro", "qwen")
        }
    }

    private fun withTowerX(
        gw: NitroGateway,
        agent: Boolean?,
        health: MutableList<Boolean> = mutableListOf(),
        settings: AskSettings = AskSettings(),
        manifests: Map<String, List<Provider>> = emptyMap(),
    ) = AskOrchestrator(
        gw, { settings }, { hits }, manifests = { manifests },
        deviceAlive = { if (it == "towerx") agent else null }, onHealth = { d, ok, _ -> if (d == "towerx") health += ok },
    )

    @Test fun disabledAndStaleOfflineHostsAreNotAsked() = runBlocking {
        val gw = TwoHosts(Health.Ok)
        val fresh = HealthSample(false, "Nitro refused the connection", atMs = 1_000)
        val o = AskOrchestrator(gw, { AskSettings() }, { hits }, healthSample = { if (it == "nitro5") fresh else null }, now = { 2_000 })
        val out = o.ask("q") as AskOutcome.Nitro
        assertEquals("TowerX", out.host)
        assertEquals(listOf(TOWERX_URL), gw.asked)
        // An old offline sample says nothing any more: Nitro is tried again.
        val gw2 = TwoHosts(Health.Ok)
        val later = AskOrchestrator(gw2, { AskSettings() }, { hits }, healthSample = { fresh }, now = { 1_000 + ProviderAvailability.HEALTH_TTL_MS + 1 })
        assertEquals("Nitro", (later.ask("q") as AskOutcome.Nitro).host)
    }

    private fun chatProvider(device: String, id: String, skills: List<String> = listOf(Skills.VAULT), ready: Boolean = true, nCtx: Int? = 16384, max: Int? = 2048) = Provider(
        id = id, device = device, kind = Provider.KIND_CHAT, title = id, description = "", skills = skills,
        endpoint = "https://$device.example.ts.net/v1/chat/completions", transport = Provider.TRANSPORT_PROXY, scope = Provider.SCOPE_TAILNET,
        access = Provider.ACCESS_READ_ONLY, ready = ready, nCtx = nCtx, maxTokensSuggest = max,
    )

    /** No host is baked in first: with Nitro down, TowerX answers; with both up the auto-pick rule (Nitro) answers. */
    @Test fun towerxAnswersWhenNitroIsDown() = runBlocking {
        val gw = TwoHosts(Health.Ok, nitro = Health.Down("Nitro refused the connection"))
        val health = mutableListOf<Boolean>()
        val out = withTowerX(gw, agent = true, health).ask("q") as AskOutcome.Nitro
        assertEquals("TowerX", out.host)
        assertEquals("from towerx", out.answer)
        assertEquals(listOf(true), health)
    }

    @Test fun autoPickRuleAsksNitroFirstWhenBothAreUp() = runBlocking {
        val gw = TwoHosts(Health.Ok)
        val out = withTowerX(gw, agent = true).ask("q") as AskOutcome.Nitro
        assertEquals("Nitro", out.host)
        assertEquals(listOf(NITRO_URL), gw.asked)
    }

    @Test fun pinnedProviderOverridesAutoPick() = runBlocking {
        val gw = TwoHosts(Health.Ok)
        val out = withTowerX(gw, agent = true, settings = AskSettings(pinnedProviderId = "towerx-qwen")).ask("q") as AskOutcome.Nitro
        assertEquals("TowerX", out.host)
        assertEquals(listOf(TOWERX_URL), gw.asked)
    }

    @Test fun pinnedProviderFallsThroughWhenItFails() = runBlocking {
        val gw = TwoHosts(Health.Down("Nitro refused the connection"))
        val out = withTowerX(gw, agent = true, settings = AskSettings(pinnedProviderId = "towerx-qwen")).ask("q") as AskOutcome.Nitro
        assertEquals("Nitro", out.host)
        assertEquals(listOf(NITRO_URL), gw.asked)
    }

    @Test fun towerxUnhealthyAfterNitroDownGivesCombinedDetail() = runBlocking {
        val gw = TwoHosts(Health.Loading("vault_searchd down"), nitro = Health.Down("Nitro refused the connection"))
        val out = withTowerX(gw, agent = true).ask("q") as AskOutcome.SearchOnly
        assertEquals("Nitro: Nitro refused the connection; vault_searchd down", out.nitroDetail)
        assertEquals(NitroSkip.Loading, out.nitro)
    }

    @Test fun towerxOfflineInZenohIsNotTried() = runBlocking {
        val gw = TwoHosts(Health.Ok, nitro = Health.Down("Nitro refused the connection"))
        val out = withTowerX(gw, agent = false).ask("q") as AskOutcome.SearchOnly
        assertEquals("TowerX: offline; Nitro refused the connection".split("; ").sorted(), out.nitroDetail.split("; ").sorted())
        assertEquals(emptyList<String>(), gw.asked)
    }

    @Test fun noZenohSessionHealthDecides() = runBlocking {
        val out = withTowerX(TwoHosts(Health.Ok, nitro = Health.Down("x")), agent = null).ask("q") as AskOutcome.Nitro
        assertEquals("TowerX", out.host)
    }

    @Test fun manifestNotReadyProviderIsSkippedAndSettingsFillOtherDevices() = runBlocking {
        val gw = TwoHosts(Health.Ok)
        val m = mapOf("nitro5" to listOf(chatProvider("nitro5", "nitro5-qwen", ready = false)))
        val out = withTowerX(gw, agent = true, manifests = m).ask("q") as AskOutcome.Nitro
        assertEquals("TowerX", out.host)
        assertEquals(listOf(TOWERX_URL), gw.asked)
    }

    @Test fun requestIsSizedFromTheManifest() = runBlocking {
        val gw = FakeNitro()
        val m = mapOf("nitro5" to listOf(chatProvider("nitro5", "nitro5-qwen", nCtx = 16384, max = 2048)))
        val out = AskOrchestrator(gw, { AskSettings(excludedProviderIds = setOf(ProviderRouting.TOWERX_PROVIDER_ID)) }, { hits }, manifests = { m }).ask("q") as AskOutcome.Nitro
        assertEquals(ChatOptions(2048, null), gw.options.single())
        assertEquals(null, out.sizingNote)
    }

    @Test fun missingNCtxKeepsThousandTokensAndSaysSoForNitroOnly() = runBlocking {
        val gw = FakeNitro()
        val out = orchestrator(gw).ask("q") as AskOutcome.Nitro
        assertEquals(ChatOptions(1024, null), gw.options.single())
        assertEquals("nCtx unknown: using 1024 tokens", out.sizingNote)
    }

    @Test fun missingNCtxOmitsMaxTokensForTowerXAndHasNoSizingNote() = runBlocking {
        val gw = TwoHosts(Health.Ok, nitro = Health.Down("x"))
        // Settings-synthesised TowerX provider has no nCtx / maxTokensSuggest.
        val out = withTowerX(gw, agent = true, settings = AskSettings(excludedProviderIds = setOf(ProviderRouting.NITRO_PROVIDER_ID))).ask("q") as AskOutcome.Nitro
        assertEquals("TowerX", out.host)
        assertEquals(ChatOptions(null, null), gw.options.single())
        assertEquals(null, out.sizingNote)
    }

    @Test fun codeSkillOnlyGoesToProvidersThatAdvertiseIt() = runBlocking {
        val gw = TwoHosts(Health.Ok)
        val m = mapOf("towerx" to listOf(chatProvider("towerx", "towerx-qwen", skills = listOf(Skills.VAULT, Skills.CODE))))
        val settings = AskSettings(skills = setOf(Skills.VAULT, Skills.CODE))
        val out = withTowerX(gw, agent = true, settings = settings, manifests = m).ask("q") as AskOutcome.Nitro
        assertEquals("TowerX", out.host)
        assertEquals(listOf(listOf(Skills.CODE, Skills.VAULT)), gw.options.map { it.skills })
    }

    @Test fun nitroUpAnswersWithoutLocalCitations() = runBlocking {
        val nitro = FakeNitro()
        val phases = mutableListOf<AskPhase>()
        val out = orchestrator(nitro).ask("  watch?  ") { phases += it }
        out as AskOutcome.Nitro
        assertEquals("Install the watch APK.", out.answer)
        // Nitro cites its own sources; the on-device hits are only for the fallback path.
        assertTrue(out.citations.isEmpty())
        assertEquals(listOf(AskPhase.CheckingNitro, AskPhase.AskingNitro), phases)
        // Thin client: only the user question; the chat proxy adds the system prompt, date block and tools.
        assertEquals(listOf(ChatMessage("user", "watch?")), nitro.chats.single())
    }

    @Test fun nitroNeverGetsClientSystemPromptOrLocalExcerpts() = runBlocking {
        val nitro = FakeNitro()
        orchestrator(nitro).ask("what I did yesterday?")
        val sent = nitro.chats.single()
        assertTrue(sent.none { it.role == "system" })
        assertTrue(sent.none { "Vault excerpts" in it.content || "Notes/VaultHome" in it.content })
    }

    @Test fun nitroDisabledSkipsHealthAndFallsBackToHits() = runBlocking {
        val nitro = FakeNitro()
        val out = orchestrator(nitro, AskSettings(excludedProviderIds = setOf(ProviderRouting.NITRO_PROVIDER_ID, ProviderRouting.TOWERX_PROVIDER_ID))).ask("watch?")
        out as AskOutcome.SearchOnly
        assertEquals(NitroSkip.Disabled, out.nitro)
        assertEquals(0, nitro.healthCalls)
        assertEquals(2, out.citations.size)
        assertEquals("obsidian://open?vault=Vault&file=Notes/VaultHome%20Watch", out.citations[0].obsidianUri)
        assertEquals(LocalAnswerEngineStub.unavailableReason, out.localDetail)
    }

    @Test fun nitroDownFallsBackToHits() = runBlocking {
        val out = orchestrator(FakeNitro(health = Health.Down("Nitro refused the connection"))).ask("watch?")
        out as AskOutcome.SearchOnly
        assertEquals(NitroSkip.Down, out.nitro)
        assertEquals("Nitro refused the connection", out.nitroDetail)
        assertEquals(2, out.citations.size)
    }

    @Test fun healthTimeoutRetriesThenStillAsksChat() = runBlocking {
        val nitro = FakeNitro(health = Health.Down("Nitro health check timed out"))
        val out = orchestrator(nitro).ask("watch?")
        assertTrue(out is AskOutcome.Nitro)
        assertEquals(2, nitro.healthCalls)
        assertEquals(1, nitro.chats.size)
    }

    @Test fun nitroLoadingFallsBack() = runBlocking {
        val out = orchestrator(FakeNitro(health = Health.Loading("Loading model"))).ask("watch?")
        assertEquals(NitroSkip.Loading, (out as AskOutcome.SearchOnly).nitro)
    }

    @Test fun chatFailureAfterHealthyFallsBack() = runBlocking {
        val out = orchestrator(FakeNitro(chatError = NitroException("Nitro answer timed out"))).ask("watch?")
        out as AskOutcome.SearchOnly
        assertEquals(NitroSkip.ChatFailed, out.nitro)
        assertEquals("Nitro answer timed out", out.nitroDetail)
    }

    @Test fun localEngineHookIsUsedWhenReady() = runBlocking {
        val engine = ReadyEngine()
        val out = orchestrator(FakeNitro(health = Health.Down("x")), local = engine).ask("watch?")
        out as AskOutcome.Local
        assertEquals("local answer", out.answer)
        assertEquals(2, engine.seen.size)
    }

    @Test fun searchFailureDoesNotBlockNitro() = runBlocking {
        val out = orchestrator(FakeNitro(), search = { error("Download the embedding model") }).ask("watch?")
        out as AskOutcome.Nitro
        assertTrue(out.citations.isEmpty())
        assertEquals(null, out.searchError)
    }

    @Test fun searchFailureAndNitroDownIsStillAnOutcome() = runBlocking {
        val out = orchestrator(FakeNitro(health = Health.Down("x")), search = { error("no model") }, local = ReadyEngine()).ask("q")
        out as AskOutcome.SearchOnly
        assertEquals("no model", out.searchError)
    }

    @Test fun citationsCappedAtSix() {
        val many = (1..20).map { SearchHit("Notes/n$it.md", "h", "s", it / 20f) }
        val c = AskOrchestrator.toCitations(many, 6)
        assertEquals(6, c.size)
        assertEquals("Notes/n20.md", c.first().path)
    }

    @Test fun lanIsTriedAfterTailnetFailsWhenListed() = runBlocking {
        val lan = "http://192.168.1.18:8082"
        val asked = mutableListOf<String>()
        val gw = object : NitroGateway {
            override suspend fun health(baseUrl: String): Health {
                asked += "h:$baseUrl"
                return if (baseUrl == lan) Health.Ok else Health.Down("nope")
            }
            override suspend fun chat(baseUrl: String, messages: List<ChatMessage>, options: ChatOptions): ChatReply {
                asked += "c:$baseUrl"
                return ChatReply("from lan", "qwen")
            }
        }
        val reg = listOf(com.vaulthome.app.devices.RegDevice("nitro5", "Nitro 5", "pc", endpoints = mapOf("lan" to lan)))
        val out = AskOrchestrator(
            gw, { AskSettings(excludedProviderIds = setOf(ProviderRouting.TOWERX_PROVIDER_ID)) }, { hits },
            registry = { reg },
        ).ask("q") as AskOutcome.Nitro
        assertEquals("from lan", out.answer)
        assertEquals(listOf("h:$NITRO_URL", "h:$lan", "c:$lan"), asked)
    }

    /** Real client, nothing listening: falls through without throwing. */
    @Test fun realClientWithNitroKilledFallsThrough() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val out = AskOrchestrator(
            NitroClient(healthTimeoutMs = 300),
            { AskSettings(excludedProviderIds = setOf(ProviderRouting.TOWERX_PROVIDER_ID)) },
            { hits },
            registry = { listOf(com.vaulthome.app.devices.RegDevice("nitro5", "Nitro 5", "pc", chatProxy = "http://127.0.0.1:$port")) },
        ).ask("watch?")
        out as AskOutcome.SearchOnly
        assertEquals(NitroSkip.Down, out.nitro)
        assertEquals(2, out.citations.size)
    }
}
