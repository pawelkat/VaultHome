package com.vaulthome.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyNotesTest {
    private val today = LocalDate.of(2026, 10, 12)

    @Test fun pathForUsesIsoCalendarDateNotWeekYear() {
        // 2026-11-26 is a Thursday; a YYYY (week-year) / DD (day-of-year) bug must not produce another filename.
        assertEquals("Daily/2026-11-26.md", DailyNotes.pathFor(LocalDate.of(2026, 11, 26)))
        assertEquals("Daily/2026-10-12.md", DailyNotes.pathFor(today))
    }

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

    @Test fun landingPrefersTodaysDailyNoteOverOlderIndexedFiles() {
        // Bug: the start page used only the (stale) index, which still had Daily/2026-11-23.md as its newest file
        // on 2026-11-26, and never looked on disk for Daily/2026-11-26.md.
        val today = LocalDate.of(2026, 11, 26)
        val index = listOf("Daily/2026-11-22.md", "Daily/2026-11-23.md", "Notes/QiMap.md")
        assertEquals("Daily/2026-11-23.md", DailyNotes.landing(index, today)!!.path)
        assertFalse(DailyNotes.landing(index, today)!!.isToday)
        val withDisk = index + "Daily/2026-11-26.md"
        val hit = DailyNotes.landing(withDisk, today)!!
        assertEquals("Daily/2026-11-26.md", hit.path)
        assertTrue(hit.isToday)
        assertEquals(today, hit.date)
    }

    @Test fun landingNeverPicksAFutureNote() {
        val today = LocalDate.of(2026, 11, 26)
        val p = listOf("Daily/2026-11-23.md", "Daily/2026-12-23.md", "Daily/2026-11-27.md")
        val hit = DailyNotes.landing(p, today)!!
        assertEquals("Daily/2026-11-23.md", hit.path)
        assertFalse(hit.isToday)
    }

    @Test fun landingEmptyWhenOnlyFutureNotesExist() {
        val today = LocalDate.of(2026, 11, 26)
        assertNull(DailyNotes.landing(listOf("Daily/2026-11-27.md", "Daily/2026-12-23.md"), today))
    }

    @Test fun landingPrefersCanonicalDailyPathForToday() {
        val today = LocalDate.of(2026, 11, 26)
        val p = listOf("Archive/2026-11-26.md", "Daily/2026-11-26.md", "Daily/2026-11-23.md")
        assertEquals("Daily/2026-11-26.md", DailyNotes.landing(p, today)!!.path)
    }

    @Test fun todayUsesTheGivenZone() {
        val zurich = ZoneId.of("Europe/Zurich")
        assertEquals(LocalDate.now(zurich), DailyNotes.today(zurich))
    }
}
