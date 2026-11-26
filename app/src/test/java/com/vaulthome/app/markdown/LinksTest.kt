package com.vaulthome.app.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    private fun urls(s: String) = MarkdownBlocks.inline(s).filter { it.url != null }.map { it.text to it.url }

    @Test fun markdownLink() = assertEquals(listOf("Video" to "https://youtu.be/abc"), urls("see [Video](https://youtu.be/abc) now"))
    @Test fun bareUrlTrimsPunctuation() = assertEquals(listOf("https://example.com/a" to "https://example.com/a"), urls("Go to https://example.com/a."))
    @Test fun bareUrlKeepsBalancedParens() = assertEquals("https://en.wikipedia.org/wiki/A_(b)", urls("x https://en.wikipedia.org/wiki/A_(b), y").single().second)
    @Test fun bareUrlInParens() = assertEquals("https://a.com/x", urls("(https://a.com/x)").single().second)
    @Test fun wwwNormalized() = assertEquals(listOf("www.youtube.com/watch?v=1" to "https://www.youtube.com/watch?v=1"), urls("www.youtube.com/watch?v=1"))
    @Test fun autolink() = assertEquals(listOf("https://a.com/p?q=1#f" to "https://a.com/p?q=1#f"), urls("<https://a.com/p?q=1#f>"))
    @Test fun urlWithHashAndUnderscoreNotTagOrItalic() {
        val spans = MarkdownBlocks.inline("https://a.com/x_y_z#frag")
        assertEquals(1, spans.size); assertFalse(spans[0].tag); assertFalse(spans[0].italic)
    }
    @Test fun listItemLinks() {
        val blocks = MarkdownBlocks.parse("## Links\n- https://youtu.be/xyz\n- [Talk](https://www.youtube.com/watch?v=q)\n")
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(listOf("https://youtu.be/xyz", "https://www.youtube.com/watch?v=q"), items.map { i -> i.text.first { it.url != null }.url })
    }
    @Test fun wikilinkUnchanged() {
        val s = MarkdownBlocks.inline("[[Note|alias]] and https://x.com").first()
        assertEquals("Note", s.wikiTarget); assertNull(s.url)
    }
    @Test fun youTubeHosts() {
        listOf("https://www.youtube.com/watch?v=1", "https://m.youtube.com/watch?v=1", "https://youtu.be/1",
            "https://music.youtube.com/watch?v=1", "https://www.youtube.com/shorts/abc", "www.youtube.com/watch?v=2", "https://YouTube.com/x")
            .forEach { assertTrue(it, Links.isYouTube(it)) }
        listOf("https://notyoutube.com/x", "https://youtube.com.evil.io/x", "https://vimeo.com/1", "https://example.com/?u=youtube.com")
            .forEach { assertFalse(it, Links.isYouTube(it)) }
    }
    @Test fun safeWww() { assertTrue(SafeLinks.isSafe("www.a.com")); assertFalse(SafeLinks.isSafe("javascript:alert(1)")) }
}
