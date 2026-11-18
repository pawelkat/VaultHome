package com.vaulthome.events

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun core(dir: File, dev: String) = SyncCore(FileVaultTree(dir), dev, AgentState(dev, dir.path))

    private fun File.put(rel: String, s: String) = File(this, rel).apply { parentFile.mkdirs(); writeText(s) }

    @Test
    fun localEditPropagatesAndEchoIsSuppressed() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        val ca = core(a, "box"); val cb = core(b, "s24")
        a.put("Notes/x.md", "one")
        val ev = ca.onLocal("Notes/x.md")!!
        assertEquals(1L, ev.stamp.version)
        assertNull(ev.stamp.prevSha256)
        // B applies.
        val r = cb.onIncoming(NoteEvent.decode(ev.encode(), ev.key()))
        assertTrue(r.changed)
        assertEquals("one", File(b, "Notes/x.md").readText())
        // B's rescan sees its own write: nothing to publish (content echo suppression).
        assertTrue(cb.scan().publish.isEmpty())
        // The event coming back to A's own session (no Locality::Remote in zenoh-kotlin) is ignored.
        assertEquals(Decision.Ignore, ca.onIncoming(ev).decision)
        // Second edit is a fast-forward with prev sha.
        File(b, "Notes/x.md").writeText("two")
        val ev2 = cb.onLocal("Notes/x.md")!!
        assertEquals(2L, ev2.stamp.version)
        assertEquals(ev.stamp.sha256, ev2.stamp.prevSha256)
        assertEquals(Decision.Apply(null), ca.onIncoming(ev2).decision)
        assertEquals("two", File(a, "Notes/x.md").readText())
    }

    @Test
    fun deletePropagatesAsTombstone() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        val ca = core(a, "box"); val cb = core(b, "s24")
        a.put("n.md", "x")
        cb.onIncoming(ca.onLocal("n.md")!!)
        File(a, "n.md").delete()
        val del = ca.onLocal("n.md")!!
        assertTrue(del.stamp.deleted)
        assertNull(del.content)
        assertEquals("", del.stamp.sha256)
        assertTrue(cb.onIncoming(del).changed)
        assertFalse(File(b, "n.md").exists())
    }

    @Test
    fun concurrentEditMakesConflictCopyOnBothSides() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        val ca = core(a, "box"); val cb = core(b, "s24")
        a.put("a.md", "base")
        val base = ca.onLocal("a.md")!!
        cb.onIncoming(base)
        a.put("a.md", "ours")
        val ours = ca.onLocal("a.md")!!
        b.put("a.md", "theirs")
        File(b, "a.md").setLastModified(ours.stamp.tsMs + 1000)
        val theirs = cb.onLocal("a.md")!!
        // Each side receives the other's v2.
        val ra = ca.onIncoming(theirs)
        val rb = cb.onIncoming(ours)
        // Same winner everywhere, and the same conflict copy name and bytes.
        assertEquals(File(a, "a.md").readText(), File(b, "a.md").readText())
        val copies = (ra.conflicts + rb.conflicts).toSet()
        assertEquals(1, copies.size)
        val copy = copies.single()
        assertTrue(copy, copy.startsWith("a (conflict box ") && copy.endsWith(").md"))
        assertEquals("theirs", File(a, "a.md").readText())
        assertEquals("ours", File(a, copy).readText())
        // A (which wrote the copy) publishes it as an ordinary new note, so B gets it too.
        assertTrue(ra.publish.any { it.path == copy })
    }

    @Test
    fun massDeleteGuardAndMinFiles() {
        val a = tmp.newFolder("a")
        val ca = core(a, "box")
        ca.maxDelete = 2
        repeat(8) { a.put("n$it.md", "x$it") }
        assertEquals(8, ca.scan().publish.size)
        repeat(4) { File(a, "n$it.md").delete() }
        val b = ca.scan()
        assertTrue(b.publish.isEmpty())
        assertEquals(4, b.refusedDeletes.size)
        // Empty vault (unmounted SD / revoked grant) is refused by the min-files guard.
        (4 until 8).forEach { File(a, "n$it.md").delete() }
        assertTrue(ca.scan().publish.isEmpty())
        assertTrue(ca.lastScanRefused != null)
    }

    @Test
    fun ignoredAndEscapingPathsAreRefused() {
        val a = tmp.newFolder("a")
        val ca = core(a, "box")
        val ev = NoteEvent.put(".obsidian/workspace.json", "{}".toByteArray(), "s24", 1, null, 1)
        assertEquals(Applied(), ca.onIncoming(ev))
        assertFalse(File(a, ".obsidian").exists())
        a.put(".obsidian/x.json", "{}")
        a.put("Notes/a.md.tmp", "t")
        assertTrue(ca.scan().publish.isEmpty())
    }

    @Test
    fun catchUpPlanAndAdopt() {
        val a = tmp.newFolder("a")
        val ca = core(a, "s24")
        a.put("same.md", "same")
        a.put("mine.md", "mine")
        val hub = listOf(
            NoteEvent.put("same.md", "same".toByteArray(), "box", 3, null, 5).stamp,
            NoteEvent.put("other.md", "o".toByteArray(), "box", 1, null, 5).stamp,
        )
        val (fetch, republish) = ca.planCatchup(hub)
        assertEquals(listOf("same.md", "other.md"), fetch)
        assertTrue(republish.isEmpty())
        assertTrue(ca.adoptIfSame(hub[0]))
        assertFalse(ca.adoptIfSame(hub[1]))
        assertEquals(hub[0], ca.state.stamp("same.md"))
        // A local-only note is published by the scan, then the plan republishes it if the hub lacks it.
        val pub = ca.scan().publish.map { it.path }
        assertEquals(listOf("mine.md"), pub)
        assertEquals(listOf("mine.md"), ca.planCatchup(hub).second)
        assertArrayEquals("mine".toByteArray(), ca.republish("mine.md")!!.content)
    }

    @Test
    fun stateSavesAndRefusesOtherDevice() {
        val a = tmp.newFolder("a")
        val f = File(tmp.root, "state/s.json")
        val st = AgentState("s24", "content://tree/x")
        st.record(NoteEvent.put("Notes/ü \"q\".md", "x".toByteArray(), "s24", 4, "ab", 9).stamp, 5L to 1L)
        st.save(f)
        val back = AgentState.loadOrNew(f, "s24", "content://tree/x")
        assertEquals(st.entries, back.entries)
        assertTrue(runCatching { AgentState.loadOrNew(f, "box", "content://tree/x") }.isFailure)
        assertTrue(a.isDirectory)
    }
}
