package com.vaulthome.events

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlobTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun data(n: Int) = ByteArray(n) { (it * 31 % 251).toByte() }

    @Test
    fun chunkMathAndKeysMatchRust() {
        assertEquals(0L, Blob.chunkCount(0, 10))
        assertEquals(1L, Blob.chunkCount(10, 10))
        assertEquals(2L, Blob.chunkCount(11, 10))
        assertEquals(27L, Blob.chunkCount(7_000_000, Blob.DEFAULT_CHUNK_SIZE))
        assertEquals(20L to 5L, Blob.chunkRange(25, 10, 2))
        assertNull(Blob.chunkRange(25, 10, 3))
        val sha = sha256Hex("x".toByteArray())
        assertEquals("vault/blob/$sha/12", Blob.key(sha, 12))
        assertEquals(sha to 12L, Blob.parseKey(Blob.key(sha, 12)))
        assertNull(Blob.parseKey("vault/blob/$sha/x"))
        assertNull(Blob.parseKey("vault/blob/abc/1"))
        assertNull(Blob.parseKey("vault/notes/$sha/1"))
        assertEquals(Blob.MIN_CHUNK_SIZE, Blob.chunkSizeParam("1"))
        assertEquals(Blob.DEFAULT_CHUNK_SIZE, Blob.chunkSizeParam(null))
        assertEquals("vault/blob/$sha/0?cs=4096", Blob.selector(sha, 0, 4096))
        assertEquals("vault/blob/$sha/3?cs=4096;src=hub", Blob.selector(sha, 3, 4096, Blob.SRC_HUB))
    }

    @Test
    fun srcNamesOneServer() {
        // Older receivers send no src: everyone answers, as before.
        assertTrue(Blob.agentAnswers("s24", null))
        assertTrue(Blob.agentAnswers("s24", "any"))
        assertTrue(Blob.agentAnswers("s24", "s24"))
        assertFalse(Blob.agentAnswers("s24", "hub"))
        assertFalse(Blob.agentAnswers("s24", "towerx"))
        assertEquals(listOf("hub", "towerx", "any"), Blob.sources("towerx"))
    }

    @Test
    fun fetchKeepsChunksInFlightAndSticksToTheSourceThatAnswered() {
        val b = data(40_000)
        val info = BlobInfo.of(b.size.toLong(), 4096) // 10 chunks
        val now = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val f = BlobFetcher(tmp.root.resolve("p1"), TransferOptions(backoffMs = 1), { _, i, cs, src ->
            asked += src
            val n = now.incrementAndGet()
            peak.accumulateAndGet(n) { a, c -> maxOf(a, c) }
            Thread.sleep(20)
            now.decrementAndGet()
            // Only the publishing device has it (the hub has not pulled it yet).
            if (src == "towerx") Blob.slice(b, i, cs) else null
        }, sleep = {})
        val t0 = System.currentTimeMillis()
        val got = f.fetch(sha256Hex(b), info, Blob.sources("towerx"))
        assertArrayEquals(b, got.bytes)
        assertEquals(4, peak.get())
        assertTrue("pipelined", System.currentTimeMillis() - t0 < 1_000)
        // At most the first wave asked the hub; the rest went straight to towerx; nobody asked `any`.
        assertTrue(asked.count { it == "hub" } <= 4)
        assertEquals(10, asked.count { it == "towerx" })
        assertFalse(asked.contains("any"))
    }

    @Test
    fun assemblerResumesAndDiscardsOnMismatch() {
        val dir = tmp.newFolder("partial")
        val b = data(10_000)
        val sha = sha256Hex(b)
        var a = Assembler(dir, sha, b.size.toLong(), 4096)
        assertEquals(listOf(0L, 1L, 2L), a.missing())
        assertFalse("wrong length", a.put(0, b.copyOf(100)))
        assertTrue(a.put(2, b.copyOfRange(8192, 10_000)))
        a = Assembler(dir, sha, b.size.toLong(), 4096)
        assertEquals("resumed after reopen", listOf(0L, 1L), a.missing())
        val bad = b.copyOfRange(4096, 8192).also { it[0] = (it[0] + 1).toByte() }
        a.put(0, b.copyOfRange(0, 4096)); a.put(1, bad)
        assertNull("mismatch", a.finish())
        assertEquals(listOf(0L, 1L, 2L), a.missing())
        for (i in 0L..2L) a.put(i, Blob.slice(b, i, 4096)!!)
        assertArrayEquals(b, a.finish())
        assertTrue("partial files removed", dir.listFiles()!!.isEmpty())
    }

    private class Fake(val bytes: ByteArray) {
        val asked = ArrayList<Long>()
        val fail = HashMap<Long, Int>()
        val corrupt = HashMap<Long, Int>()
        @Synchronized
        fun chunk(i: Long, cs: Long): ByteArray? {
            asked += i
            fail[i]?.let { if (it > 0) { fail[i] = it - 1; return null } }
            val c = Blob.slice(bytes, i, cs) ?: return null
            corrupt[i]?.let { if (it > 0) { corrupt[i] = it - 1; c[0] = (c[0].toInt() xor 0xff).toByte() } }
            return c
        }
    }

    private fun fetcher(f: Fake, rounds: Int = 8) =
        BlobFetcher(tmp.root.resolve("partial"), TransferOptions(rounds = rounds, backoffMs = 1), { _, i, cs, _ -> f.chunk(i, cs) }, sleep = {})

    @Test
    fun fetchRetriesOnlyMissingChunks() {
        val b = data(20_000)
        val f = Fake(b).apply { fail[3] = 2 }
        val got = fetcher(f).fetch(sha256Hex(b), BlobInfo.of(b.size.toLong(), 4096))
        assertArrayEquals(b, got.bytes)
        assertEquals(2, got.retries)
        // 0,1,2,4 once; 3 three times (the other chunks do not wait for it).
        assertEquals(listOf(0L, 1L, 2L, 3L, 3L, 3L, 4L), f.asked.sorted())
    }

    @Test
    fun fetchResumesFromPartialFile() {
        val b = data(20_000)
        val info = BlobInfo.of(b.size.toLong(), 4096)
        val f1 = Fake(b).apply { fail[2] = 1000 }
        try {
            fetcher(f1, rounds = 2).fetch(sha256Hex(b), info)
            fail("should give up")
        } catch (e: BlobFetchException) {
            assertTrue(e.message!!, e.message!!.contains("4/5"))
        }
        val f2 = Fake(b)
        val got = fetcher(f2).fetch(sha256Hex(b), info)
        assertArrayEquals(b, got.bytes)
        assertEquals(4, got.resumed)
        assertEquals(listOf(2L), f2.asked)
    }

    @Test
    fun fetchRefetchesAfterHashMismatch() {
        val b = data(20_000)
        val info = BlobInfo.of(b.size.toLong(), 4096)
        val f = Fake(b).apply { corrupt[1] = 1 }
        assertArrayEquals(b, fetcher(f).fetch(sha256Hex(b), info).bytes)
        assertEquals(10, f.asked.size)
        val liar = Fake(b).apply { corrupt[0] = Int.MAX_VALUE }
        try {
            fetcher(liar, rounds = 3).fetch(sha256Hex(b), info)
            fail("a lying source never yields bytes")
        } catch (e: BlobFetchException) {
            assertTrue(e.message!!, e.message!!.contains("mismatch"))
        }
    }

    @Test
    fun outgoingCacheBounds() {
        var now = 0L
        val c = OutgoingCache(maxBytes = 25_000, ttlMs = 1_000, now = { now })
        val a = data(10_000)
        c.insert("a", a); c.insert("b", data(12_000))
        assertArrayEquals(a.copyOfRange(4096, 8192), c.chunk("a", 1, 4096))
        c.insert("d", data(9_000))
        assertNull("oldest evicted", c.get("a"))
        now = 2_000
        assertNull("expired", c.get("b"))
    }

    @Test
    fun blobReferenceWireAndOldReaders() {
        val big = data(3000)
        val e = NoteEvent.put("Daily/p.jpg", big, "nitro5", 2, null, 9).by("nitro5/external", Cause.EXTERNAL_EDIT)
        assertNull("at the limit stays inline", e.toBlobRef(3000, 4096))
        val r = e.toBlobRef(1000, 4096)!!
        assertTrue(r.isBlobRef)
        assertEquals(3000L, r.size)
        assertTrue(r.encodeToString().endsWith("\"cause\":\"external-edit\",\"blob\":{\"size\":3000,\"chunk_size\":4096,\"chunks\":1}}"))
        assertEquals(e, NoteEvent.decode(r.encode(), r.key()).withContent(big))
        try {
            r.withContent(ByteArray(3000)); fail()
        } catch (x: DecodeException) {
            assertEquals(DecodeErrorKind.HashMismatch, x.kind)
        }
    }
}
