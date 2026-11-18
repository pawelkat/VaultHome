package com.vaulthome.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Engine against an in-memory bus that behaves like the Rust hub (keeps the stamp that beats) and,
 * like zenoh-kotlin without `Locality::Remote`, delivers every put to *all* subscribers including
 * the publisher's own session.
 */
class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    class Bus {
        val stored = HashMap<String, NoteEvent>()
        val subs = CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
        var puts = 0

        fun link() = object : EventLink {
            override fun subscribe(onSample: (String, ByteArray) -> Unit) { subs += onSample }
            override fun publish(ev: NoteEvent) {
                synchronized(this@Bus) {
                    puts++
                    val cur = stored[ev.path]
                    if (cur == null || ev.stamp.beats(cur.stamp)) stored[ev.path] = ev
                }
                subs.forEach { it(ev.key(), ev.encode()) }
            }
            override fun fetchIndex(timeoutMs: Long) = synchronized(this@Bus) { IndexReply(1, "fake", stored.values.map { it.stamp }) }
            override fun fetchNote(path: String, timeoutMs: Long) = synchronized(this@Bus) { stored[path] }
            override fun routerCount() = 1
            override fun peerCount() = 0
            override fun close() {}
        }
    }

    private fun engine(bus: Bus, dir: File, dev: String) = SyncEngine(
        SyncCore(FileVaultTree(dir), dev, AgentState(dev, dir.path)),
        LinkConfig(LinkMode.CLIENT, listOf("tcp/127.0.0.1:1")),
        openLink = { bus.link() },
        options = EngineOptions(rescanIntervalMs = 100, catchupIntervalMs = 60_000, linkCheckMs = 1_000),
    )

    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun twoEnginesSyncWithoutEchoLoops() {
        val bus = Bus()
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        File(a, "Notes").mkdirs(); File(a, "Notes/one.md").writeText("1")
        File(b, "Daily").mkdirs(); File(b, "Daily/2026-11-18.md").writeText("d")
        val ea = engine(bus, a, "box").also { it.start() }
        waitFor { bus.stored.containsKey("Notes/one.md") }
        val eb = engine(bus, b, "s24").also { it.start() }
        // B catches up A's note from the store; A gets B's note live.
        waitFor { File(b, "Notes/one.md").exists() && File(a, "Daily/2026-11-18.md").exists() }
        // Edit on B propagates to A through the rescan poll.
        Thread.sleep(50)
        File(b, "Notes/one.md").writeText("1 edited on s24")
        waitFor { File(a, "Notes/one.md").readText() == "1 edited on s24" }
        assertEquals(2L, bus.stored["Notes/one.md"]!!.stamp.version)
        // Quiet period: no echo ping-pong, the put counter stays put.
        Thread.sleep(500)
        val puts = bus.puts
        Thread.sleep(500)
        assertEquals(puts, bus.puts)
        assertTrue(ea.status.applied >= 1 && eb.status.published >= 1)
        ea.stop(); eb.stop()
        assertTrue(ea.awaitStopped(2_000) && eb.awaitStopped(2_000))
    }
}
