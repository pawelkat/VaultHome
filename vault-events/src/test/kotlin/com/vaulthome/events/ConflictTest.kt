package com.vaulthome.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictTest {
    private fun put(path: String, body: String, origin: String, version: Long, prev: String?, ts: Long) =
        NoteEvent.put(path, body.toByteArray(), origin, version, prev, ts).stamp

    @Test
    fun staleAncestorIsAFastForward() {
        val v1 = put("Notes/story.md", "v1\n", "box", 1, null, 10)
        val v2 = put("Notes/story.md", "v2\n", "box", 2, v1.sha256, 20)
        val v3 = put("Notes/story.md", "v3\n", "box", 3, v2.sha256, 30)
        val disk = v1.sha256
        // Without ancestors, v3.prev is v2, not v1: false conflict.
        val without = Conflict.decide(v1, disk, v3, "towerx", 40)
        assertTrue(without is Decision.Apply && (without as Decision.Apply).saveLocalAs != null)
        val with = Conflict.decide(v1, disk, v3, "towerx", 40, listOf(v2.sha256, v1.sha256))
        assertEquals(Decision.Apply(null), with)
        // Disk still v1 even if we have no recorded state.
        val noState = Conflict.decide(null, disk, v3, "towerx", 40, listOf(v2.sha256, v1.sha256))
        assertEquals(Decision.Apply(null), noState)
        // A unique local edit still conflicts.
        val dirty = sha256Hex("my edit".toByteArray())
        val lost = Conflict.decide(v1, dirty, v3, "towerx", 40, listOf(v2.sha256, v1.sha256))
        assertTrue(lost is Decision.Apply && (lost as Decision.Apply).saveLocalAs != null || lost is Decision.KeepLocal)
    }
}
