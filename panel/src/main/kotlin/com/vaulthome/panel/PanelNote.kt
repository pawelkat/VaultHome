package com.vaulthome.panel

/** One `## Heading` block of a Panel note. [items] are plain text: no bullets, no wikilinks, no `None`. */
data class PanelSection(val name: String, val items: List<String>)

/**
 * Parsed `Panel/YYYY-MM-DD.md`. [date] is the ISO date from the file name, [title] the H1
 * (e.g. "Saturday 17 October 2026"), [sections] in file order.
 */
data class PanelNote(
    val date: String,
    val title: String,
    val sections: List<PanelSection>,
) {
    fun section(name: String): PanelSection? = sections.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun items(name: String): List<String> = section(name)?.items.orEmpty()

    val calendar: List<String> get() = items(CALENDAR)
    val openLoops: List<String> get() = items(OPEN_LOOPS)
    val critical: List<String> get() = items(CRITICAL)
    val weather: List<String> get() = items(WEATHER)
    val out: List<String> get() = items(OUT)

    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }

    companion object {
        const val CALENDAR = "Calendar"
        const val OPEN_LOOPS = "Open loops"
        const val CRITICAL = "Critical"
        const val WEATHER = "Weather"
        const val OUT = "Out"
    }
}
