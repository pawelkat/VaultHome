package com.vaulthome.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.util.TreeMap

/** Last accepted stamp per path, with the file's stat when recorded (Rust `state::AgentState`). */
class AgentState(val device: String, val vault: String) {
    data class Entry(val stamp: Stamp, val mtimeMs: Long?, val size: Long?)

    val entries: TreeMap<String, Entry> = TreeMap { a, b -> Utf8.compare(a, b) }

    fun stamp(path: String): Stamp? = entries[path]?.stamp

    fun record(stamp: Stamp, stat: Pair<Long, Long>?) {
        entries[stamp.path] = Entry(stamp, stat?.first, stat?.second)
    }

    fun encode(): String {
        val sb = StringBuilder()
        sb.append("{\"format\":").append(STATE_FORMAT).append(",\"device\":")
        JsonOut.string(sb, device)
        sb.append(",\"vault\":")
        JsonOut.string(sb, vault)
        sb.append(",\"entries\":{")
        var first = true
        for ((k, e) in entries) {
            if (!first) sb.append(',')
            first = false
            JsonOut.string(sb, k)
            sb.append(":{\"stamp\":")
            e.stamp.writeFields(sb, true)
            sb.append(",\"mtime_ms\":").append(e.mtimeMs?.toString() ?: "null")
            sb.append(",\"size\":").append(e.size?.toString() ?: "null")
            sb.append('}')
        }
        sb.append("}}")
        return sb.toString()
    }

    /** Atomic save (temp + rename). */
    fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode())
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot replace $file")
        }
    }

    companion object {
        const val STATE_FORMAT = 1

        fun decode(text: String): AgentState {
            val o = Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("state is not an object")
            if (JsonIn.u64(o, "format") != STATE_FORMAT.toLong()) throw IOException("unsupported state format")
            val s = AgentState(JsonIn.str(o, "device") ?: "", JsonIn.str(o, "vault") ?: "")
            val entries = o["entries"] as? JsonObject ?: JsonObject(emptyMap())
            for ((k, v) in entries) {
                val e = v as? JsonObject ?: continue
                val st = Stamp.fromJson(e["stamp"] as? JsonObject ?: continue)
                if (st.path != k) continue
                s.entries[k] = Entry(st, JsonIn.u64OrNull(e, "mtime_ms"), JsonIn.u64OrNull(e, "size"))
            }
            return s
        }

        /**
         * Load [file] or start empty. Refuses a state written for another vault or device: mixing them
         * would turn every note into a "local edit".
         */
        fun loadOrNew(file: File, device: String, vault: String): AgentState {
            if (!file.exists()) return AgentState(device, vault)
            val s = try {
                decode(file.readText())
            } catch (e: Exception) {
                throw IOException("$file: ${e.message}", e)
            }
            if (s.device != device || s.vault != vault) {
                throw IOException("$file belongs to device ${s.device} vault ${s.vault}, not $device $vault")
            }
            return s
        }
    }
}
