package com.vaulthome.panel

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Where the Grok `/panel-daily` run writes the note: `Panel/YYYY-MM-DD.md`, dated in Europe/Zurich. */
object PanelPaths {
    val ZONE: ZoneId = ZoneId.of("Europe/Zurich")
    const val FOLDER = "Panel"

    fun today(clock: Clock = Clock.system(ZONE)): LocalDate = LocalDate.now(clock.withZone(ZONE))

    fun pathFor(date: LocalDate): String = "$FOLDER/$date.md"
}
