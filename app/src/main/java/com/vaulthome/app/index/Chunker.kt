package com.vaulthome.app.index

data class ChunkDraft(
    val heading: String,
    val text: String,
    val title: String,
)

object Chunker {
    private val heading = Regex("(?m)^(#{1,6})[ \\t]+(.+?)[ \\t]*$")
    private val frontmatterClose = Regex("(?m)^---[ \\t]*$")

    fun chunk(relativePath: String, markdown: String): List<ChunkDraft> {
        val fileTitle = fileTitle(relativePath)
        val body = stripFrontmatter(markdown).replace("\r\n", "\n").replace('\r', '\n')
        val matches = heading.findAll(body).toList()
        if (matches.isEmpty()) {
            return splitLong(title = fileTitle, heading = "", text = body)
        }
        val out = mutableListOf<ChunkDraft>()
        val preamble = body.substring(0, matches.first().range.first).trim()
        if (preamble.isNotEmpty()) {
            out += splitLong(title = fileTitle, heading = "", text = preamble)
        }
        for (i in matches.indices) {
            val headingText = matches[i].groupValues[2].trim()
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            val section = body.substring(start, end).trim()
            val title = headingText.ifBlank { fileTitle }
            if (section.isEmpty()) {
                if (headingText.isNotEmpty()) {
                    out += ChunkDraft(heading = headingText, text = headingText, title = title)
                }
            } else {
                out += splitLong(title = title, heading = headingText, text = section)
            }
        }
        return out
    }

    fun stripFrontmatter(text: String): String {
        if (!text.startsWith("---")) return text
        val newline = text.indexOf('\n')
        if (newline < 0) return text
        if (text.substring(0, newline).trim() != "---") return text
        val rest = text.substring(newline + 1)
        val close = frontmatterClose.find(rest) ?: return text
        return rest.substring(close.range.last + 1).trimStart('\n', '\r')
    }

    fun fileTitle(relativePath: String): String {
        val name = relativePath.substringAfterLast('/').substringAfterLast('\\')
        val stem = name.substringBeforeLast('.').trim()
        return stem.ifBlank { "none" }
    }

    private fun splitLong(title: String, heading: String, text: String): List<ChunkDraft> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= SearchConfig.MAX_CHUNK_CHARS) {
            return listOf(ChunkDraft(heading = heading, text = clean, title = title))
        }
        val parts = mutableListOf<String>()
        val buffer = StringBuilder()
        fun flush() {
            val piece = buffer.toString().trim()
            if (piece.isNotEmpty()) parts += piece
            buffer.clear()
        }
        for (paragraph in clean.split(Regex("\n{2,}"))) {
            val block = paragraph.trim()
            if (block.isEmpty()) continue
            if (block.length > SearchConfig.MAX_CHUNK_CHARS) {
                flush()
                var index = 0
                while (index < block.length) {
                    val end = minOf(index + SearchConfig.MAX_CHUNK_CHARS, block.length)
                    val slice = block.substring(index, end).trim()
                    if (slice.isNotEmpty()) parts += slice
                    index = end
                }
                continue
            }
            if (buffer.isNotEmpty() && buffer.length + 2 + block.length > SearchConfig.MAX_CHUNK_CHARS) {
                flush()
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(block)
        }
        flush()
        return parts.map { ChunkDraft(heading = heading, text = it, title = title) }
    }
}
