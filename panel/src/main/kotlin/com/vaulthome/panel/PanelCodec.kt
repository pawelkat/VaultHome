package com.vaulthome.panel

/**
 * Line-based text encoding of a [PanelNote] for the Data Layer payload and watch storage.
 * Pure Kotlin so the exact same code runs (and is tested) on both ends.
 *
 * ```
 * D<TAB>2026-10-17
 * T<TAB>Saturday 17 October 2026
 * S<TAB>Critical
 * I<TAB>19:00 — 15 min guitar
 * ```
 * Backslash, tab and newline inside values are escaped.
 */
object PanelCodec {
    fun encode(note: PanelNote): String = buildString {
        line('D', note.date)
        line('T', note.title)
        for (section in note.sections) {
            line('S', section.name)
            section.items.forEach { line('I', it) }
        }
    }

    fun decode(payload: String): PanelNote? {
        var date: String? = null
        var title = ""
        val sections = mutableListOf<PanelSection>()
        var name: String? = null
        var items = mutableListOf<String>()
        for (raw in payload.split('\n')) {
            if (raw.length < 2 || raw[1] != '\t') continue
            val value = unescape(raw.substring(2))
            when (raw[0]) {
                'D' -> date = value
                'T' -> title = value
                'S' -> {
                    name?.let { sections += PanelSection(it, items) }
                    name = value
                    items = mutableListOf()
                }
                'I' -> if (name != null) items += value
            }
        }
        name?.let { sections += PanelSection(it, items) }
        return date?.let { PanelNote(it, title.ifEmpty { it }, sections) }
    }

    private fun StringBuilder.line(kind: Char, value: String) {
        append(kind).append('\t').append(escape(value)).append('\n')
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
