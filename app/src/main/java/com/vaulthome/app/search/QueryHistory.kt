package com.vaulthome.app.search

import android.content.Context
import com.vaulthome.app.vault.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class QueryMode { Search, Ask }

data class HistoryEntry(val text: String, val mode: QueryMode, val atMs: Long)

/**
 * The whole history as stored: newest-first [entries] plus [clearedAtMs], the time of the last "Clear". The clear
 * time travels with the data so a device that still has old entries cannot bring them back after a merge.
 */
data class HistoryDoc(val entries: List<HistoryEntry> = emptyList(), val clearedAtMs: Long = 0)

/** Pure rules (unit-tested): newest first, same text+mode collapses to its latest entry, cap [CAP], merge by timestamp. */
object QueryHistory {
    const val CAP = 50

    /** Vault file, same `app_settings/<App>/` layout as GymMonth and `app_settings/VaultEvents/endpoints.json`. */
    const val VAULT_PATH = "app_settings/VaultHome/history.json"
    const val FORMAT_VERSION = 1

    private fun key(e: HistoryEntry) = e.mode to e.text.trim().lowercase()

    private fun normalize(entries: Collection<HistoryEntry>, clearedAtMs: Long): List<HistoryEntry> = entries
        .filter { it.atMs > clearedAtMs && it.text.isNotBlank() }
        .sortedWith(compareByDescending<HistoryEntry> { it.atMs }.thenBy { it.mode }.thenBy { it.text })
        .distinctBy { key(it) }
        .take(CAP)

    fun record(entries: List<HistoryEntry>, text: String, mode: QueryMode, atMs: Long): List<HistoryEntry> {
        val t = text.trim()
        if (t.isEmpty()) return entries
        return normalize(listOf(HistoryEntry(t, mode, atMs)) + entries, 0)
    }

    fun record(doc: HistoryDoc, text: String, mode: QueryMode, atMs: Long) =
        doc.copy(entries = normalize(listOf(HistoryEntry(text.trim(), mode, maxOf(atMs, doc.clearedAtMs + 1))) + doc.entries, doc.clearedAtMs))

    fun clear(doc: HistoryDoc, atMs: Long) = HistoryDoc(emptyList(), maxOf(atMs, doc.clearedAtMs))

    /** Union of two copies: latest entry per (mode, text), nothing at or before the newest clear, newest first, capped. Commutative. */
    fun merge(a: HistoryDoc, b: HistoryDoc): HistoryDoc {
        val cleared = maxOf(a.clearedAtMs, b.clearedAtMs)
        return HistoryDoc(normalize(a.entries + b.entries, cleared), cleared)
    }

    fun encode(doc: HistoryDoc): String {
        val arr = JSONArray()
        doc.entries.forEach { e -> arr.put(JSONObject().put("q", e.text).put("mode", e.mode.name).put("at", e.atMs)) }
        return JSONObject().put("version", FORMAT_VERSION).put("cleared_at", doc.clearedAtMs).put("entries", arr).toString(2) + "\n"
    }

    fun encode(entries: List<HistoryEntry>) = encode(HistoryDoc(entries))

    /** Reads the file format, and the older plain array the app kept in SharedPreferences before it moved to the vault. */
    fun decodeDoc(raw: String?): HistoryDoc {
        if (raw.isNullOrBlank()) return HistoryDoc()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return HistoryDoc(normalize(entriesOf(runCatching { JSONArray(trimmed) }.getOrNull()), 0))
        val o = runCatching { JSONObject(trimmed) }.getOrNull() ?: return HistoryDoc()
        val cleared = o.optLong("cleared_at", 0)
        return HistoryDoc(normalize(entriesOf(o.optJSONArray("entries")), cleared), cleared)
    }

    fun decode(raw: String?): List<HistoryEntry> = decodeDoc(raw).entries

    private fun entriesOf(arr: JSONArray?): List<HistoryEntry> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("q").trim()
            val mode = runCatching { QueryMode.valueOf(o.optString("mode")) }.getOrNull()
            if (text.isEmpty() || mode == null) null else HistoryEntry(text, mode, o.optLong("at"))
        }
    }
}

/** Local copy of the history (SharedPreferences on the phone). */
interface HistoryCache {
    fun load(): String?
    fun save(raw: String)
}

/** The vault file. [read] returns `null` when it does not exist; [write] throws when the vault is not writable. */
interface HistoryFile {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/**
 * History with the vault file as the shared copy and the cache as the offline copy. Every change is applied to the
 * cache first, then [sync] merges the file into it (by timestamp, dedupe, cap) and writes the result back only when it
 * differs from what the file already holds, so two devices editing at once converge instead of overwriting each other.
 * Only [QueryHistory.VAULT_PATH] is ever written; no other vault note is touched. Methods are blocking and
 * synchronized: call [sync] off the main thread.
 */
class QueryHistoryRepo(private val cache: HistoryCache, private val file: HistoryFile?, private val clock: () -> Long = System::currentTimeMillis) {
    private var doc: HistoryDoc = QueryHistory.decodeDoc(cache.load())

    /** Last [sync] problem (no write access, unreadable file); `null` when fine or when there is no vault file. */
    @Volatile var lastError: String? = null
        private set

    @Synchronized fun current(): HistoryDoc = doc

    @Synchronized fun record(text: String, mode: QueryMode): HistoryDoc {
        if (text.isBlank()) return doc
        doc = QueryHistory.record(doc, text, mode, clock())
        cache.save(QueryHistory.encode(doc))
        return doc
    }

    @Synchronized fun clear(): HistoryDoc {
        doc = QueryHistory.clear(doc, clock())
        cache.save(QueryHistory.encode(doc))
        return doc
    }

    /** Merge the vault file into the local copy and write back if the file is behind. Returns the merged history. */
    @Synchronized fun sync(): HistoryDoc {
        val f = file ?: return doc
        val raw = try {
            f.read()?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            lastError = "history file unreadable: ${e.message}"
            return doc
        }
        val remote = QueryHistory.decodeDoc(raw)
        val merged = QueryHistory.merge(doc, remote)
        if (merged != doc) {
            doc = merged
            cache.save(QueryHistory.encode(doc))
        }
        // An absent or unparsable file counts as behind; a file already equal to the merge is left alone (no churn, no sync echo).
        if (raw == null || merged != remote) {
            try {
                f.write(QueryHistory.encode(merged).toByteArray(Charsets.UTF_8))
                lastError = null
            } catch (e: Exception) {
                lastError = "history not written to the vault: ${e.message}"
            }
        } else lastError = null
        return doc
    }
}

/** Android wiring: SharedPreferences cache + the vault file, synced off the main thread. */
class QueryHistoryStore(context: Context, vaultFile: HistoryFile? = null) {
    private val prefs = context.getSharedPreferences("vaulthome_history", Context.MODE_PRIVATE)
    private val repo = QueryHistoryRepo(
        object : HistoryCache {
            override fun load() = prefs.getString(KEY, null)
            override fun save(raw: String) { prefs.edit().putString(KEY, raw).apply() }
        },
        vaultFile,
    )
    private val flow = MutableStateFlow(repo.current().entries)
    val state: StateFlow<List<HistoryEntry>> = flow
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { sync() }

    fun record(text: String, mode: QueryMode) {
        flow.value = repo.record(text, mode).entries
        sync()
    }

    fun clear() {
        flow.value = repo.clear().entries
        sync()
    }

    /** At start, after a write, and when the event sync applies a new `history.json` from another device. */
    fun sync() {
        scope.launch { flow.value = repo.sync().entries }
    }

    val lastError: String? get() = repo.lastError

    private companion object {
        // Entries written before the vault file existed live here too: the first sync merges them into history.json.
        const val KEY = "entries"
    }
}
