package com.vaulthome.app.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {
    @Test fun parsesSubset() {
        val b = MarkdownBlocks.parse("# T\n\n> quote x\n\npara *cun* and **LI4 Hegu**: y\n\n## Points\n\n- **LI4**: a [w](https://e.org)\n1. one\n---\n```\ncode\n```")
        assertEquals(MdBlock.Heading(1, listOf(MdSpan("T"))), b[0])
        assertTrue(b[1] is MdBlock.Quote)
        val p = (b[2] as MdBlock.Paragraph).text
        assertTrue(p.any { it.italic && it.text == "cun" } && p.any { it.bold && it.text == "LI4 Hegu" })
        assertEquals("https://e.org", (b[4] as MdBlock.ListItem).text.last().url)
        assertEquals("1.", (b[5] as MdBlock.ListItem).marker)
        assertEquals(MdBlock.Rule, b[6]); assertEquals(MdBlock.Code("code"), b[7])
    }

    @Test fun obsidianWikilinksEmbedsAndTags() {
        val s = MarkdownBlocks.inline("See [[S24 Ultra]], [[VaultHome provisioning#Index|index]] and ![[photo.jpg]] #idea #grok-build, not a#b or https://x.org/#frag")
        val wiki = s.filter { it.wikiTarget != null }
        assertEquals(listOf("S24 Ultra", "VaultHome provisioning", "photo.jpg"), wiki.map { it.wikiTarget })
        assertEquals(listOf("S24 Ultra", "index", "photo.jpg"), wiki.map { it.text })
        assertEquals(listOf(false, false, true), wiki.map { it.embed })
        assertEquals(listOf("#idea", "#grok-build"), s.filter { it.tag }.map { it.text })
        assertTrue(s.none { "[[" in it.text })
    }

    @Test fun tagLineIsNotAHeading() {
        val b = MarkdownBlocks.parse("#idea #project\n\n# Real heading")
        assertTrue(b[0] is MdBlock.Paragraph)
        assertTrue(b[1] is MdBlock.Heading)
    }

    @Test fun frontmatterSplit() {
        val n = MarkdownBlocks.splitFrontmatter("---\ntitle: X\ntags:\n  - idea\n  - project\nstatus: done\n---\n\n# X\nbody")
        assertEquals(listOf("idea", "project"), n.tags)
        assertEquals("# X\nbody", n.body)
        assertEquals(listOf("a", "b"), MarkdownBlocks.splitFrontmatter("---\ntags: [a, \"#b\"]\n---\nz").tags)
        assertEquals("no fm", MarkdownBlocks.splitFrontmatter("no fm").body)
        assertEquals("---\nunterminated", MarkdownBlocks.splitFrontmatter("---\nunterminated").body)
        assertEquals("b", MarkdownBlocks.splitFrontmatter("---\r\nx: 1\r\n---\r\nb").body)
    }

    @Test fun onlyHttpsLinksAreSafe() {
        assertTrue(SafeLinks.isSafe("https://obsidian.md/help/uri"))
        listOf("http://x.org", "file:///sdcard/a", "content://x/y", "javascript:alert(1)", "obsidian://open?vault=Vault",
            "intent://x#Intent;end", "https://user@evil.com/", "Notes/a.md", "").forEach { assertFalse(it, SafeLinks.isSafe(it)) }
    }
}
