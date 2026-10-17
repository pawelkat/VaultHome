package com.vaulthome.wear

import com.vaulthome.panel.PanelFormat
import com.vaulthome.panel.PanelNote
import com.vaulthome.panel.PanelSection
import com.vaulthome.wear.tile.PanelTileLayout
import com.vaulthome.wear.tile.PanelTileLayout.Style
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PanelTileLayoutTest {
    private val today = LocalDate.of(2026, 10, 10)

    @Test fun rowsInOrderWithStyles() {
        val note = PanelNote(
            "2026-10-10", "Saturday 10 October 2026",
            listOf(
                PanelSection("Calendar", listOf("09:00 Dentist")),
                PanelSection("Critical", listOf("19:00 — 15 min guitar")),
                PanelSection("Weather", listOf("Zürich: high 13°C", "Clothes: rain jacket")),
            ),
        )
        val rows = PanelTileLayout.rows(PanelFormat.tile(note, today))
        assertEquals(
            listOf(
                PanelTileLayout.Row("Sat 10 Oct", Style.HEADER),
                PanelTileLayout.Row("19:00 — 15 min guitar", Style.CRITICAL),
                PanelTileLayout.Row("Zürich: high 13°C", Style.BODY, maxLines = 2),
                PanelTileLayout.Row("09:00 Dentist", Style.MUTED),
            ),
            rows,
        )
    }

    @Test fun emptyPanelShowsNothingCriticalMuted() {
        val rows = PanelTileLayout.rows(PanelFormat.tile(PanelNote("2026-10-10", "t", emptyList()), today))
        assertEquals(listOf("Sat 10 Oct", "Nothing critical"), rows.map { it.text })
        assertEquals(Style.MUTED, rows[1].style)
    }

    @Test fun noDataYet() {
        val rows = PanelTileLayout.rows(PanelFormat.tile(null, today))
        assertEquals(listOf("Panel", "Open VaultHome on the phone"), rows.map { it.text })
    }
}
