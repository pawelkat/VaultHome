package com.vaulthome.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Base64

/** Current payload format (Rust `event::FORMAT_VERSION`). */
const val FORMAT_VERSION: Int = 1

/**
 * Version metadata of one note state (live content or tombstone), as in Rust `event::Stamp`.
 *
 * `version` and `tsMs` are Rust `u64`; values above [Long.MAX_VALUE] are rejected on decode.
 */
data class Stamp(
    val path: String,
    val version: Long,
    val tsMs: Long,
    val origin: String,
    val deleted: Boolean = false,
    val sha256: String = "",
    val prevSha256: String? = null,
) {
    /**
     * Total order used by every device and the hub:
     * higher version, later ts, live over tombstone, larger origin, larger sha256 (byte-wise).
     */
    fun compareRank(other: Stamp): Int {
        java.lang.Long.compareUnsigned(version, other.version).let { if (it != 0) return it }
        java.lang.Long.compareUnsigned(tsMs, other.tsMs).let { if (it != 0) return it }
        (!deleted).compareTo(!other.deleted).let { if (it != 0) return it }
        Utf8.compare(origin, other.origin).let { if (it != 0) return it }
        return Utf8.compare(sha256, other.sha256)
    }

    /** `true` if this strictly wins over [other]. */
    fun beats(other: Stamp): Boolean = compareRank(other) > 0

    /** Same logical state (same version, ts, liveness, origin and content). */
    fun sameState(other: Stamp): Boolean = compareRank(other) == 0

    /** serde_json shape of a bare `Stamp` (index replies, state files). */
    fun toJson(): String = StringBuilder().also { writeFields(it, true) }.toString()

    internal fun writeFields(sb: StringBuilder, braces: Boolean) {
        if (braces) sb.append('{')
        sb.append("\"path\":"); JsonOut.string(sb, path)
        sb.append(",\"version\":").append(java.lang.Long.toUnsignedString(version))
        sb.append(",\"ts_ms\":").append(java.lang.Long.toUnsignedString(tsMs))
        sb.append(",\"origin\":"); JsonOut.string(sb, origin)
        sb.append(",\"deleted\":").append(deleted)
        sb.append(",\"sha256\":"); JsonOut.string(sb, sha256)
        if (prevSha256 != null) { sb.append(",\"prev_sha256\":"); JsonOut.string(sb, prevSha256) }
        if (braces) sb.append('}')
    }

    companion object {
        internal fun fromJson(o: JsonObject): Stamp = Stamp(
            path = JsonIn.str(o, "path") ?: throw JsonIn.Bad("missing path"),
            version = JsonIn.u64(o, "version") ?: throw JsonIn.Bad("missing version"),
            tsMs = JsonIn.u64(o, "ts_ms") ?: throw JsonIn.Bad("missing ts_ms"),
            origin = JsonIn.str(o, "origin") ?: throw JsonIn.Bad("missing origin"),
            deleted = JsonIn.bool(o, "deleted") ?: false,
            sha256 = JsonIn.str(o, "sha256") ?: "",
            prevSha256 = JsonIn.optStr(o, "prev_sha256"),
        )
    }
}

/** Rust `event::DecodeError` variants. */
enum class DecodeErrorKind { Json, UnsupportedFormat, BadPath, KeyMismatch, HashMismatch, MissingContent, TombstoneWithContent }

class DecodeException(val kind: DecodeErrorKind, message: String) : Exception("$kind: $message")

/** One change, as published on `vault/notes/<path>` (Rust `event::NoteEvent`). */
class NoteEvent(
    val format: Int = FORMAT_VERSION,
    val stamp: Stamp,
    /** File bytes (base64 on the wire); `null` for a tombstone. */
    val content: ByteArray?,
) {
    val path: String get() = stamp.path

    fun key(): String = VaultKey.pathToKey(stamp.path)

    /** Byte-identical to Rust `serde_json::to_vec(&NoteEvent)`. */
    fun encode(): ByteArray = encodeToString().toByteArray(Charsets.UTF_8)

    fun encodeToString(): String {
        val sb = StringBuilder(64 + (content?.size ?: 0) * 4 / 3)
        sb.append("{\"format\":").append(format).append(',')
        stamp.writeFields(sb, false)
        if (content != null) sb.append(",\"content\":\"").append(Base64.getEncoder().encodeToString(content)).append('"')
        sb.append('}')
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean =
        other is NoteEvent && other.format == format && other.stamp == stamp &&
            (content?.contentEquals(other.content ?: return false) ?: (other.content == null))

    override fun hashCode(): Int = stamp.hashCode() * 31 + (content?.contentHashCode() ?: 0)

    override fun toString(): String = "NoteEvent(${stamp}, ${content?.size ?: "no"} bytes)"

    companion object {
        fun put(path: String, content: ByteArray, origin: String, version: Long, prevSha256: String?, tsMs: Long) =
            NoteEvent(FORMAT_VERSION, Stamp(path, version, tsMs, origin, false, sha256Hex(content), prevSha256), content)

        fun delete(path: String, origin: String, version: Long, prevSha256: String?, tsMs: Long) =
            NoteEvent(FORMAT_VERSION, Stamp(path, version, tsMs, origin, true, "", prevSha256), null)

        /**
         * Decode and validate (Rust `NoteEvent::decode`). [key] is the Zenoh key the sample arrived
         * on, `null` when there is nothing to check against.
         */
        fun decode(bytes: ByteArray, key: String?): NoteEvent {
            val root = JsonIn.parseObject(bytes)
            val format = try {
                JsonIn.u64(root, "format")?.takeIf { it in 0..0xFFFF_FFFFL } ?: throw JsonIn.Bad("format")
            } catch (e: JsonIn.Bad) {
                throw DecodeException(DecodeErrorKind.Json, e.message ?: "format")
            }
            if (format == 0L || format > FORMAT_VERSION) {
                throw DecodeException(DecodeErrorKind.UnsupportedFormat, "unsupported event format $format (this build reads <= $FORMAT_VERSION)")
            }
            val (stamp, content) = try {
                Stamp.fromJson(root) to JsonIn.optStr(root, "content")?.let { JsonIn.base64(it) }
            } catch (e: JsonIn.Bad) {
                throw DecodeException(DecodeErrorKind.Json, e.message ?: "bad field")
            }
            VaultKey.check(stamp.path)?.let { throw DecodeException(DecodeErrorKind.BadPath, "bad path: $it") }
            if (key != null) {
                val expect = VaultKey.pathToKey(stamp.path)
                if (expect != key) throw DecodeException(DecodeErrorKind.KeyMismatch, "key $key does not match payload path ${stamp.path}")
            }
            when {
                content != null && stamp.deleted -> throw DecodeException(DecodeErrorKind.TombstoneWithContent, "tombstone carries content")
                content == null && !stamp.deleted -> throw DecodeException(DecodeErrorKind.MissingContent, "live event without content")
                content != null && sha256Hex(content) != stamp.sha256 -> throw DecodeException(DecodeErrorKind.HashMismatch, "sha256 does not match content")
            }
            return NoteEvent(format.toInt(), stamp, content)
        }
    }
}

/** Reply body of the hub's `vault/hub/index` queryable (Rust `net::IndexReply`). */
data class IndexReply(val format: Int, val hub: String, val stamps: List<Stamp>) {
    fun encodeToString(): String {
        val sb = StringBuilder()
        sb.append("{\"format\":").append(format).append(",\"hub\":")
        JsonOut.string(sb, hub)
        sb.append(",\"stamps\":[")
        stamps.forEachIndexed { i, s -> if (i > 0) sb.append(','); s.writeFields(sb, true) }
        sb.append("]}")
        return sb.toString()
    }

    companion object {
        fun decode(bytes: ByteArray): IndexReply = try {
            val o = JsonIn.parseObject(bytes)
            val stamps = (o["stamps"] as? JsonArray ?: throw JsonIn.Bad("stamps")).map {
                Stamp.fromJson(it as? JsonObject ?: throw JsonIn.Bad("stamp"))
            }
            IndexReply(
                format = (JsonIn.u64(o, "format") ?: throw JsonIn.Bad("format")).toInt(),
                hub = JsonIn.str(o, "hub") ?: throw JsonIn.Bad("hub"),
                stamps = stamps,
            )
        } catch (e: JsonIn.Bad) {
            throw DecodeException(DecodeErrorKind.Json, e.message ?: "bad index")
        }
    }
}

/** Lower-case hex SHA-256. */
fun sha256Hex(bytes: ByteArray): String {
    val d = MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = CharArray(64)
    for (i in d.indices) {
        val v = d[i].toInt() and 0xFF
        out[i * 2] = HEX_LOWER[v shr 4]
        out[i * 2 + 1] = HEX_LOWER[v and 0xF]
    }
    return String(out)
}

private val HEX_LOWER = "0123456789abcdef".toCharArray()

/** serde_json's compact string escaping, so encodings are byte-identical to the Rust side. */
internal object JsonOut {
    fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u00").append(HEX_LOWER[c.code shr 4]).append(HEX_LOWER[c.code and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }
}

/** Strict typed reads mirroring serde's derive: wrong JSON types are errors, not coercions. */
internal object JsonIn {
    class Bad(msg: String) : Exception(msg)

    private val json = Json { isLenient = false }

    fun parseObject(bytes: ByteArray): JsonObject {
        val text = Utf8.decodeStrict(bytes) ?: throw DecodeException(DecodeErrorKind.Json, "payload is not UTF-8")
        val el = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw DecodeException(DecodeErrorKind.Json, "bad JSON: ${e.message}")
        }
        return el as? JsonObject ?: throw DecodeException(DecodeErrorKind.Json, "payload is not a JSON object")
    }

    private fun prim(o: JsonObject, k: String): JsonPrimitive? = when (val v = o[k]) {
        null -> null
        is JsonPrimitive -> v
        else -> throw Bad("$k: wrong type")
    }

    fun str(o: JsonObject, k: String): String? {
        val p = prim(o, k) ?: return null
        if (p is JsonNull || !p.isString) throw Bad("$k: expected a string")
        return p.content
    }

    /** `Option<String>`: absent or `null` -> null. */
    fun optStr(o: JsonObject, k: String): String? {
        val p = prim(o, k) ?: return null
        if (p is JsonNull) return null
        if (!p.isString) throw Bad("$k: expected a string")
        return p.content
    }

    fun bool(o: JsonObject, k: String): Boolean? {
        val p = prim(o, k) ?: return null
        if (p is JsonNull || p.isString) throw Bad("$k: expected a boolean")
        return when (p.content) { "true" -> true; "false" -> false; else -> throw Bad("$k: expected a boolean") }
    }

    fun u64(o: JsonObject, k: String): Long? {
        val p = prim(o, k) ?: return null
        if (p is JsonNull || p.isString) throw Bad("$k: expected an integer")
        val c = p.content
        if (c.isEmpty() || !c.all { it in '0'..'9' }) throw Bad("$k: expected an unsigned integer")
        return c.toLongOrNull() ?: throw Bad("$k: integer out of range for this build")
    }

    fun u64OrNull(o: JsonObject, k: String): Long? {
        val p = prim(o, k) ?: return null
        if (p is JsonNull) return null
        return u64(o, k)
    }

    /** base64 STANDARD with padding (Rust `base64::engine::general_purpose::STANDARD`). */
    fun base64(s: String): ByteArray {
        if (s.length % 4 != 0) throw Bad("content: invalid base64 length")
        return try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw Bad("content: ${e.message}")
        }
    }
}
