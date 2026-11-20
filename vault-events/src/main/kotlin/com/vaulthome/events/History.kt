package com.vaulthome.events

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One stored event as the hub recorded it (Rust `history::HistoryEntry`; only the fields we use). */
data class HistoryEntry(val sha256: String, val prevSha256: String?)

/** Reply body of `vault/hub/history/<path>` (Rust `history::HistoryReply`). */
data class HistoryReply(val format: Int, val hub: String, val path: String, val entries: List<HistoryEntry>) {
    companion object {
        fun decode(bytes: ByteArray): HistoryReply = try {
            val o = JsonIn.parseObject(bytes)
            val entries = (o["entries"] as? JsonArray ?: throw JsonIn.Bad("entries")).mapNotNull { item ->
                val e = item as? JsonObject ?: return@mapNotNull null
                HistoryEntry(
                    sha256 = JsonIn.str(e, "sha256") ?: "",
                    prevSha256 = JsonIn.optStr(e, "prev_sha256")?.takeIf { it.isNotEmpty() },
                )
            }
            HistoryReply(
                format = (JsonIn.u64(o, "format") ?: throw JsonIn.Bad("format")).toInt(),
                hub = JsonIn.str(o, "hub") ?: throw JsonIn.Bad("hub"),
                path = JsonIn.str(o, "path") ?: throw JsonIn.Bad("path"),
                entries = entries,
            )
        } catch (e: JsonIn.Bad) {
            throw DecodeException(DecodeErrorKind.Json, e.message ?: "bad history")
        }
    }
}
