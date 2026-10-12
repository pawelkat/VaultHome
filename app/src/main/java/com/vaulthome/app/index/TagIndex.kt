package com.vaulthome.app.index

import java.io.File

/**
 * path -> tags, filled during indexing (no extra Room migration) and stored as a small TSV in app files.
 * Thread-safe; [frequencies] counts notes per tag.
 */
class TagIndex(private val file: File) {
    private val map = HashMap<String, Set<String>>()
    private var loaded = false

    @Synchronized private fun ensure() {
        if (loaded) return
        loaded = true
        if (file.exists()) file.readLines().forEach { l ->
            val p = l.split('\t', limit = 2)
            if (p[0].isNotEmpty()) map[p[0]] = p.getOrElse(1) { "" }.split(',').filter { it.isNotEmpty() }.toSet()
        }
    }

    @Synchronized fun has(path: String): Boolean { ensure(); return path in map }
    @Synchronized fun get(path: String): Set<String>? { ensure(); return map[path] }
    @Synchronized fun put(path: String, tags: Set<String>) { ensure(); map[path] = tags }
    @Synchronized fun remove(path: String) { ensure(); map.remove(path) }
    @Synchronized fun retain(paths: Set<String>) { ensure(); map.keys.retainAll(paths) }
    @Synchronized fun clear() { map.clear(); loaded = true; file.delete() }
    @Synchronized fun frequencies(): Map<String, Int> {
        ensure()
        val out = HashMap<String, Int>()
        map.values.forEach { tags -> tags.forEach { out[it] = (out[it] ?: 0) + 1 } }
        return out
    }
    @Synchronized fun save() {
        ensure()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(map.entries.joinToString("\n") { "${it.key.replace('\t', ' ').replace('\n', ' ')}\t${it.value.joinToString(",")}" })
        tmp.renameTo(file)
    }
}
