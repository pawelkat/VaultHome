package com.vaulthome.app.search

import com.vaulthome.app.index.ChunkDao
import com.vaulthome.app.index.Prefixes
import com.vaulthome.app.index.SearchConfig
import com.vaulthome.app.index.Vectors
import com.vaulthome.app.model.Embedder

data class SearchHit(
    val path: String,
    val heading: String,
    val snippet: String,
    val score: Float,
)

class Searcher(
    private val dao: ChunkDao,
    private val embedder: Embedder,
) {
    suspend fun search(typed: String, limit: Int = SearchConfig.TOP_K): List<SearchHit> {
        val query = typed.trim()
        if (query.isEmpty()) return emptyList()
        val vector = embedder.embed(Prefixes.query(query))
        return dao.allChunks()
            .map { row ->
                SearchHit(
                    path = row.path,
                    heading = row.heading,
                    snippet = snippet(row.chunkText),
                    score = Vectors.cosine(vector, Vectors.bytesToFloats(row.vector)),
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun snippet(text: String): String {
        return text.replace(Regex("\\s+"), " ").take(180)
    }
}
