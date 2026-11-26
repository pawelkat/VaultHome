package com.vaulthome.app.search

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class QueryMode { Search, Ask }

data class HistoryEntry(val text: String, val mode: QueryMode, val atMs: Long)

/** Pure store rules (unit-tested): newest first, same text+mode is moved to the top, cap [CAP]. */
object QueryHistory {
    const val CAP = 50

    fun record(entries: List<HistoryEntry>, text: String, mode: QueryMode, atMs: Long): List<HistoryEntry> {
        val t = text.trim()
        if (t.isEmpty()) return entries
        val rest = entries.filterNot { it.mode == mode && it.text.equals(t, ignoreCase = true) }
        return (listOf(HistoryEntry(t, mode, atMs)) + rest).take(CAP)
    }

    fun encode(entries: List<HistoryEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("q", e.text).put("mode", e.mode.name).put("at", e.atMs))
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<HistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("q").trim()
            val mode = runCatching { QueryMode.valueOf(o.optString("mode")) }.getOrNull()
            if (text.isEmpty() || mode == null) null else HistoryEntry(text, mode, o.optLong("at"))
        }
    }
}

class QueryHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("vaulthome_history", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(QueryHistory.decode(prefs.getString(KEY, null)))
    val state: StateFlow<List<HistoryEntry>> = flow

    fun record(text: String, mode: QueryMode, atMs: Long = System.currentTimeMillis()) {
        val next = QueryHistory.record(flow.value, text, mode, atMs)
        prefs.edit().putString(KEY, QueryHistory.encode(next)).apply()
        flow.value = next
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        flow.value = emptyList()
    }

    private companion object {
        const val KEY = "entries"
    }
}
