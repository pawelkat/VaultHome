package com.vaulthome.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterTest {
    @Test fun minLengthGating() {
        assertFalse(SearchFilter.shouldSearch(""))
        assertFalse(SearchFilter.shouldSearch("ab"))
        assertFalse(SearchFilter.shouldSearch("  ab  "))
        assertTrue(SearchFilter.shouldSearch("abc"))
        assertTrue(SearchFilter.shouldSearch(" qi map "))
    }

    @Test fun extractsFrontmatterAndInlineTags() {
        val md = "---\ntags:\n  - Daily\n---\n# Title\nCall mum #reminder and #daily/2026.\n```\n#notatag\n```\nissue #12 `#code`"
        val tags = SearchFilter.extractTags(md)
        assertTrue("daily" in tags); assertTrue("reminder" in tags); assertTrue("daily/2026" in tags)
        assertFalse("notatag" in tags); assertFalse("code" in tags); assertFalse("12" in tags); assertFalse("title" in tags)
    }

    @Test fun inlineListFrontmatter() {
        assertEquals(setOf("reminder", "work"), SearchFilter.extractTags("---\ntags: [reminder, \"#work\"]\n---\nbody"))
    }

    @Test fun filterKeepsAnySelectedTag() {
        val tags = mapOf("a.md" to setOf("daily"), "b.md" to setOf("reminder"), "c.md" to emptySet())
        val items = listOf("a.md", "b.md", "c.md")
        assertEquals(items, SearchFilter.filter(items, emptySet(), { it }) { tags.getValue(it) })
        assertEquals(listOf("a.md"), SearchFilter.filter(items, setOf("daily"), { it }) { tags.getValue(it) })
        assertEquals(listOf("a.md", "b.md"), SearchFilter.filter(items, setOf("daily", "reminder"), { it }) { tags.getValue(it) })
    }
}
