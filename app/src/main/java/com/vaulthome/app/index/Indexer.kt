package com.vaulthome.app.index

import com.vaulthome.app.model.Embedder
import com.vaulthome.app.model.ModelStore
import com.vaulthome.app.vault.VaultReader
import com.vaulthome.app.vault.VaultStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Indexer(
    private val store: VaultStore,
    private val reader: VaultReader,
    private val dao: ChunkDao,
    private val models: ModelStore,
    private val embedder: Embedder,
    private val status: IndexStatusStore,
    private val tags: TagIndex,
) {
    /** One index run at a time: the manual/Worker full pass and the Zenoh-event passes never interleave. */
    private val runLock = Mutex()

    suspend fun indexAll() = runLock.withLock { indexAllLocked() }

    /**
     * Event-driven pass for the notes a sync just changed: re-embed those that exist, drop those that are gone.
     * No directory walk and no model download; returns false (nothing done) when the model is not on the phone yet
     * or the vault is not granted, so the caller can fall back to a full [indexAll] later.
     */
    suspend fun indexPaths(paths: Collection<String>): Boolean = runLock.withLock {
        if (!store.hasReadAccess() || !models.isReady()) return@withLock false
        val wanted = paths.filter(ReindexPlan::isIndexable).distinct()
        if (wanted.isEmpty()) return@withLock true
        reader.invalidate()
        var done = 0
        var removed = 0
        var failures = 0
        wanted.forEachIndexed { i, path ->
            status.update(IndexProgress(phase = "indexing", message = "Embedding ${i + 1} of ${wanted.size} synced notes", done = i, total = wanted.size))
            val file = reader.findByPath(path)
            if (file == null) {
                dao.deleteChunks(path); dao.deleteNote(path); tags.remove(path); removed++
            } else if (embedNote(path, file)) done++ else failures++
        }
        runCatching { tags.save() }
        status.update(
            IndexProgress(
                phase = "ready",
                message = "Synced: embedded $done, removed $removed" + (if (failures > 0) ", $failures skipped" else "") + ". ${dao.chunkCount()} chunks.",
                done = wanted.size, total = wanted.size,
            ),
        )
        true
    }

    /** Embeds one note into the index; false when it could not be read or embedded. */
    private suspend fun embedNote(path: String, file: com.vaulthome.app.vault.VaultFile): Boolean = try {
        val text = reader.readText(file.uri)
        dao.deleteChunks(path)
        if (text == null) {
            false
        } else {
            tags.put(path, com.vaulthome.app.search.SearchFilter.extractTags(text))
            val rows = Chunker.chunk(path, text).map { draft ->
                ChunkEntity(
                    path = path, heading = draft.heading, chunkText = draft.text,
                    vector = Vectors.floatsToBytes(embedder.embed(Prefixes.chunk(draft.title, draft.text))),
                    sourceMtime = file.mtime,
                )
            }
            if (rows.isNotEmpty()) dao.insertChunks(rows)
            dao.upsertNote(NoteEntity(path = path, mtime = file.mtime))
            true
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun indexAllLocked() {
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
            if (!embedNote(path, file)) failures += 1
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
