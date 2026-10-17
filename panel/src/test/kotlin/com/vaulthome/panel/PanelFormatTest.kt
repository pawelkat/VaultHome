package com.vaulthome.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PanelFormatTest {
    private val oct10 = PanelParser.parse("2026-10-10", Samples.oct10)
    private val oct17 = PanelParser.parse("2026-10-17", Samples.oct17)

    @Test fun titleAndCollapsedText() {
        assertEquals("Panel · Sat 10 Oct", PanelFormat.notificationTitle(oct10))
        assertEquals(
            "Critical: 19:00 — 15 min guitar; Ricardo check — Kindle PW5/PW6, Jura, Galaxy Tab · " +
                "Zürich: high 13°C, low about 10°C, light rain, wind WSW about 11 km/h",
            PanelFormat.collapsedText(oct10),
        )
        assertTrue(PanelFormat.collapsedText(oct17).startsWith("Nothing critical · Zürich: morning 12°C"))
    }

    @Test fun fullTextListsNonEmptySectionsCriticalFirst() {
        val text = PanelFormat.fullText(oct10)
        assertTrue(text.startsWith("Critical\n• 19:00 — 15 min guitar\n"))
        assertFalse(text.contains("Calendar"))
        assertTrue(text.indexOf("Weather") < text.indexOf("Open loops"))
        assertEquals("Nothing on the panel today.", PanelFormat.fullText(PanelNote("2026-10-12", "x", emptyList())))
    }

    @Test fun tileTextFitsAndFlagsStale() {
        val t = PanelFormat.tile(oct10, LocalDate.of(2026, 10, 10))
        assertEquals("Sat 10 Oct", t.header)
        assertFalse(t.stale)
        assertEquals(2, t.critical.size)
        assertTrue(t.critical.all { it.length <= 40 })
        assertTrue(t.weather!!.startsWith("Zürich: high 13°C"))
        assertEquals(emptyList<String>(), t.calendar)

        val old = PanelFormat.tile(oct17, LocalDate.of(2026, 10, 18))
        assertTrue(old.stale)
        assertEquals("Sat 17 Oct (old)", old.header)
        assertEquals(listOf("Nothing critical"), old.critical)

        val none = PanelFormat.tile(null, LocalDate.of(2026, 10, 18))
        assertTrue(none.stale)
    }

    @Test fun tileCollapsesExtraCriticalItemsAndShowsCalendarWhenRoom() {
        val many = PanelNote(
            "2026-10-20", "t",
            listOf(
                PanelSection("Critical", listOf("one", "two", "three")),
                PanelSection("Calendar", listOf("09:00 Dentist", "12:00 Lunch", "18:00 Gym")),
            ),
        )
        val t = PanelFormat.tile(many, LocalDate.of(2026, 10, 20))
        assertEquals(listOf("one", "two (+2)"), t.critical)
        assertEquals(listOf("09:00 Dentist"), t.calendar)
        val few = PanelFormat.tile(many.copy(sections = listOf(many.sections[1])), LocalDate.of(2026, 10, 20))
        assertEquals(listOf("09:00 Dentist", "12:00 Lunch"), few.calendar)
        assertEquals("abcd…", PanelFormat.ellipsize("abcdefgh", 5))
    }
}
