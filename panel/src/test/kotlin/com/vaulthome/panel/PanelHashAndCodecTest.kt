package com.vaulthome.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class PanelHashAndCodecTest {
    @Test fun codecRoundTripsIncludingEscapes() {
        val note = PanelNote(
            "2026-10-10",
            "Title\twith tab",
            listOf(PanelSection("Critical", listOf("a\\b", "line\nbreak")), PanelSection("Calendar", emptyList())),
        )
        assertEquals(note, PanelCodec.decode(PanelCodec.encode(note)))
        val real = PanelParser.parse("2026-10-10", Samples.oct10)
        assertEquals(real, PanelCodec.decode(PanelCodec.encode(real)))
        assertEquals(null, PanelCodec.decode("garbage"))
    }

    @Test fun hashIgnoresFormattingButSeesContent() {
        val a = PanelHash.of(PanelParser.parse("2026-10-10", Samples.oct10))
        val reformatted = Samples.oct10.replace("\n\n- ", "\n- ").replace("- 19:00", "-   19:00")
        val b = PanelHash.of(PanelParser.parse("2026-10-10", reformatted))
        assertEquals(a, b)
        assertEquals(64, a.length)
        val changed = PanelHash.of(PanelParser.parse("2026-10-10", Samples.oct10.replace("19:00", "19:30")))
        assertNotEquals(a, changed)
        assertNotEquals(a, PanelHash.of(PanelParser.parse("2026-10-11", Samples.oct10)))
    }

    @Test fun dedupeRules() {
        assertTrue(PanelDedupe.shouldPublish(null, "h1"))
        assertFalse(PanelDedupe.shouldPublish("h1", "h1"))
        assertTrue(PanelDedupe.shouldPublish("h1", "h1", force = true))
        assertTrue(PanelDedupe.shouldPublish("h1", "h2"))
        assertFalse(PanelDedupe.shouldPublish(null, ""))
        assertTrue(PanelDedupe.shouldNotify(null, "h1"))
        assertFalse(PanelDedupe.shouldNotify("h1", "h1"))
        assertTrue(PanelDedupe.shouldNotify("h1", "h2"))
        assertFalse(PanelDedupe.shouldNotify("h1", null))
    }

    @Test fun todayPathUsesZurichDate() {
        // 22:30 UTC on 17 Oct is already 18 Oct 00:30 in Zurich (CEST, UTC+2).
        val clock = Clock.fixed(Instant.parse("2026-10-17T22:30:00Z"), PanelPaths.ZONE)
        assertEquals(LocalDate.of(2026, 10, 18), PanelPaths.today(clock))
        assertEquals("Panel/2026-10-18.md", PanelPaths.pathFor(PanelPaths.today(clock)))
    }
}
