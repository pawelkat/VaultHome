package com.vaulthome.app.index

import com.vaulthome.app.model.Embedder
import com.vaulthome.app.model.ModelStore
import com.vaulthome.app.vault.VaultReader
import com.vaulthome.app.vault.VaultStore

class Indexer(
    private val store: VaultStore,
    private val reader: VaultReader,
    private val dao: ChunkDao,
    private val models: ModelStore,
    private val embedder: Embedder,
    private val status: IndexStatusStore,
    private val tags: TagIndex,
) {
    suspend fun indexAll() {
        if (!store.hasReadAccess()) {
            status.update(IndexProgress(phase = "error", message = "Grant the Vault folder first"))
            return
        }
        status.update(IndexProgress(phase = "downloading", message = "Checking the embedding model"))
        models.ensure { bytes, total ->
            val message = if (total > 0) {
                "Downloading model ${bytes / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
            } else {
                "Downloading model ${bytes / (1024 * 1024)} MB"
            }
            status.update(IndexProgress(phase = "downloading", message = message))
        }
        reader.invalidate()
        val scanned = reader.scan()
        val existing = dao.allNotes().associate { it.path to it.mtime }
        val plan = planIndex(existing, scanned.associate { it.relativePath to it.mtime })
        for (path in plan.removed) {
            dao.deleteChunks(path)
            dao.deleteNote(path)
            tags.remove(path)
        }
        tags.retain(scanned.map { it.relativePath }.toSet())
        var failures = 0
        plan.changed.forEachIndexed { index, path ->
            status.update(
                IndexProgress(
                    phase = "indexing",
                    message = "Embedding ${index + 1} of ${plan.changed.size} changed notes",
                    done = index,
                    total = plan.changed.size,
                ),
            )
            val file = scanned.first { it.relativePath == path }
            try {
                val text = reader.readText(file.uri)
                dao.deleteChunks(path)
                if (text == null) {
                    failures += 1
                    return@forEachIndexed
                }
                tags.put(path, com.vaulthome.app.search.SearchFilter.extractTags(text))
                val drafts = Chunker.chunk(path, text)
                val rows = drafts.map { draft ->
                    val vector = embedder.embed(Prefixes.chunk(draft.title, draft.text))
                    ChunkEntity(
                        path = path,
                        heading = draft.heading,
                        chunkText = draft.text,
                        vector = Vectors.floatsToBytes(vector),
                        sourceMtime = file.mtime,
                    )
                }
                if (rows.isNotEmpty()) dao.insertChunks(rows)
                dao.upsertNote(NoteEntity(path = path, mtime = file.mtime))
            } catch (_: Exception) {
                failures += 1
            }
        }
        // Backfill tags for unchanged notes indexed before the tag index existed (read only, no embedding).
        scanned.filter { !tags.has(it.relativePath) }.forEach { f ->
            runCatching { reader.readText(f.uri) }.getOrNull()?.let { tags.put(f.relativePath, com.vaulthome.app.search.SearchFilter.extractTags(it)) }
        }
        runCatching { tags.save() }
        val count = dao.chunkCount()
        val unchanged = scanned.size - plan.changed.size
        val summary = if (plan.changed.isEmpty() && plan.removed.isEmpty()) {
            "Up to date. $unchanged notes unchanged. $count chunks."
        } else {
            "Embedded ${plan.changed.size} changed notes. Removed ${plan.removed.size}. $unchanged unchanged. $count chunks."
        }
        val failureNote = if (failures == 0) "" else " $failures notes skipped."
        status.update(
            IndexProgress(
                phase = "ready",
                message = summary + failureNote,
                done = plan.changed.size,
                total = plan.changed.size,
            ),
        )
    }
}
