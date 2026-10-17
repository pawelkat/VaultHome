package com.vaulthome.wear.tile

import com.vaulthome.panel.PanelFormat

/** Ordered tile rows with a style hint; pure so it is unit-tested without the tile renderer. */
object PanelTileLayout {
    enum class Style { HEADER, CRITICAL, BODY, MUTED }

    data class Row(val text: String, val style: Style, val maxLines: Int = 1)

    fun rows(text: PanelFormat.TileText): List<Row> = buildList {
        add(Row(text.header, Style.HEADER))
        text.critical.forEach { add(Row(it, if (it == "Nothing critical") Style.MUTED else Style.CRITICAL)) }
        text.weather?.let { add(Row(it, Style.BODY, maxLines = 2)) }
        text.calendar.forEach { add(Row(it, Style.MUTED)) }
    }
}
