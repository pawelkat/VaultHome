package com.vaulthome.app.search

/** A chip in the overlay row; [color] indexes [TagChips.PALETTE_SIZE] palette slots. */
data class TagChip(val tag: String, val color: Int)

data class TagSuggestion(val tag: String, val score: Double, val relevant: Boolean)

/** Pure chip-row and tag-ranking rules (unit-tested). */
object TagChips {
    const val PALETTE_SIZE = 8
    val DEFAULTS = listOf(TagChip("journal", 0), TagChip("reminder", 1))

    fun normalize(tag: String) = tag.trim().removePrefix("#").lowercase()

    /** Adds [tag] with the first palette slot not used by the row (cycling when all are taken). No duplicates. */
    fun add(chips: List<TagChip>, tag: String): List<TagChip> {
        val t = normalize(tag)
        if (t.isEmpty() || chips.any { it.tag == t }) return chips
        val used = chips.map { it.color }.toSet()
        val color = (0 until PALETTE_SIZE).firstOrNull { it !in used } ?: (chips.size % PALETTE_SIZE)
        return chips + TagChip(t, color)
    }

    fun remove(chips: List<TagChip>, tag: String) = chips.filterNot { it.tag == normalize(tag) }

    fun encode(chips: List<TagChip>) = chips.joinToString("\n") { "${it.tag}\t${it.color}" }

    fun decode(s: String?): List<TagChip> {
        if (s == null) return DEFAULTS
        if (s.isEmpty()) return emptyList()
        return s.lines().mapNotNull { l ->
            val p = l.split('\t'); if (p[0].isEmpty()) null else TagChip(p[0], p.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }

    /**
     * Ranks tags for the picker. With a live query (>= 3 chars) and [hits] (path, score), a tag's score is the
     * sum of scores of hits carrying it; those come first. Everything else follows by vault frequency.
     * Already-added chips are excluded; [filter] is a case-insensitive substring match.
     */
    fun rank(
        frequencies: Map<String, Int>,
        hits: List<Pair<String, Float>>,
        tagsOf: (String) -> Set<String>,
        query: String,
        exclude: Set<String>,
        filter: String = "",
    ): List<TagSuggestion> {
        val rel = HashMap<String, Double>()
        if (SearchFilter.shouldSearch(query)) {
            hits.forEach { (path, score) -> tagsOf(path).forEach { rel[it] = (rel[it] ?: 0.0) + score } }
        }
        val f = normalize(filter)
        return (frequencies.keys + rel.keys).toSet()
            .filter { it !in exclude && (f.isEmpty() || f in it) }
            .map { t -> rel[t]?.let { TagSuggestion(t, it, true) } ?: TagSuggestion(t, (frequencies[t] ?: 0).toDouble(), false) }
            .sortedWith(compareByDescending<TagSuggestion> { it.relevant }.thenByDescending { it.score }.thenBy { it.tag })
    }
}
