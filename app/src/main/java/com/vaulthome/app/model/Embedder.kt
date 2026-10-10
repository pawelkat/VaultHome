package com.vaulthome.app.model

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.vaulthome.app.index.SearchConfig
import com.vaulthome.app.index.Vectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Embedder(private val models: ModelStore) {
    private val lock = Any()
    private var engine: EmbeddingEngine? = null

    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        synchronized(lock) {
            val current = engine ?: open()
            val response = current.computeEmbedding(
                listOf(InputData.Text(text)),
                EmbeddingOptions(normalize = true, outputSize = SearchConfig.VECTOR_DIM),
            )
            Vectors.fitDim(response.embedding)
        }
    }

    private fun open(): EmbeddingEngine {
        val file = models.modelFile()
        check(file.isFile) { "Embedding model is not on disk" }
        val created = EmbeddingEngine(
            EmbeddingEngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU(),
                cacheDir = models.cacheDir().absolutePath,
                maxInputLength = SearchConfig.MAX_INPUT_TOKENS,
            ),
        )
        created.initialize()
        engine = created
        return created
    }
}
