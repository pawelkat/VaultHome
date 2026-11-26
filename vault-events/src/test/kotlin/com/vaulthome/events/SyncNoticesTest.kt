package com.vaulthome.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class SyncNoticesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun core(dir: File, dev: String) = SyncCore(FileVaultTree(dir), dev, AgentState(dev, dir.path))
    private fun File.put(rel: String, s: String) = File(this, rel).apply { parentFile.mkdirs(); writeText(s) }

    @Test
    fun remoteCreateUpdateDeleteNotify() {
        val box = tmp.newFolder("box"); val s24 = tmp.newFolder("s24")
        val cBox = core(box, "box"); val cS24 = core(s24, "s24")
        box.put("Notes/Experiment.md", "v1")
        val create = cBox.onLocal("Notes/Experiment.md")!!
        assertEquals(listOf(SyncedNote("Notes/Experiment.md", "box")), SyncNotices.fromIncoming(create, cS24.onIncoming(create), "s24"))
        box.put("Notes/Experiment.md", "v2")
        val update = cBox.onLocal("Notes/Experiment.md")!!
        assertEquals(listOf(SyncedNote("Notes/Experiment.md", "box")), SyncNotices.fromIncoming(update, cS24.onIncoming(update), "s24"))
        File(box, "Notes/Experiment.md").delete()
        val del = cBox.onLocal("Notes/Experiment.md")!!
        val n = SyncNotices.fromIncoming(del, cS24.onIncoming(del), "s24")
        assertEquals(listOf(SyncedNote("Notes/Experiment.md", "box", deleted = true)), n)
        assertEquals("Deleted: Experiment" to "Notes/Experiment.md · from box", SyncNotices.singleText(n[0]))
    }

    @Test
    fun ownChangesAndEchoesDoNotNotify() {
        val s24 = tmp.newFolder("s24")
        val c = core(s24, "s24")
        s24.put("Notes/mine.md", "local edit")
        val own = c.onLocal("Notes/mine.md")!!
        // The phone's own put delivered back to its session (zenoh-kotlin has no Locality::Remote).
        val echo = c.onIncoming(own)
        assertEquals(Decision.Ignore, echo.decision)
        assertTrue(SyncNotices.fromIncoming(own, echo, "s24").isEmpty())
        // Own-origin event that does change disk (state reset, older copy on the hub): still silent.
        val other = tmp.newFolder("fresh")
        val fresh = core(other, "s24")
        val a = fresh.onIncoming(own)
        assertTrue(a.changed)
        assertTrue(SyncNotices.fromIncoming(own, a, "s24").isEmpty())
    }

    @Test
    fun adoptAndRepeatedDeliveryDoNotNotify() {
        val box = tmp.newFolder("box"); val s24 = tmp.newFolder("s24")
        val cBox = core(box, "box"); val cS24 = core(s24, "s24")
        box.put("n.md", "same"); s24.put("n.md", "same")
        val ev = cBox.onLocal("n.md")!!
        val adopt = cS24.onIncoming(ev)
        assertEquals(Decision.Adopt, adopt.decision)
        assertTrue(SyncNotices.fromIncoming(ev, adopt, "s24").isEmpty())
        // Same event again (another router path / catch-up after live): ignored, silent.
        assertTrue(SyncNotices.fromIncoming(ev, cS24.onIncoming(ev), "s24").isEmpty())
    }

    @Test
    fun conflictCopyNotifiesAndSharedConfigIsSilent() {
        val box = tmp.newFolder("box"); val s24 = tmp.newFolder("s24")
        val cBox = core(box, "box"); val cS24 = core(s24, "s24")
        box.put("c.md", "base")
        cS24.onIncoming(cBox.onLocal("c.md")!!)
        box.put("c.md", "box edit"); val evBox = cBox.onLocal("c.md")!!
        s24.put("c.md", "s24 edit") // not yet scanned: concurrent edit
        val a = cS24.onIncoming(evBox)
        val notes = SyncNotices.fromIncoming(evBox, a, "s24")
        assertTrue(notes.any { it.conflictCopy && it.path.contains(" (conflict ") })
        box.put(SharedEndpoints.PATH, "{}")
        val cfg = cBox.onLocal(SharedEndpoints.PATH)!!
        val ac = cS24.onIncoming(cfg)
        assertTrue(ac.changed)
        assertTrue(SyncNotices.fromIncoming(cfg, ac, "s24").isEmpty())
    }

    @Test
    fun planGroupsManyIntoOneSummary() {
        val few = (1..3).map { SyncedNote("Notes/n$it.md", "box") }
        assertEquals(few.map { Notice.Single(it) }, SyncNotices.plan(few, catchUp = true))
        val many = (1..12).map { SyncedNote("Notes/n$it.md", if (it % 2 == 0) "box" else "latitude") }
        val p = SyncNotices.plan(many, catchUp = true)
        assertEquals(1, p.size)
        val s = p[0] as Notice.Summary
        assertEquals(12, s.count)
        assertEquals("12 notes synced from 2 devices", SyncNotices.summaryTitle(s))
        // Same path twice in a batch counts once (latest state wins).
        val dup = listOf(SyncedNote("a.md", "box"), SyncedNote("a.md", "box", deleted = true))
        assertEquals(listOf(Notice.Single(SyncedNote("a.md", "box", deleted = true))), SyncNotices.plan(dup, catchUp = false))
        assertTrue(SyncNotices.plan(emptyList(), catchUp = false).isEmpty())
    }

    @Test
    fun engineBatchesCatchUpAndLiveNotes() {
        val bus = SyncEngineTest.Bus()
        val box = tmp.newFolder("box"); val s24 = tmp.newFolder("s24")
        for (i in 1..6) box.put("Notes/n$i.md", "note $i")
        val notices = CopyOnWriteArrayList<List<Notice>>()
        val opts = EngineOptions(rescanIntervalMs = 100, catchupIntervalMs = 60_000, linkCheckMs = 1_000, notifyDebounceMs = 200, hubWaitMs = 0)
        val eBox = SyncEngine(SyncCore(FileVaultTree(box), "box", AgentState("box", box.path)), LinkConfig(LinkMode.CLIENT, listOf("tcp/127.0.0.1:1")), { bus.link() }, opts)
        eBox.start()
        waitFor { bus.stored.size == 6 }
        s24.put("Daily/2026-11-18.md", "phone note")
        val eS24 = SyncEngine(
            SyncCore(FileVaultTree(s24), "s24", AgentState("s24", s24.path)), LinkConfig(LinkMode.ROUTER, emptyList(), listOf("tcp/0.0.0.0:7447")),
            { bus.link() }, opts, onNotices = { notices += it },
        )
        eS24.start()
        // Catch-up of 6 notes from the store: one summary, not six notifications.
        waitFor { notices.isNotEmpty() }
        val first = notices[0].single() as Notice.Summary
        assertEquals(6, first.count)
        assertTrue(first.catchUp)
        // The phone's own note reaches the box but posts nothing on the phone.
        waitFor { File(box, "Daily/2026-11-18.md").exists() }
        // One live edit from the box: one single notification after the debounce.
        Thread.sleep(50)
        box.put("Notes/n1.md", "edited on box")
        waitFor { notices.size >= 2 }
        assertEquals(listOf(Notice.Single(SyncedNote("Notes/n1.md", "box"))), notices[1])
        Thread.sleep(600)
        assertEquals(2, notices.size)
        eBox.stop(); eS24.stop()
        assertTrue(eBox.awaitStopped(2_000) && eS24.awaitStopped(2_000))
    }

    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }
}
