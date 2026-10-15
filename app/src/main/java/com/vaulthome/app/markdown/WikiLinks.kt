package com.vaulthome.app.markdown

/**
 * Resolves an Obsidian wikilink target (`Note`, `Folder/Note`, `Note.md`, `Note#Heading`) to an
 * indexed vault path, case-insensitively. Exact relative path wins, then path suffix, then basename
 * (shortest path on ties, like Obsidian). Returns null when nothing matches.
 */
object WikiLinks {
    fun resolve(target: String, paths: Collection<String>): String? {
        val t = target.substringBefore('#').substringBefore('|').trim().trimStart('/')
            .removeSuffix(".md").removeSuffix(".markdown").lowercase()
        if (t.isEmpty()) return null
        fun key(p: String) = p.trimStart('/').removeSuffix(".md").removeSuffix(".markdown").lowercase()
        val sorted = paths.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it.length }, { it }))
        sorted.firstOrNull { key(it) == t }?.let { return it }
        sorted.firstOrNull { key(it).endsWith("/$t") }?.let { return it }
        return sorted.firstOrNull { key(it).substringAfterLast('/') == t.substringAfterLast('/') }
    }
}
