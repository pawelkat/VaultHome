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
        assertFalse(md.contains("Answer from") || md.contains("qwen3"))
        assertTrue(md.lines().first().startsWith("*Q:"))
        assertEquals("Nitro · qwen3", AskMarkdown.sourceLabel(AskOutcome.Nitro("a", "qwen3", cites, null)))
        assertTrue(md.contains("Use GPIO 12."))
        // No on-device Sources footer under a Nitro answer, even if citations were passed.
        assertFalse(md.contains("### Sources") || md.contains("CrowPanel") || md.contains("No matching notes"))
    }

    @Test fun nitroOwnSourcesAreKeptVerbatim() {
        val answer = "Done.\n\nSources:\n- [2026-11-01](https://nitro5.tailf52603.ts.net/vault/Daily/2026-11-01.md)"
        val md = AskMarkdown.format("q", AskOutcome.Nitro(answer, null, emptyList(), null))
        assertTrue(md.contains("(https://nitro5.tailf52603.ts.net/vault/Daily/2026-11-01.md)"))
        assertEquals(1, Regex("Sources").findAll(md).count())
    }

    @Test fun localAndSearchOnlyAreMarkedLocal() {
        val local = AskMarkdown.format("q", AskOutcome.Local("a", NitroSkip.Down, "timeout", cites, null))
        assertEquals("On-device", AskMarkdown.sourceLabel(AskOutcome.Local("a", NitroSkip.Down, "timeout", cites, null)))
        assertTrue(local.contains("Nitro skipped: timeout"))
        val so = AskMarkdown.format("q", AskOutcome.SearchOnly(NitroSkip.Disabled, "Nitro is off", "No local model", emptyList(), null))
        assertEquals("Matching notes", AskMarkdown.sourceLabel(AskOutcome.SearchOnly(NitroSkip.Disabled, "x", "", emptyList(), null)))
        assertTrue(so.contains("No matching notes"))
    }

    @Test fun answerStartingWithRuleIsNotFrontmatter() {
        val md = AskMarkdown.format("", AskOutcome.Nitro("---\ntags: [x]\n---\nbody", null, emptyList(), null))
        val parts = MarkdownBlocks.splitFrontmatter(md)
        assertTrue(parts.tags.isEmpty())
        assertTrue(parts.body.contains("body"))
    }

    @Test fun modelPathIsShortened() {
        assertEquals("Qwen3-8B-Q4_K_M", AskMarkdown.shortModel("/home/pawelk/models/Qwen3-8B-Q4_K_M.gguf"))
        assertEquals(null, AskMarkdown.shortModel("  "))
        assertEquals("Nitro", AskMarkdown.sourceLabel(AskOutcome.Nitro("a", null, emptyList(), null)))
    }

    @Test fun answerStartsBodyAndLocalSourcesAreWikilinks() {
        assertTrue(AskMarkdown.format("", AskOutcome.Nitro("ok", "/m/q.gguf", cites, null)).startsWith("ok"))
        val md = AskMarkdown.format("", AskOutcome.Local("ok", NitroSkip.Down, "x", cites, null))
        val blocks = MarkdownBlocks.parse(md)
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
