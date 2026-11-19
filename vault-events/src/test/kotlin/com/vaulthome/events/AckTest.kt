package com.vaulthome.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AckTest {
    @Test
    fun keysRoundTripAndRejectJunk() {
        val sha = "ab".repeat(32)
        assertEquals(64, sha.length)
        val k = Ack.key(sha, "towerx")
        assertEquals("vault/ack/$sha/towerx", k)
        assertEquals(sha to "towerx", Ack.parseKey(k))
        assertEquals(sha to "Box", Ack.parseKey(Ack.key(sha.uppercase(), "Box")))
        assertNull(Ack.parseKey("vault/notes/x"))
        assertNull(Ack.parseKey("vault/ack/notasha/box"))
        assertNull(Ack.parseKey("vault/ack/$sha/a/b"))
    }

    @Test
    fun logLineOkAndFail() {
        assertEquals(
            "box received Notes/x.md (123) ok in 1.4s",
            Ack.logLine("box", TransferAck.ok("Notes/x.md", 123, 1400)),
        )
        assertEquals(
            "s24 received Notes/x.md (123) fail: sha256 mismatch",
            Ack.logLine("s24", TransferAck.fail("Notes/x.md", 123, 500, "sha256 mismatch")),
        )
        assertEquals("0.0s", Ack.fmtSecs(0))
        assertEquals("0.1s", Ack.fmtSecs(50))
    }

    @Test
    fun wireRoundTrip() {
        val ack = TransferAck.ok("Daily/a.md", 4, 12)
        assertEquals(ack, TransferAck.decode(ack.encode()))
        val raw = """{"status":"fail","reason":"timeout","path":"a.bin","bytes":10,"duration_ms":2000}"""
        val f = TransferAck.decode(raw.toByteArray())
        assertEquals(AckStatus.FAIL, f.status)
        assertEquals("timeout", f.reason)
        assertEquals(10L, f.bytes)
        assertEquals(2000L, f.durationMs)
        // Rust-shaped payload without reason.
        val ok = TransferAck.decode("""{"status":"ok","path":"Notes/x.md","bytes":123,"duration_ms":400}""".toByteArray())
        assertEquals(TransferAck.ok("Notes/x.md", 123, 400), ok)
    }
}
