package com.vaulthome.app.ask

import com.vaulthome.app.markdown.MarkdownBlocks
import com.vaulthome.app.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskMarkdownTest {
    private val cites = listOf(Citation("Projects/CrowPanel.md", "Wiring", "GPIO 12"), Citation("Devices/Nitro5.md", "", "x"))

    @Test fun nitroIsMarkedRemoteWithModel() {
        val md = AskMarkdown.format("What pins?", AskOutcome.Nitro("Use GPIO 12.", "qwen3", cites, null))
        assertTrue(md.lines().first().contains("Answer from Nitro (remote) · qwen3"))
        assertTrue(md.contains("Use GPIO 12."))
        assertTrue(md.contains("[[Projects/CrowPanel.md|Projects/CrowPanel.md › Wiring]]"))
        assertTrue(md.contains("[[Devices/Nitro5.md|Devices/Nitro5.md]]"))
    }

    @Test fun localAndSearchOnlyAreMarkedLocal() {
        val local = AskMarkdown.format("q", AskOutcome.Local("a", NitroSkip.Down, "timeout", cites, null))
        assertTrue(local.lines().first().contains("On-device answer (local)"))
        assertTrue(local.contains("Nitro skipped: timeout"))
        val so = AskMarkdown.format("q", AskOutcome.SearchOnly(NitroSkip.Disabled, "Nitro is off", "No local model", emptyList(), null))
        assertTrue(so.lines().first().contains("(local search)"))
        assertTrue(so.contains("No matching notes"))
    }

    @Test fun answerStartingWithRuleIsNotFrontmatter() {
        val md = AskMarkdown.format("", AskOutcome.Nitro("---\ntags: [x]\n---\nbody", null, emptyList(), null))
        val parts = MarkdownBlocks.splitFrontmatter(md)
        assertTrue(parts.tags.isEmpty())
        assertTrue(parts.body.contains("body"))
    }

    @Test fun headerRendersAsBoldParagraphAndSourcesAsWikilinks() {
        val md = AskMarkdown.format("why *now*?", AskOutcome.Nitro("ok", null, cites, null))
        val blocks = MarkdownBlocks.parse(md)
        val first = blocks.first() as MdBlock.Paragraph
        assertTrue(first.text.all { it.bold })
        val links = blocks.filterIsInstance<MdBlock.ListItem>().flatMap { it.text }.mapNotNull { it.wikiTarget }
        assertEquals(listOf("Projects/CrowPanel.md", "Devices/Nitro5.md"), links)
    }

    @Test fun oddPathsFallBackToCode() {
        assertEquals("`a#b.md`", AskMarkdown.citationLink(Citation("a#b.md", "", "")))
        assertFalse(AskMarkdown.escapeInline("x [[y]] *z*").contains("[["))
    }

    @Test fun crlfAndEmptyAnswers() {
        assertEquals("a\nb", AskMarkdown.body("a\r\nb\r\n"))
        assertEquals("*(empty answer)*", AskMarkdown.body("  "))
    }
}
