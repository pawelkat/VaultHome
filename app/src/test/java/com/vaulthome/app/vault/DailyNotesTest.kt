package com.vaulthome.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DailyNotesTest {
    private val today = LocalDate.of(2026, 10, 12)

    @Test fun latestNotAfterToday() {
        val p = listOf("2026-10-10.md", "Daily/2026-10-12.md", "2026-10-13.md", "Notes/QiMap.md", "2026-09-30.md")
        assertEquals("Daily/2026-10-12.md", DailyNotes.latest(p, today))
    }

    @Test fun ignoresConflictCopiesAndInvalidDates() {
        val p = listOf("2026-10-08.md..conflict-Date1", "2026-02-30.md", "x2026-10-11.md", "2026-10-11.md")
        assertEquals("2026-10-11.md", DailyNotes.latest(p, today))
    }

    @Test fun tiePrefersDailyFolder() {
        assertEquals("Journal/2026-10-12.md", DailyNotes.latest(listOf("Archive/old/2026-10-12.md", "Journal/2026-10-12.md"), today))
    }

    @Test fun noneFound() {
        assertNull(DailyNotes.latest(listOf("note4.md", "2027-01-01.md"), today))
    }
}
