package com.vaulthome.events

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket

/**
 * Wire compatibility with the box: the real Rust `vault-events` hub and agent (VaultSearch PR #9,
 * zenoh 1.10.2) against this Kotlin port on zenoh-kotlin 1.10.2 (JVM jar, same JNI code as the
 * Android AAR). Runs only when `VAULT_EVENTS_BIN` points at the Rust binary.
 */
class RustHubIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bin = System.getenv("VAULT_EVENTS_BIN").orEmpty()
    private val procs = ArrayList<Process>()
    private val engines = ArrayList<SyncEngine>()

    @After
    fun tearDown() {
        engines.forEach { it.stop() }
        procs.forEach { it.destroy() }
        procs.forEach { it.waitFor() }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun rust(name: String, vararg args: String): Process {
        val log = File(tmp.root, "$name.log")
        return ProcessBuilder(listOf(bin) + args).redirectErrorStream(true).redirectOutput(log)
            .apply { environment()["RUST_LOG"] = "info" }.start().also { procs += it }
    }

    private fun kotlinEngine(dir: File, dev: String, cfg: LinkConfig): SyncEngine {
        val e = SyncEngine(
            SyncCore(FileVaultTree(dir), dev, AgentState(dev, dir.path), File(tmp.root, "$dev-state.json")),
            cfg,
            openLink = { ZenohLink.open(it) },
            options = EngineOptions(rescanIntervalMs = 300, linkCheckMs = 500, retryMs = 500, queryTimeoutMs = 3_000),
            log = { println("[$dev] $it") },
        )
        engines += e
        e.start()
        return e
    }

    private fun waitFor(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) {
                tmp.root.listFiles { f -> f.name.endsWith(".log") }?.forEach { println("--- ${it.name}\n${it.readText().takeLast(4000)}") }
                throw AssertionError("timed out: $what")
            }
            Thread.sleep(100)
        }
    }

    private fun File.put(rel: String, s: String) = File(this, rel).apply { parentFile.mkdirs(); writeText(s) }

    @Test
    fun kotlinClientSyncsWithRustHubAndAgent() {
        assumeTrue("set VAULT_EVENTS_BIN to run", bin.isNotEmpty() && File(bin).canExecute())
        val port = freePort()
        val ep = "tcp/127.0.0.1:$port"
        rust("hub", "hub", "--data-dir", tmp.newFolder("hub").path, "--listen", ep, "--name", "testhub")
        waitFor("hub ready") { File(tmp.root, "hub.log").let { it.isFile && it.readText().contains("listening on") } }
        val a = tmp.newFolder("a").apply {
            put("Notes/Rust one.md", "from box\n"); put("Notes/C# and \$HOME ?*%.md", "escaped key\n"); put("Daily/2026-11-18.md", "daily\n")
        }
        rust("agent", "agent", "--vault", a.path, "--device", "box", "--state-dir", tmp.newFolder("as").path, "--connect", ep, "--debounce-ms", "200")
        val b = tmp.newFolder("b")
        val eb = kotlinEngine(b, "s24", LinkConfig(LinkMode.CLIENT, listOf(ep)))

        // Catch-up from Rust hub storage (or live): all three notes land in the Kotlin vault.
        waitFor("catch-up to Kotlin") { File(b, "Notes/C# and \$HOME ?*%.md").isFile && File(b, "Daily/2026-11-18.md").isFile && File(b, "Notes/Rust one.md").isFile }
        assertEquals("escaped key\n", File(b, "Notes/C# and \$HOME ?*%.md").readText())

        // Live Rust -> Kotlin.
        a.put("Notes/live from rust.md", "live\n")
        waitFor("Rust live edit reaches Kotlin") { File(b, "Notes/live from rust.md").isFile }

        // Kotlin -> Rust hub -> Rust agent (publish), including an edit with prev_sha256.
        b.put("Notes/from s24 Zürich ✓.md", "hello from kotlin\n")
        waitFor("Kotlin note reaches Rust agent") { File(a, "Notes/from s24 Zürich ✓.md").isFile }
        Thread.sleep(400)
        File(b, "Notes/Rust one.md").writeText("from box\nedited on s24\n")
        waitFor("Kotlin edit reaches Rust agent") { File(a, "Notes/Rust one.md").readText().contains("edited on s24") }

        // Kotlin delete -> tombstone on the Rust side.
        File(b, "Notes/live from rust.md").delete()
        waitFor("Kotlin delete reaches Rust agent") { !File(a, "Notes/live from rust.md").exists() }

        // The hub's index (queried from Kotlin) shows the Kotlin-originated versions.
        val idx = ZenohLink.open(LinkConfig(LinkMode.CLIENT, listOf(ep))).use { it.fetchIndex(3_000) }!!
        assertEquals("testhub", idx.hub)
        val one = idx.stamps.single { it.path == "Notes/Rust one.md" }
        assertEquals("s24", one.origin)
        assertEquals(2L, one.version)
        assertTrue(idx.stamps.single { it.path == "Notes/live from rust.md" }.deleted)
        assertTrue(eb.status.phase == EngineStatus.Phase.CONNECTED)
    }

    @Test
    fun kotlinRouterForwardsBetweenRustHubAndRustAgent() {
        assumeTrue("set VAULT_EVENTS_BIN to run", bin.isNotEmpty() && File(bin).canExecute())
        // Kotlin session in router mode = the meeting point (like VaultHome on the S24 instead of note4).
        val routerPort = freePort()
        val routerEp = "tcp/127.0.0.1:$routerPort"
        val r = tmp.newFolder("r")
        val er = kotlinEngine(r, "s24", LinkConfig(LinkMode.ROUTER, connect = emptyList(), listen = listOf(routerEp)))
        Thread.sleep(500)
        // The hub dials OUT to the Kotlin router (box -> VAULT_EVENTS_HUB_CONNECT) and listens only on a private port.
        val hubPort = freePort()
        rust("hub", "hub", "--data-dir", tmp.newFolder("hub").path, "--listen", "tcp/127.0.0.1:$hubPort", "--connect", routerEp, "--name", "testhub")
        waitFor("hub dialled the Kotlin router") { er.status.routers >= 1 }
        // Hub subscriber/queryables are declared right after its session opens.
        waitFor("hub ready") { File(tmp.root, "hub.log").readText().contains("listening on") }
        // A Rust agent that can only reach the Kotlin router.
        val c = tmp.newFolder("c").apply { put("Notes/via router.md", "through the kotlin router\n") }
        rust("agent", "agent", "--vault", c.path, "--device", "latitude", "--state-dir", tmp.newFolder("cs").path, "--connect", routerEp, "--debounce-ms", "200")

        waitFor("agent note reaches the router's own vault") { File(r, "Notes/via router.md").isFile }
        // And it was stored by the hub, which the agent can only reach through the Kotlin router.
        waitFor("hub stored the note via the router") {
            runCatching {
                ZenohLink.open(LinkConfig(LinkMode.CLIENT, listOf("tcp/127.0.0.1:$hubPort"))).use { l ->
                    l.fetchIndex(2_000)?.stamps?.any { it.path == "Notes/via router.md" && it.origin == "latitude" } == true
                }
            }.getOrDefault(false)
        }
        // Router's own edits reach both.
        r.put("Notes/from router.md", "s24 router\n")
        waitFor("router edit reaches agent") { File(c, "Notes/from router.md").isFile }
        assertTrue("router sees the hub as a router link", er.status.routers >= 1)
    }
}
