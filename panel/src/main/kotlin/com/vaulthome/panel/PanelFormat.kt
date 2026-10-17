package com.vaulthome.panel

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text for the watch notification and tile. Pure functions so they are unit-tested on the JVM. */
object PanelFormat {
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** "Sat 17 Oct", or the raw date if it does not parse. */
    fun shortDate(date: String): String =
        runCatching { LocalDate.parse(date).format(shortDate) }.getOrDefault(date)

    fun notificationTitle(note: PanelNote): String = "Panel · ${shortDate(note.date)}"

    /** Collapsed notification: Critical, then the first Weather line. */
    fun collapsedText(note: PanelNote): String {
        val critical = note.critical
        val parts = mutableListOf<String>()
        parts += if (critical.isEmpty()) "Nothing critical" else "Critical: " + critical.joinToString("; ")
        note.weather.firstOrNull()?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** Expanded notification / full screen: every non-empty section, Critical first. */
    fun fullText(note: PanelNote): String {
        val ordered = orderedSections(note).filter { it.items.isNotEmpty() }
        if (ordered.isEmpty()) return "Nothing on the panel today."
        return ordered.joinToString("\n\n") { section ->
            section.name + "\n" + section.items.joinToString("\n") { "• $it" }
        }
    }

    /** Critical, Calendar, Weather, Out, Open loops, then anything else in file order. */
    fun orderedSections(note: PanelNote): List<PanelSection> {
        val order = listOf(PanelNote.CRITICAL, PanelNote.CALENDAR, PanelNote.WEATHER, PanelNote.OUT, PanelNote.OPEN_LOOPS)
        val known = order.mapNotNull(note::section)
        return known + note.sections.filter { s -> order.none { it.equals(s.name, ignoreCase = true) } }
    }

    /** Lines for the tile, which has room for roughly 6 short lines on a 480 px round screen. */
    data class TileText(
        val header: String,
        val critical: List<String>,
        val weather: String?,
        val calendar: List<String>,
        val stale: Boolean,
    )

    fun tile(note: PanelNote?, today: LocalDate, maxChars: Int = 40): TileText {
        if (note == null) {
            return TileText("Panel", listOf("Open VaultHome on the phone"), null, emptyList(), stale = true)
        }
        val stale = note.date != today.toString()
        val critical = note.critical.ifEmpty { listOf("Nothing critical") }
        val maxCritical = 2
        val criticalLines = critical.take(maxCritical).map { ellipsize(it, maxChars) }.toMutableList()
        if (critical.size > maxCritical) criticalLines[maxCritical - 1] = ellipsize(criticalLines.last(), maxChars - 6) + " (+${critical.size - maxCritical + 1})"
        // Calendar only fits when Critical is short.
        val calendarRoom = if (criticalLines.size >= 2) 1 else 2
        return TileText(
            header = shortDate(note.date) + if (stale) " (old)" else "",
            critical = criticalLines,
            weather = note.weather.firstOrNull()?.let { ellipsize(it, maxChars * 2) },
            calendar = note.calendar.take(calendarRoom).map { ellipsize(it, maxChars) },
            stale = stale,
        )
    }

    fun ellipsize(text: String, max: Int): String =
        if (max <= 1 || text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}
