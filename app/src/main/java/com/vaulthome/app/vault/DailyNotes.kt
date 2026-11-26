package com.vaulthome.app.vault

import java.time.LocalDate
import java.time.ZoneId

/**
 * Picks the landing daily note. Today's path is [pathFor] (`Daily/yyyy-MM-dd.md`, calendar year and month-day —
 * never week-year `YYYY` or day-of-year `DD`). Future-dated files are never chosen.
 */
object DailyNotes {
    const val FOLDER = "Daily"
    private val name = Regex("""^(\d{4})-(\d{2})-(\d{2})\.md$""", RegexOption.IGNORE_CASE)

    /** Device-local calendar date (the phone's zone; on Pawel's devices that is Europe/Zurich). */
    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

    /** Canonical path for [day]: `Daily/2026-11-26.md`. Uses ISO `yyyy-MM-dd` (`LocalDate.toString()`). */
    fun pathFor(day: LocalDate, folder: String = FOLDER): String = "$folder/$day.md"

    fun dateOf(path: String): LocalDate? {
        val m = name.find(path.substringAfterLast('/')) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }

    /** Today's note if it exists in [paths]; otherwise the most recent past daily note. Never a future date. */
    data class Landing(val path: String, val date: LocalDate, val isToday: Boolean)

    fun landing(paths: Collection<String>, today: LocalDate, folder: String = FOLDER): Landing? {
        val preferred = pathFor(today, folder)
        val sameDay = paths.filter { dateOf(it) == today }
        val todayPath = when {
            preferred in sameDay -> preferred
            sameDay.isNotEmpty() -> best(sameDay)
            else -> null
        }
        if (todayPath != null) return Landing(todayPath, today, isToday = true)
        val past = latest(paths, today.minusDays(1)) ?: return null
        return Landing(past, dateOf(past)!!, isToday = false)
    }

    /** Ties on the same date prefer a path under a folder named like daily/journal, then the shallowest path. */
    fun latest(paths: Collection<String>, today: LocalDate): String? = paths
        .mapNotNull { p -> dateOf(p)?.takeIf { !it.isAfter(today) }?.let { p to it } }
        .sortedWith(
            compareByDescending<Pair<String, LocalDate>> { it.second }
                .thenByDescending { favoredFolder(it.first) }
                .thenBy { it.first.count { c -> c == '/' } }
                .thenBy { it.first },
        )
        .firstOrNull()?.first

    private fun best(sameDate: List<String>): String = sameDate.minWith(
        compareByDescending<String> { favoredFolder(it) }.thenBy { it.count { c -> c == '/' } }.thenBy { it },
    )

    private fun favoredFolder(path: String) = Regex("(?i)(^|/)(daily|journal|diary)[^/]*/").containsMatchIn(path)
}
