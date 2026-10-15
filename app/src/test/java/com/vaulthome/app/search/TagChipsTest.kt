package com.vaulthome.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagChipsTest {
    @Test fun defaultsAreJournalAndReminder() {
        assertEquals(listOf("journal", "reminder"), TagChips.decode(null).map { it.tag })
        assertTrue(TagChips.decode("").isEmpty())
    }

    @Test fun addNormalizesDedupesAndPicksFreeColor() {
        var c = TagChips.DEFAULTS
        c = TagChips.add(c, "#Health")
        assertEquals(TagChip("health", 2), c.last())
        assertEquals(c, TagChips.add(c, "health"))
        c = TagChips.remove(c, "journal")
        assertEquals(TagChip("work", 0), TagChips.add(c, "work").last())
    }

    @Test fun removeAndRoundTrip() {
        val c = TagChips.add(TagChips.DEFAULTS, "x")
        val r = TagChips.remove(c, "reminder")
        assertFalse(r.any { it.tag == "reminder" })
        assertEquals(r, TagChips.decode(TagChips.encode(r)))
        assertTrue(TagChips.decode(TagChips.encode(emptyList())).isEmpty())
    }

    private val freq = mapOf("journal" to 50, "esp32" to 3, "android" to 10, "rust" to 1)
    private val tags = mapOf("a.md" to setOf("esp32"), "b.md" to setOf("esp32", "rust"), "c.md" to setOf("android"))

    @Test fun rankByFrequencyWithoutQuery() {
        val r = TagChips.rank(freq, listOf("a.md" to 0.9f), { tags.getValue(it) }, "ab", setOf("journal"))
        assertEquals(listOf("android", "esp32", "rust"), r.map { it.tag })
        assertFalse(r.any { it.relevant })
    }

    @Test fun rankBySummedHitScoreWithQuery() {
        val hits = listOf("a.md" to 0.5f, "b.md" to 0.4f, "c.md" to 0.8f)
        val r = TagChips.rank(freq, hits, { tags.getValue(it) }, "board", emptySet())
        assertEquals(listOf("esp32", "android", "rust", "journal"), r.map { it.tag })
        assertEquals(0.9, r[0].score, 1e-6)
        assertFalse(r.last().relevant)
    }

    @Test fun filterSubstring() {
        assertEquals(listOf("rust"), TagChips.rank(freq, emptyList(), { emptySet() }, "", emptySet(), "#RU").map { it.tag })
    }
}
