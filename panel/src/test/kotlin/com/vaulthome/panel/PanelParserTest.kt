package com.vaulthome.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelParserTest {
    @Test fun parsesAllSectionsOfRealNote() {
        val note = PanelParser.parse("2026-10-10", Samples.oct10)
        assertEquals("2026-10-10", note.date)
        assertEquals("Saturday 10 October 2026", note.title)
        assertEquals(listOf("Calendar", "Open loops", "Critical", "Weather"), note.sections.map { it.name })
        assertEquals(emptyList<String>(), note.calendar)
        assertEquals(listOf("19:00 — 15 min guitar", "Ricardo check — Kindle PW5/PW6, Jura, Galaxy Tab"), note.critical)
        assertEquals(2, note.openLoops.size)
        assertEquals("Clothes: rain jacket over a light layer, closed shoes", note.weather[1])
        assertTrue(note.out.isEmpty())
    }

    @Test fun noneAndNotAvailableAreEmpty() {
        val note = PanelParser.parse("2026-10-17", Samples.oct17)
        assertTrue(note.calendar.isEmpty())
        assertTrue(note.openLoops.isEmpty())
        assertTrue(note.critical.isEmpty())
        assertEquals(2, note.weather.size)
        val na = PanelParser.parse("2026-10-18", "## Weather\n- Not available\n## Critical\n- none.\n- `None`\n")
        assertTrue(na.weather.isEmpty())
        assertTrue(na.critical.isEmpty())
    }

    @Test fun wikilinksBecomePlainText() {
        val note = PanelParser.parse("2026-10-11", Samples.oct11)
        assertEquals(
            "Switch rclone shared client_id to private on S24 Ultra and Latitude 5300 before 2026 retirement — Reminder rclone private client_id",
            note.openLoops[1],
        )
        assertEquals("Alias", PanelParser.plain("[[Some note|Alias]]"))
        assertEquals("Note › Heading", PanelParser.plain("[[Folder/Note#Heading]]"))
        assertEquals("img.png", PanelParser.plain("![[img.png]]"))
        assertEquals("label and bold", PanelParser.plain("[label](https://x.y) and **bold**"))
    }

    @Test fun keepsOptionalOutAndUnknownSections() {
        val md = """
# Sunday 1 November 2026
## Critical
- [ ] Pay rent
## Out
- Pizol: 2°C at the top, wind 20 km/h
- Kit: helmet, harness, gloves
### detail heading ignored
## Extra
* something
  continued here
""".trimIndent()
        val note = PanelParser.parse("2026-11-01", md)
        assertEquals(listOf("Pay rent"), note.critical)
        assertEquals(2, note.out.size)
        assertEquals(listOf("something continued here"), note.items("Extra"))
    }

    @Test fun missingTitleFallsBackToDateAndCrlfWorks() {
        val note = PanelParser.parse("2026-10-20", "---\r\ntitle: x\r\n---\r\n## Critical\r\n- A\r\n")
        assertEquals("2026-10-20", note.title)
        assertEquals(listOf("A"), note.critical)
    }
}
