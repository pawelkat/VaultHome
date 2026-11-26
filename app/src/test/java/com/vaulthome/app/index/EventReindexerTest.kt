package com.vaulthome.app.index

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class EventReindexerTest {
    @Test fun planKeepsOnlyMarkdownAndDedupes() {
        assertEquals(
            ReindexPlan.Paths(listOf("Daily/2026-11-26.md", "Notes/a.markdown")),
            ReindexPlan.of(listOf("Daily/2026-11-26.md", "app_settings/VaultHome/history.json", "Daily/2026-11-26.md", "Notes/a.markdown", "img.png", ".trash/x.md"), false),
        )
        assertNull(ReindexPlan.of(listOf("a.json", "b.png"), false))
        assertEquals(ReindexPlan.Full, ReindexPlan.of(emptyList(), true))
    }

    @Test fun bigBurstFallsBackToFullIncremental() {
        val many = (0..ReindexPlan.MAX_PATHS).map { "n$it.md" }
        assertEquals(ReindexPlan.Full, ReindexPlan.of(many, false))
        assertTrue(ReindexPlan.of(many.take(ReindexPlan.MAX_PATHS), false) is ReindexPlan.Paths)
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun burstOfEventsIsOnePassAfterTheQuietPeriod() = runBlocking {
        val plans = CopyOnWriteArrayList<ReindexPlan>()
        val r = EventReindexer(scope(), quietMs = 80, maxWaitMs = 5_000) { plans += it; true }
        r.onVaultChanged(listOf("a.md"))
        delay(30); r.onVaultChanged(listOf("b.md", "a.md"))
        delay(30); r.onVaultChanged(listOf("c.md"))
        assertTrue("nothing runs inside the burst", plans.isEmpty())
        delay(300)
        assertEquals(listOf<ReindexPlan>(ReindexPlan.Paths(listOf("a.md", "b.md", "c.md"))), plans.toList())
    }

    @Test fun maxWaitStopsAnEndlessBurstFromStarvingTheIndex() = runBlocking {
        val plans = CopyOnWriteArrayList<ReindexPlan>()
        val r = EventReindexer(scope(), quietMs = 200, maxWaitMs = 120) { plans += it; true }
        repeat(6) { i -> r.onVaultChanged(listOf("n$i.md")); delay(40) }
        delay(400)
        assertTrue("an early pass ran before the burst ended", plans.size >= 2)
        assertEquals(6, plans.flatMap { (it as ReindexPlan.Paths).paths }.toSet().size)
    }

    @Test fun nonMarkdownEventsAreIgnored() = runBlocking {
        val plans = CopyOnWriteArrayList<ReindexPlan>()
        val r = EventReindexer(scope(), quietMs = 30) { plans += it; true }
        r.onVaultChanged(listOf("app_settings/VaultHome/history.json"))
        delay(150)
        assertTrue(plans.isEmpty())
        assertEquals(0, r.pendingCount())
    }

    @Test fun failedPassKeepsThePathsForTheNextEvent() = runBlocking {
        val plans = CopyOnWriteArrayList<ReindexPlan>()
        var ready = false
        val r = EventReindexer(scope(), quietMs = 30) { plans += it; ready }
        r.onVaultChanged(listOf("a.md"))
        delay(150)
        assertEquals(1, r.pendingCount())
        ready = true
        r.onVaultChanged(listOf("b.md"))
        delay(150)
        assertEquals(listOf<ReindexPlan>(ReindexPlan.Paths(listOf("a.md")), ReindexPlan.Paths(listOf("a.md", "b.md"))), plans.toList())
        assertEquals(0, r.pendingCount())
    }

    @Test fun requestFullAfterCatchUpRunsAFullPass() = runBlocking {
        val plans = CopyOnWriteArrayList<ReindexPlan>()
        val r = EventReindexer(scope(), quietMs = 30) { plans += it; true }
        r.requestFull()
        delay(150)
        assertEquals(listOf<ReindexPlan>(ReindexPlan.Full), plans.toList())
    }
}
