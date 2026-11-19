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
        val blobServers = CopyOnWriteArrayList<Pair<String, (String, Long, Long) -> ByteArray?>>()
        var puts = 0
        var maxPut = 0
        var chunkRequests = 0
        val configs = CopyOnWriteArrayList<LinkConfig>()

        fun link() = object : EventLink {
            override fun subscribe(onSample: (String, ByteArray) -> Unit) { subs += onSample }
            override fun publish(ev: NoteEvent) {
                synchronized(this@Bus) {
                    puts++
                    maxPut = maxOf(maxPut, ev.encode().size)
                    val cur = stored[ev.path]
                    if (cur == null || ev.stamp.beats(cur.stamp)) stored[ev.path] = ev
                }
                subs.forEach { it(ev.key(), ev.encode()) }
            }
            override fun fetchIndex(timeoutMs: Long) = synchronized(this@Bus) { IndexReply(1, "fake", stored.values.map { it.stamp }) }
            override fun fetchNote(path: String, timeoutMs: Long) = synchronized(this@Bus) { stored[path] }
            override fun routerCount() = 1
            override fun peerCount() = 0
            override fun serveBlobs(
                device: String,
                provider: (String, Long, Long) -> ByteArray?,
                describe: (String) -> BlobLabel?,
                log: (String) -> Unit,
            ) { blobServers += device to provider }
            override fun fetchChunk(sha: String, index: Long, chunkSize: Long, timeoutMs: Long, src: String): ByteArray? {
                synchronized(this@Bus) { chunkRequests++ }
                return blobServers.filter { Blob.agentAnswers(it.first, src) }.firstNotNullOfOrNull { it.second(sha, index, chunkSize) }
            }
            override fun close() {}
        }
    }

    private fun engine(bus: Bus, dir: File, dev: String) = SyncEngine(
        SyncCore(FileVaultTree(dir), dev, AgentState(dev, dir.path)),
        LinkConfig(LinkMode.CLIENT, listOf("tcp/127.0.0.1:1")),
        openLink = { bus.configs += it; bus.link() },
        options = EngineOptions(
            rescanIntervalMs = 100, catchupIntervalMs = 60_000, linkCheckMs = 1_000,
            partialDir = File(tmp.root, "partial-$dev"),
        ),
    )

    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun linkOpensWithDeviceIdentity() {
        val bus = Bus()
        val e = engine(bus, tmp.newFolder("id"), "s24")
        e.start()
        waitFor { bus.configs.isNotEmpty() && bus.blobServers.isNotEmpty() }
        assertEquals("s24", bus.configs.first().device)
        assertEquals("s24", bus.blobServers.first().first)
        e.stop(); e.awaitStopped(5_000)
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

    @Test
    fun largeFileTravelsAsBlobReferencePlusChunks() {
        val bus = Bus()
        val a = tmp.newFolder("ba"); val b = tmp.newFolder("bb")
        File(a, "Daily").mkdirs(); File(b, "Daily").mkdirs()
        val ea = engine(bus, a, "s24").also { it.start() }
        val eb = engine(bus, b, "box").also { it.start() }
        Thread.sleep(200)
        val photo = ByteArray(3_000_000) { (it * 7 % 253).toByte() }
        File(a, "Daily/photo.jpg").writeBytes(photo)
        waitFor(10_000) { File(b, "Daily/photo.jpg").let { it.isFile && it.length() == photo.size.toLong() && sha256Hex(it.readBytes()) == sha256Hex(photo) } }
        val stored = bus.stored["Daily/photo.jpg"]!!
        assertTrue("stored as a reference", stored.isBlobRef)
        assertEquals(12L, stored.blob!!.chunks)
        assertTrue("no put carried the photo (max ${bus.maxPut} B)", bus.maxPut < 4096)
        assertTrue(bus.chunkRequests >= 12)
        assertTrue("partial files cleaned up", File(tmp.root, "partial-box").listFiles().orEmpty().isEmpty())
        // A late joiner gets it through catch-up.
        val c = tmp.newFolder("bc")
        val ec = engine(bus, c, "nitro5").also { it.start() }
        waitFor(10_000) { File(c, "Daily/photo.jpg").let { it.isFile && sha256Hex(it.readBytes()) == sha256Hex(photo) } }
        listOf(ea, eb, ec).forEach { it.stop() }
    }
}
