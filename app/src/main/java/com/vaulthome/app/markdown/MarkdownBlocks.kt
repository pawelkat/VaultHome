package com.vaulthome.app.markdown

/**
 * Tiny Markdown subset parser (ported from QiMap 2990de1; pure Kotlin, no Compose ABI coupling).
 * Blocks: ATX headings, paragraphs, `-`/`*`/`+` and `1.` lists, `>` quotes, ``` fences, `---` rules.
 * Inline: **bold**, *italic* / _italic_, `code`, [text](url), Obsidian [[wikilink|alias]],
 * ![[embed]] and #tags. Anything else stays literal text.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: List<MdSpan>) : MdBlock
    data class Paragraph(val text: List<MdSpan>) : MdBlock
    data class ListItem(val marker: String, val depth: Int, val text: List<MdSpan>) : MdBlock
    data class Quote(val text: List<MdSpan>) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
    /** Image on its own line: Obsidian `![[a.png|300]]` or `![alt](src)`. [width] in dp, null = fit. */
    data class Image(val target: String, val alt: String, val width: Int? = null) : MdBlock
    /** GFM pipe table. [align] per column: -1 left (default), 0 center, 1 right. Rows are padded to header width. */
    data class Table(val header: List<List<MdSpan>>, val align: List<Int>, val rows: List<List<List<MdSpan>>>) : MdBlock
}

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
    /** Obsidian link target (`[[Note#Heading|alias]]` -> "Note"), for wikilinks and embeds. */
    val wikiTarget: String? = null,
    val embed: Boolean = false,
    val tag: Boolean = false,
)

/** Frontmatter split off a note: tags (from `tags:` list or inline list) and the remaining body. */
data class NoteParts(val tags: List<String>, val body: String)

object MarkdownBlocks {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    /** Strips a leading YAML frontmatter block (`---` ... `---`). Unterminated -> whole text is body. */
    fun splitFrontmatter(markdown: String): NoteParts {
        val text = markdown.replace("\r\n", "\n").removePrefix("\uFEFF")
        if (!text.startsWith("---\n")) return NoteParts(emptyList(), text)
        val end = Regex("(?m)^---[ \\t]*$").find(text, 4) ?: return NoteParts(emptyList(), text)
        val header = text.substring(4, end.range.first)
        val body = text.substring(end.range.last + 1).trimStart('\n')
        val tags = mutableListOf<String>()
        var inTags = false
        for (line in header.lines()) {
            val kv = Regex("^([A-Za-z0-9_\\-]+):\\s*(.*)$").find(line)
            if (kv != null) {
                inTags = kv.groupValues[1] == "tags"
                val v = kv.groupValues[2].trim()
                if (inTags && v.isNotEmpty()) {
                    tags += v.removePrefix("[").removeSuffix("]").split(',').map { it.trim().trim('"', '\'').removePrefix("#") }.filter { it.isNotEmpty() }
                }
            } else if (inTags) {
                Regex("^\\s*-\\s+(.*)$").find(line)?.let { tags += it.groupValues[1].trim().trim('"', '\'').removePrefix("#") }
            }
        }
        return NoteParts(tags, body)
    }

    fun parse(markdown: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        val quote = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) { out += MdBlock.Paragraph(inline(para.joinToString(" ") { it.trim() })); para.clear() }
            if (quote.isNotEmpty()) { out += MdBlock.Quote(inline(quote.joinToString(" ") { it.trim() })); quote.clear() }
        }
        val lines = markdown.replace("\r\n", "\n").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                    out += MdBlock.Code(code.joinToString("\n"))
                }
                line.isBlank() -> flush()
                Images.parseLine(line) != null -> { flush(); out += Images.parseLine(line)!! }
                i + 1 < lines.size && line.contains('|') && isSeparator(lines[i + 1]) &&
                    splitRow(line).size == splitRow(lines[i + 1]).size -> {
                    flush()
                    val header = splitRow(line)
                    val align = splitRow(lines[i + 1]).map { c ->
                        val l = c.startsWith(":"); val r = c.endsWith(":")
                        if (l && r) 0 else if (r) 1 else -1
                    }
                    i += 2
                    val rows = mutableListOf<List<List<MdSpan>>>()
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                        val cells = splitRow(lines[i])
                        rows += List(header.size) { inline(cells.getOrElse(it) { "" }) }
                        i++
                    }
                    out += MdBlock.Table(header.map { inline(it) }, align, rows)
                    continue
                }
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                heading.matches(line) -> {
                    flush(); val m = heading.find(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, inline(m.groupValues[2]))
                }
                line.trimStart().startsWith(">") -> {
                    if (para.isNotEmpty()) flush()
                    quote += line.trimStart().removePrefix(">")
                }
                bullet.matches(line) -> {
                    flush(); val m = bullet.find(line)!!
                    out += MdBlock.ListItem("•", m.groupValues[1].length / 2, inline(m.groupValues[3]))
                }
                ordered.matches(line) -> {
                    flush(); val m = ordered.find(line)!!
                    out += MdBlock.ListItem("${m.groupValues[2]}.", m.groupValues[1].length / 2, inline(m.groupValues[3]))
                }
                else -> { if (quote.isNotEmpty()) flush(); para += line }
            }
            i++
        }
        flush()
        return out
    }

    private val sepCell = Regex("^:?-{1,}:?$")

    /** Splits a pipe row into trimmed cells; honours `\\|` escapes and `|` inside [[a|b]] wikilinks. */
    fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var k = 0
        while (k < t.length) {
            val c = t[k]
            when {
                c == '\\' && k + 1 < t.length && t[k + 1] == '|' -> { cur.append('|'); k++ }
                t.startsWith("[[", k) -> { depth++; cur.append("[["); k++ }
                t.startsWith("]]", k) && depth > 0 -> { depth--; cur.append("]]"); k++ }
                c == '|' && depth == 0 -> { cells += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            k++
        }
        cells += cur.toString().trim()
        return cells
    }

    fun isSeparator(line: String): Boolean {
        if (!line.contains('-')) return false
        val cells = splitRow(line)
        return cells.isNotEmpty() && cells.all { sepCell.matches(it.replace(" ", "")) } && (line.contains('|') || cells.size > 1)
    }

    private val token = Regex("""(!?)\[\[([^\]|]+?)(?:\|([^\]]+))?]]|(?<![\p{L}\p{N}&/#])#([\p{L}_][\p{L}\p{N}_/\-]*)|\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)|\*([^*\s][^*]*?)\*|(?<![\p{L}\p{N}])_([^_\s][^_]*?)_(?![\p{L}\p{N}])""")

    fun inline(text: String, bold: Boolean = false, italic: Boolean = false): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        var pos = 0
        for (m in token.findAll(text)) {
            if (m.range.first > pos) out += MdSpan(text.substring(pos, m.range.first), bold, italic)
            val g = m.groupValues
            if (g[2].isNotEmpty()) {
                val target = g[2].substringBefore('#').trim()
                val label = g[3].ifEmpty { g[2] }.trim()
                out += MdSpan(label, bold, italic, wikiTarget = target.ifEmpty { null }, embed = g[1] == "!")
                pos = m.range.last + 1
                continue
            }
            if (g[4].isNotEmpty()) {
                out += MdSpan("#" + g[4], bold, italic, tag = true)
                pos = m.range.last + 1
                continue
            }
            when {
                g[5].isNotEmpty() -> out += inline(g[5], true, italic)
                g[6].isNotEmpty() -> out += inline(g[6], true, italic)
                g[7].isNotEmpty() -> out += MdSpan(g[7], bold, italic, code = true)
                g[8].isNotEmpty() -> out += inline(g[8], bold, italic).map { it.copy(url = g[9]) }
                g[10].isNotEmpty() -> out += inline(g[10], bold, true)
                g[11].isNotEmpty() -> out += inline(g[11], bold, true)
            }
            pos = m.range.last + 1
        }
        if (pos < text.length) out += MdSpan(text.substring(pos), bold, italic)
        return out
    }
}
