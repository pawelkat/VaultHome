package com.vaulthome.app.index

/**
 * EmbeddingGemma 2 prefixes locked in the VaultHome note.
 * Trim the payload first. Do not swap these for another prompt.
 */
object Prefixes {
    fun query(typed: String): String = "task: search result | query: ${typed.trim()}"

    fun chunk(title: String?, text: String): String {
        val body = text.trim()
        val label = title?.trim().orEmpty()
        val titleSlot = if (label.isEmpty() || label == "none") "none" else label
        return "title: $titleSlot | text: $body"
    }
}
