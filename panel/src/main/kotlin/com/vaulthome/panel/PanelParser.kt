package com.vaulthome.panel

/**
 * Parses the Markdown written by `/panel-daily` (see vault note "Panel daily"):
 * YAML frontmatter, `# Weekday D Month YYYY`, then `## Calendar`, `## Open loops`, `## Critical`,
 * `## Weather` and an optional `## Out`, each a bullet list.
 *
 * `None` / `Not available` items count as empty. `[[wikilinks]]` become plain text.
 * Unknown `##` sections are kept, in file order.
 */
object PanelParser {
    private val emptyMarkers = setOf("none", "not available", "n/a", "-", "—")
    private val bullet = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(?:\[[ xX]\]\s+)?(.*)$""")
    private val wikilink = Regex("""!?\[\[([^\]|]+?)(?:\|([^\]]+))?]]""")
    private val mdLink = Regex("""\[([^\]]+)]\((?:[^)]+)\)""")
    private val emphasis = Regex("""(\*\*|__)(.+?)\1""")
    private val tag = Regex("""(^|\s)#\p{L}[\p{L}\p{N}_/-]*""")
    private val deeperHeading = Regex("""^\s{0,3}#{3,6}\s""")

    fun parse(date: String, markdown: String): PanelNote {
        val lines = stripFrontmatter(markdown.replace("\r\n", "\n").replace('\r', '\n')).lines()
        var title = ""
        val sections = mutableListOf<PanelSection>()
        var name: String? = null
        var items = mutableListOf<String>()

        fun flush() {
            val current = name ?: return
            sections += PanelSection(current, items.filterNot(::isEmptyMarker))
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> {
                    flush()
                    name = plain(line.removePrefix("## ").trim().trimEnd('#').trim())
                    items = mutableListOf()
                }
                line.startsWith("# ") && name == null && title.isEmpty() -> title = plain(line.removePrefix("# ").trim())
                name == null || line.isBlank() -> Unit
                deeperHeading.containsMatchIn(line) -> Unit
                else -> {
                    val m = bullet.matchEntire(line)
                    val text = plain(m?.groupValues?.get(1) ?: line.trim())
                    when {
                        text.isEmpty() -> Unit
                        m == null && items.isNotEmpty() && raw.startsWith(" ") -> items[items.lastIndex] = items.last() + " " + text
                        else -> items += text
                    }
                }
            }
        }
        flush()
        return PanelNote(date = date, title = title.ifEmpty { date }, sections = sections)
    }

    /** Plain text: wikilinks → alias or note name, Markdown links → label, no bold markers or `#tags`. */
    fun plain(text: String): String {
        var s = wikilink.replace(text) { m ->
            m.groupValues[2].ifEmpty {
                val target = m.groupValues[1]
                val note = target.substringBefore('#').substringAfterLast('/').removeSuffix(".md")
                val heading = target.substringAfter('#', "")
                when {
                    note.isEmpty() -> heading
                    heading.isEmpty() -> note
                    else -> "$note › $heading"
                }
            }
        }
        s = mdLink.replace(s) { it.groupValues[1] }
        s = emphasis.replace(s) { it.groupValues[2] }
        s = tag.replace(s) { it.groupValues[1] }
        s = s.replace("`", "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    fun isEmptyMarker(item: String): Boolean = item.trim().trimEnd('.').lowercase() in emptyMarkers

    private fun stripFrontmatter(text: String): String {
        val body = text.trimStart('\uFEFF')
        if (!body.startsWith("---\n") && body != "---") return body
        val end = Regex("""\n(?:---|\.\.\.)[ \t]*(?:\n|$)""").find(body, 3) ?: return body
        return body.substring(end.range.last + 1)
    }
}
