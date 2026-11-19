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

    /** Delegates to a [FileVaultTree]; the first read of [stalePath] returns [staleBytes] (a read mid-write). */
    private class StaleOnce(val inner: FileVaultTree, val stalePath: String, val staleBytes: ByteArray) : VaultTree by inner {
        var served = false
        override fun read(rel: String): Disk {
            if (rel == stalePath && !served) {
                served = true
                return Disk.Present(sha256Hex(staleBytes), staleBytes, 1L, staleBytes.size.toLong())
            }
            return inner.read(rel)
        }
    }

    @Test
    fun identicalBytesNeverMakeAConflictCopy() {
        // S24, 19 Nov: "2026-09-19-20260919_153121 (conflict nitro5 2026-11-18).jpg" was byte-identical to the live photo.
        val dir = tmp.newFolder("s24")
        val c = core(dir, "s24")
        val photo = ByteArray(8192) { (it % 251).toByte() }
        File(dir, "Daily").mkdirs()
        File(dir, "Daily/p.jpg").writeBytes(photo)
        c.onLocal("Daily/p.jpg")!!
        for ((v, prev) in listOf(1L to null, 2L to "x", 9L to null)) {
            val r = c.onIncoming(NoteEvent.put("Daily/p.jpg", photo, "nitro5", v, prev, 1))
            assertTrue("v$v ${r.decision}", r.conflicts.isEmpty())
            assertFalse(r.changed)
        }
        // Fresh state, same bytes already on disk: adopted, nothing written or published.
        val d2 = tmp.newFolder("s24b")
        val c2 = core(d2, "s24")
        File(d2, "Daily").mkdirs()
        File(d2, "Daily/p.jpg").writeBytes(photo)
        val r = c2.onIncoming(NoteEvent.put("Daily/p.jpg", photo, "nitro5", 1, null, 1))
        assertEquals(Decision.Adopt, r.decision)
        assertTrue(r.publish.isEmpty())
        assertEquals(1, File(d2, "Daily").list()!!.size)
    }

    @Test
    fun staleReadThatMatchesIncomingIsAdoptedNotCopied() {
        val dir = tmp.newFolder("v")
        val photo = ByteArray(4096) { 7 }
        File(dir, "Daily").mkdirs()
        File(dir, "Daily/p.jpg").writeBytes(photo)
        // First hash sees a half-written file (Autosync / provider mid-write); the file is complete right after.
        val tree = StaleOnce(FileVaultTree(dir), "Daily/p.jpg", photo.copyOf(1000))
        val logs = ArrayList<String>()
        val c = SyncCore(tree, "s24", AgentState("s24", dir.path), log = { logs += it })
        val r = c.onIncoming(NoteEvent.put("Daily/p.jpg", photo, "nitro5", 1, null, 0))
        assertEquals(Decision.Adopt, r.decision)
        assertTrue(r.conflicts.isEmpty() && r.publish.isEmpty())
        assertEquals(1, File(dir, "Daily").list()!!.size)
        assertTrue(logs.toString(), logs.any { "avoided" in it })
    }

    @Test
    fun writerAndCauseLabels() {
        val dir = tmp.newFolder("w")
        val c = core(dir, "s24")
        assertTrue(c.seeding)
        dir.put("seed.md", "s")
        val seed = c.scan().publish.single()
        assertEquals("s24/external", seed.stamp.writer)
        assertEquals(Cause.SEED, seed.stamp.cause)
        c.seeding = false
        // Obsidian / Termux / Autosync edit.
        dir.put("seed.md", "edited")
        val ext = c.onLocal("seed.md")!!
        assertEquals("s24/external", ext.stamp.writer)
        assertEquals(Cause.EXTERNAL_EDIT, ext.stamp.cause)
        // The app's own write.
        val mine = "app".toByteArray()
        c.noteOwnWrite("app_settings/x.json", mine)
        File(dir, "app_settings").mkdirs()
        File(dir, "app_settings/x.json").writeBytes(mine)
        val own = c.onLocal("app_settings/x.json")!!
        assertEquals("s24/vaulthome", own.stamp.writer)
        assertEquals(Cause.LOCAL_EDIT, own.stamp.cause)
        // Consumed once: the next edit is external again.
        File(dir, "app_settings/x.json").writeText("someone else")
        assertEquals("s24/external", c.onLocal("app_settings/x.json")!!.stamp.writer)
        // Delete.
        File(dir, "seed.md").delete()
        val del = c.onLocal("seed.md")!!
        assertEquals(Cause.DELETE, del.stamp.cause)
        // Labels survive the wire and the state file.
        val back = NoteEvent.decode(own.encode(), own.key())
        assertEquals(own, back)
        assertEquals(publishedLine(own), "published app_settings/x.json v1 by s24/vaulthome (local-edit)")
        // Catch-up republish keeps the writer, marks the cause.
        val (_, rep) = c.planCatchup(emptyList())
        assertTrue("app_settings/x.json" in rep)
        val again = c.republish("app_settings/x.json")!!
        assertEquals("s24/external", again.stamp.writer)
        assertEquals(Cause.CATCHUP_REPUBLISH, again.stamp.cause)
    }

    @Test
    fun conflictCopyCarriesWriterCauseAndBothSides() {
        val dir = tmp.newFolder("cc")
        val c = core(dir, "s24")
        dir.put("a.md", "base")
        val base = c.onLocal("a.md")!!
        dir.put("a.md", "ours, longer")
        val ours = c.onLocal("a.md")!!
        val theirs = NoteEvent.put("a.md", "th".toByteArray(), "box", 2, base.stamp.sha256, ours.stamp.tsMs + 1).by("box/galanonim", Cause.LOCAL_EDIT)
        val r = c.onIncoming(theirs)
        val cp = r.publish.single()
        assertEquals("s24/vaulthome", cp.stamp.writer)
        assertEquals(Cause.CONFLICT_COPY, cp.stamp.cause)
        val info = cp.conflict!!
        assertEquals("a.md", info.of)
        assertEquals(2L, info.winnerSize)
        assertEquals(12L, info.loserSize)
        assertEquals("box", info.winnerOrigin)
        assertEquals("s24", info.loserOrigin)
        assertTrue(info.keptSmaller)
        assertEquals(cp, NoteEvent.decode(cp.encode(), cp.key()))
        assertEquals("box/galanonim", c.state.stamp("a.md")!!.writer)
    }

    @Test
    fun rcloneConflictFilesAreNotPublishedAppliedOrRepublished() {
        val dir = tmp.newFolder("rc")
        val c = core(dir, "s24")
        val p = "Daily/2026-11-19.md..conflict-Date1"
        dir.put(p, "old")
        assertNull(c.onLocal(p))
        assertTrue(c.scan().publish.isEmpty())
        val ev = NoteEvent.put("Daily/x.md.conflict-2026", "x".toByteArray(), "box", 1, null, 1)
        assertEquals(Applied(), c.onIncoming(ev))
        assertFalse(File(dir, "Daily/x.md.conflict-2026").exists())
        assertTrue(c.planCatchup(listOf(ev.stamp)).first.isEmpty())
        c.state.record(NoteEvent.put(p, "old".toByteArray(), "s24", 1, null, 1).stamp, null)
        assertTrue(c.planCatchup(emptyList()).second.isEmpty())
        assertNull(c.republish(p))
        val own = "Daily/2026-11-19 (conflict s24 2026-11-19).md"
        dir.put(own, "mine")
        assertTrue(c.onLocal(own) != null)
    }

    @Test
    fun decodesEventsFromOlderBuilds() {
        val old = """{"format":1,"path":"Daily/2026-11-19.md","version":5,"ts_ms":1763589540000,"origin":"nitro5","deleted":false,"sha256":"8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4","prev_sha256":"abc","content":"aGk="}"""
        val e = NoteEvent.decode(old.toByteArray(), "vault/notes/Daily/2026-11-19.md")
        assertNull(e.stamp.writer)
        assertNull(e.stamp.cause)
        assertNull(e.conflict)
        assertEquals("-", e.stamp.writerStr)
        assertEquals(old, e.encodeToString())
        // Unknown future keys are ignored.
        NoteEvent.decode(old.replace("\"content\"", "\"future\":{\"x\":1},\"content\"").toByteArray(), null)
    }

    @Test
    fun blobReferenceIsNeverAppliedWithoutBytes() {
        val dir = tmp.newFolder("blobcore")
        val c = SyncCore(FileVaultTree(dir), "s24", AgentState("s24", dir.path))
        val big = ByteArray(5000) { 9 }
        val r = NoteEvent.put("Daily/p.jpg", big, "nitro5", 1, null, 1).toBlobRef(1000, 4096)!!
        org.junit.Assert.assertTrue(c.needsBytes(r))
        try {
            c.onIncoming(r)
            org.junit.Assert.fail("applied without bytes")
        } catch (e: IllegalStateException) {
        }
        org.junit.Assert.assertFalse(java.io.File(dir, "Daily/p.jpg").exists())
        org.junit.Assert.assertTrue(c.onIncoming(r.withContent(big)).changed)
        org.junit.Assert.assertArrayEquals(big, java.io.File(dir, "Daily/p.jpg").readBytes())
        org.junit.Assert.assertFalse("same state: stamp is enough", c.needsBytes(r))
        org.junit.Assert.assertEquals(Decision.Ignore, c.onIncoming(r).decision)
    }
}
