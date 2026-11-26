package com.vaulthome.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {
    @Test
    fun fromParameterAddedLikeRust() {
        assertEquals("vault/hub/index?from=s24", Requester.withFrom(VaultKey.INDEX_KEY, "s24"))
        assertEquals(
            "vault/blob/abc/3?cs=262144;src=hub;from=s24",
            Requester.withFrom(Blob.selector("abc", 3, 262_144, Blob.SRC_HUB), "s24"),
        )
        assertEquals("k?from=unknown", Requester.withFrom("k", "bad;x=y"))
        assertEquals("k", Requester.withFrom("k", ""))
        assertEquals("unknown", Requester.clean(null))
        assertEquals("unknown", Requester.clean("x".repeat(65)))
        assertEquals("note4c_1.a-b", Requester.clean("note4c_1.a-b"))
        assertEquals("vault/presence/s24", Presence.key("s24"))
        assertEquals(
            """{"device":"s24","role":"vaulthome","build":"vaulthome 0.1.0+abc1234","started_ms":5}""",
            String(PresenceInfo("s24", "vaulthome", "vaulthome 0.1.0+abc1234", 5).encode()),
        )
    }

    @Test
    fun serveLogOneLinePerTransfer() {
        val sha = "e1936f74b612" + "0".repeat(52)
        val cs = 4096L
        val size = 10 * cs - 100
        val label = BlobLabel(size, "Notes/x.bin")
        val log = ServeLog(idleMs = 30_000)
        for ((n, i) in listOf(0L, 1, 2, 3, 3, 4, 5, 6, 7, 8).withIndex()) {
            assertNull(log.record("towerx", sha, i, cs, label, cs, 1_000L + 100 * n))
        }
        log.record("hub", sha, 0, cs, label, cs, 1_000)
        log.record("hub", sha, 1, cs, label, cs, 2_000)
        assertEquals(
            "served blob e1936f74b612 (Notes/x.bin) to towerx: 10/10 chunks, ${10 * cs + 3996} B in 3.1 s",
            log.record("towerx", sha, 9, 3996, label, cs, 4_100),
        )
        assertTrue(log.sweep(20_000).isEmpty())
        assertEquals(
            listOf("served blob e1936f74b612 (Notes/x.bin) to hub: partial 2/10 chunks, 8192 B in 1.0 s, idle"),
            log.sweep(32_000),
        )
        assertEquals(0, log.openTransfers)
        assertEquals(
            "served blob e1936f74b612 to unknown: 1/1 chunks, 100 B in 0.0 s",
            log.record("unknown", sha, 0, 100, BlobLabel(100, null), cs, 0),
        )
    }

    @Test
    fun outgoingCacheLabels() {
        val c = OutgoingCache()
        c.insert("a".repeat(64), ByteArray(5000), "Daily/p.jpg")
        assertEquals(BlobLabel(5000, "Daily/p.jpg"), c.label("a".repeat(64)))
        assertNull(c.label("b".repeat(64)))
    }
}
