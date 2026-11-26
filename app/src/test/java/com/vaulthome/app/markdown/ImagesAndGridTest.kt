package com.vaulthome.app.markdown

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagesAndGridTest {
    @Test fun obsidianEmbedWithWidth() {
        val b = MarkdownBlocks.parse("![[Pasted image 2026.png|300]]").single() as MdBlock.Image
        assertEquals("Pasted image 2026.png", b.target); assertEquals(300, b.width)
    }

    @Test fun standardImagesLocalAndHttps() {
        val imgs = MarkdownBlocks.parse("![map](attachments/My%20map.jpg) ![x](https://e.com/a.png \"t\")").filterIsInstance<MdBlock.Image>()
        assertEquals("attachments/My map.jpg", imgs[0].target); assertEquals("map", imgs[0].alt)
        assertEquals("https://e.com/a.png", imgs[1].target)
    }

    @Test fun noteEmbedAndInlineImageStayText() {
        assertTrue(MarkdownBlocks.parse("![[Other note]]").single() is MdBlock.Paragraph)
        assertTrue(MarkdownBlocks.parse("see ![[a.png]] here").single() is MdBlock.Paragraph)
    }

    @Test fun resolvesAttachmentAnywhere() {
        val paths = listOf("Attachments/Photo.PNG", "Notes/img/photo.png", "x/diagram.jpg")
        assertEquals("Attachments/Photo.PNG", Images.resolve("photo.png", paths))
        assertEquals("Notes/img/photo.png", Images.resolve("img/photo.png", paths))
        assertEquals("x/diagram.jpg", Images.resolve("DIAGRAM.jpg", paths))
        assertNull(Images.resolve("missing.png", paths))
    }

    @Test fun sampleSize() {
        assertEquals(1, Images.sampleSize(1000, 800, 1080))
        assertEquals(4, Images.sampleSize(4000, 3000, 1000))
    }

    @Test fun sharedColumnWidths() {
        val w = TableWidths.columns(listOf(listOf(10, 500), listOf(80, 20), listOf(30)), 2, 48, 260)
        assertArrayEquals(intArrayOf(80, 260), w)
    }
}
