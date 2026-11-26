package com.vaulthome.app.search

import com.vaulthome.app.markdown.MarkdownBlocks

/** Pure search-overlay rules (unit-tested). */
object SearchFilter {
    const val MIN_QUERY = 3

    fun shouldSearch(query: String): Boolean = query.trim().length >= MIN_QUERY

    private val inlineTag = Regex("""(?<![\p{L}\p{N}&/#])#([\p{L}_][\p{L}\p{N}_/\-]*)""")

    /** Frontmatter tags + inline #tags (outside code fences), lowercased; `daily/2026` also yields `daily`. */
    fun extractTags(markdown: String): Set<String> {
        val parts = MarkdownBlocks.splitFrontmatter(markdown)
        val raw = parts.tags.toMutableList()
        var fence = false
        for (line in parts.body.lines()) {
            if (line.trimStart().startsWith("```")) { fence = !fence; continue }
            if (fence) continue
            val noCode = line.replace(Regex("`[^`]*`"), "")
            if (Regex("^\\s{0,3}#{1,6}\\s").containsMatchIn(noCode) && !noCode.trimStart().startsWith("#[")) {
                inlineTag.findAll(noCode.replaceFirst(Regex("^\\s*#{1,6}\\s"), "")).forEach { raw += it.groupValues[1] }
            } else inlineTag.findAll(noCode).forEach { raw += it.groupValues[1] }
        }
        return raw.flatMap { t ->
            val l = t.lowercase().trim().removePrefix("#")
            listOf(l, l.substringBefore('/'))
        }.filter { it.isNotEmpty() }.toSet()
    }

    /** Empty selection keeps everything; otherwise a hit stays if its note has ANY selected tag. */
    fun <T> filter(items: List<T>, selected: Set<String>, pathOf: (T) -> String, tagsOf: (String) -> Set<String>): List<T> =
        if (selected.isEmpty()) items else items.filter { tagsOf(pathOf(it)).any { t -> t in selected } }
}
