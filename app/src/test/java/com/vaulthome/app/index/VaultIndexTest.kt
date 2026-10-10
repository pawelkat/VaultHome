package com.vaulthome.app.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultIndexTest {
    @Test
    fun prefixesMatchTheLockedPrompt() {
        assertEquals(
            "task: search result | query: morning pages",
            Prefixes.query("  morning pages  "),
        )
        assertEquals(
            "title: Swim notes | text: 1500m easy",
            Prefixes.chunk("Swim notes", "  1500m easy  "),
        )
        assertEquals(
            "title: none | text: orphan line",
            Prefixes.chunk("none", "orphan line"),
        )
        assertEquals(
            "title: none | text: orphan line",
            Prefixes.chunk(null, "orphan line"),
        )
    }

    @Test
    fun chunkerDropsFrontmatterAndSplitsOnHeadings() {
        val markdown = """
            ---
            title: Day
            created: 2026-10-10
            ---
            Preamble stays.
            # First
            Alpha
            ## Second
            Beta
        """.trimIndent()
        val chunks = Chunker.chunk("Notes/Day.md", markdown)
        assertEquals(listOf("", "First", "Second"), chunks.map { it.heading })
        assertEquals("Preamble stays.", chunks[0].text)
        assertEquals("Day", chunks[0].title)
        assertEquals("Alpha", chunks[1].text)
        assertEquals("First", chunks[1].title)
        assertTrue(chunks.none { it.text.contains("created:") })
    }

    @Test
    fun longSectionSplitsNearTheCharBudget() {
        val paragraph = "word ".repeat(600).trim()
        val chunks = Chunker.chunk("Notes/Long.md", "# H\n\n$paragraph\n\n$paragraph")
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= SearchConfig.MAX_CHUNK_CHARS })
        assertTrue(chunks.all { it.title == "H" })
    }

    @Test
    fun planSkipsUnchangedNotesAndDropsMissingOnes() {
        val plan = planIndex(
            existingMtime = mapOf("a.md" to 1L, "b.md" to 2L, "gone.md" to 3L),
            scannedMtime = mapOf("a.md" to 1L, "b.md" to 9L, "c.md" to 4L),
        )
        assertEquals(listOf("b.md", "c.md"), plan.changed)
        assertEquals(listOf("gone.md"), plan.removed)
    }

    @Test
    fun cosineRanksAMatchingVectorFirst() {
        val query = floatArrayOf(1f, 0f, 0f)
        val match = floatArrayOf(0.8f, 0.1f, 0f)
        val other = floatArrayOf(0f, 1f, 0f)
        assertTrue(Vectors.cosine(query, match) > Vectors.cosine(query, other))
        assertEquals(1f, Vectors.cosine(query, query), 0.0001f)
    }

    @Test
    fun vectorRoundTripKeepsTheLeadingDimension() {
        val values = FloatArray(300) { index -> index.toFloat() }
        val fitted = Vectors.fitDim(values)
        assertEquals(SearchConfig.VECTOR_DIM, fitted.size)
        val restored = Vectors.bytesToFloats(Vectors.floatsToBytes(fitted))
        assertEquals(fitted[0], restored[0], 0f)
        assertEquals(fitted[255], restored[255], 0f)
    }

    @Test
    fun obsidianUrisEncodeSpacesAndKeepSlashes() {
        assertEquals(
            "obsidian://open?vault=Vault&file=Notes/Some%20note",
            ObsidianLinks.vaultFileUri("Notes/Some note.md"),
        )
        assertEquals(
            "obsidian://open?path=/sdcard/DriveSyncFiles/Vault/Notes/Some%20note.md",
            ObsidianLinks.pathFallbackUri("/sdcard/DriveSyncFiles/Vault/Notes/Some note.md"),
        )
    }
}
