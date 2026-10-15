package com.vaulthome.app.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableAndWikiTest {
    private fun plain(spans: List<MdSpan>) = spans.joinToString("") { it.text }

    @Test fun parsesPipeTableWithAlignment() {
        val md = "| Name | Qty | Note |\n|:-----|----:|:---:|\n| **a** | 1 | [[X\\|y]] |\n| b | 2 |\n\nAfter"
        val blocks = MarkdownBlocks.parse(md)
        val t = blocks[0] as MdBlock.Table
        assertEquals(listOf("Name", "Qty", "Note"), t.header.map(::plain))
        assertEquals(listOf(-1, 1, 0), t.align)
        assertEquals(2, t.rows.size)
        assertTrue(t.rows[0][0].single().bold)
        assertEquals(3, t.rows[1].size)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    @Test fun wikilinkPipeInsideCellIsNotASplit() {
        val t = MarkdownBlocks.parse("a | b\n--- | ---\n[[Note|alias]] | `c`")[0] as MdBlock.Table
        val cell = t.rows[0][0].single()
        assertEquals("alias", cell.text)
        assertEquals("Note", cell.wikiTarget)
        assertTrue(t.rows[0][1].single().code)
    }

    @Test fun pipeTextWithoutSeparatorIsParagraph() {
        assertTrue(MarkdownBlocks.parse("a | b\nc | d")[0] is MdBlock.Paragraph)
        assertTrue(MarkdownBlocks.parse("a | b\n---")[0] !is MdBlock.Table)
    }

    @Test fun headingTargetStripped() {
        assertEquals("Note", MarkdownBlocks.inline("[[Note#Sec|x]]").single().wikiTarget)
    }

    private val paths = listOf("Notes/note4c.md", "note4.md", "Projects/QiMap.md", "Archive/QiMap.md", "Daily/2026-10-10.md")

    @Test fun resolvesByBasenameCaseInsensitive() {
        assertEquals("Notes/note4c.md", WikiLinks.resolve("NOTE4C", paths))
        assertEquals("note4.md", WikiLinks.resolve("note4", paths))
        assertEquals("Daily/2026-10-10.md", WikiLinks.resolve("2026-10-10#Log", paths))
    }

    @Test fun resolvesPathsAndExtension() {
        assertEquals("Archive/QiMap.md", WikiLinks.resolve("archive/qimap.md", paths))
        assertEquals("Projects/QiMap.md", WikiLinks.resolve("Projects/QiMap", paths))
    }

    @Test fun unresolvedIsNull() {
        assertNull(WikiLinks.resolve("Missing", paths))
        assertNull(WikiLinks.resolve("#only-heading", paths))
    }
}
