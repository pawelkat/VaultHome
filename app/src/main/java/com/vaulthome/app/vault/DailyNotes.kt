package com.vaulthome.app.vault

import java.time.LocalDate

/** Picks the landing daily note: filename `YYYY-MM-DD.md`, latest date <= today, anywhere in the vault. */
object DailyNotes {
    private val name = Regex("""^(\d{4})-(\d{2})-(\d{2})\.md$""", RegexOption.IGNORE_CASE)

    fun dateOf(path: String): LocalDate? {
        val m = name.find(path.substringAfterLast('/')) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }

    /** Ties on the same date prefer a path under a folder named like daily/journal, then the shallowest path. */
    fun latest(paths: Collection<String>, today: LocalDate): String? = paths
        .mapNotNull { p -> dateOf(p)?.takeIf { !it.isAfter(today) }?.let { p to it } }
        .sortedWith(
            compareByDescending<Pair<String, LocalDate>> { it.second }
                .thenByDescending { Regex("(?i)(^|/)(daily|journal|diary)[^/]*/").containsMatchIn(it.first) }
                .thenBy { it.first.count { c -> c == '/' } }
                .thenBy { it.first },
        )
        .firstOrNull()?.first
}
