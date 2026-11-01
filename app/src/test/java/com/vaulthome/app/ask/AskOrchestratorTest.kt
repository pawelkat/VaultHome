package com.vaulthome.app.ask

import com.vaulthome.app.search.SearchHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class AskOrchestratorTest {
    private val hits = listOf(
        SearchHit("Notes/VaultHome Watch.md", "Open loops", "install watch APK", 0.9f),
        SearchHit("Notes/VaultHome Watch.md", "Open loops", "duplicate chunk", 0.8f),
        SearchHit("Notes/VaultHome.md", "", "Home-screen companion", 0.7f),
    )

    private class FakeNitro(var health: Health = Health.Ok, var chatError: Exception? = null) : NitroGateway {
        var healthCalls = 0
        val chats = mutableListOf<List<ChatMessage>>()
        override suspend fun health(baseUrl: String): Health { healthCalls++; return health }
        override suspend fun chat(baseUrl: String, messages: List<ChatMessage>): ChatReply {
            chats += messages
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
        settings: AskSettings = AskSettings(),
        search: suspend (String) -> List<SearchHit> = { hits },
        local: AnswerEngine = LocalAnswerEngineStub,
    ) = AskOrchestrator(nitro, { settings }, search, local)

    @Test fun nitroUpAnswersWithLocalCitations() = runBlocking {
        val nitro = FakeNitro()
        val phases = mutableListOf<AskPhase>()
        val out = orchestrator(nitro).ask("  watch?  ") { phases += it }
        out as AskOutcome.Nitro
        assertEquals("Install the watch APK.", out.answer)
        assertEquals(listOf("Notes/VaultHome Watch.md", "Notes/VaultHome.md"), out.citations.map { it.path })
        assertEquals("obsidian://open?vault=Vault&file=Notes/VaultHome%20Watch", out.citations[0].obsidianUri)
        assertEquals(listOf(AskPhase.CheckingNitro, AskPhase.AskingNitro), phases)
        // Citations only: the question goes to Nitro without the local hits.
        assertEquals("watch?", nitro.chats.single()[1].content)
    }

    @Test fun groundingSendsHitsAsContext() = runBlocking {
        val nitro = FakeNitro()
        orchestrator(nitro, AskSettings(groundNitro = true)).ask("watch?")
        val user = nitro.chats.single()[1].content
        assertTrue(user, user.contains("Notes/VaultHome Watch.md › Open loops") && user.endsWith("Question: watch?"))
    }

    @Test fun nitroDisabledSkipsHealthAndFallsBackToHits() = runBlocking {
        val nitro = FakeNitro()
        val out = orchestrator(nitro, AskSettings(nitroEnabled = false)).ask("watch?")
        out as AskOutcome.SearchOnly
        assertEquals(NitroSkip.Disabled, out.nitro)
        assertEquals(0, nitro.healthCalls)
        assertEquals(2, out.citations.size)
        assertEquals(LocalAnswerEngineStub.unavailableReason, out.localDetail)
    }

    @Test fun nitroDownFallsBackToHits() = runBlocking {
        val out = orchestrator(FakeNitro(health = Health.Down("Nitro timed out"))).ask("watch?")
        out as AskOutcome.SearchOnly
        assertEquals(NitroSkip.Down, out.nitro)
        assertEquals("Nitro timed out", out.nitroDetail)
        assertEquals(2, out.citations.size)
    }

    @Test fun nitroLoadingFallsBack() = runBlocking {
        val out = orchestrator(FakeNitro(health = Health.Loading("Loading model"))).ask("watch?")
        assertEquals(NitroSkip.Loading, (out as AskOutcome.SearchOnly).nitro)
    }

    @Test fun chatFailureAfterHealthyFallsBack() = runBlocking {
        val out = orchestrator(FakeNitro(chatError = NitroException("Nitro timed out"))).ask("watch?")
        out as AskOutcome.SearchOnly
        assertEquals(NitroSkip.ChatFailed, out.nitro)
        assertEquals("Nitro timed out", out.nitroDetail)
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
        assertEquals("Download the embedding model", out.searchError)
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

    /** Real client, nothing listening: falls through without throwing. */
    @Test fun realClientWithNitroKilledFallsThrough() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val out = AskOrchestrator(
            NitroClient(healthTimeoutMs = 300),
            { AskSettings(nitroBaseUrl = "http://127.0.0.1:$port") },
            { hits },
        ).ask("watch?")
        out as AskOutcome.SearchOnly
        assertEquals(NitroSkip.Down, out.nitro)
        assertEquals(2, out.citations.size)
    }
}
