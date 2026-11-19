package com.vaulthome.events

/**
 * Transfer acknowledgement, same wire format as Rust `ack.rs`: after this device has a file on disk
 * and has verified its SHA-256, it publishes on `vault/ack/<sha256>/<device>` with
 * `{status: ok|fail, reason?, path, bytes, duration_ms}`.
 */
object Ack {
    const val PREFIX = "vault/ack"
    const val WILDCARD = "vault/ack/**"

    private val SHA = Regex("^[0-9a-fA-F]{64}$")

    fun normalizeSha256(s: String): String? = if (SHA.matches(s)) s.lowercase() else null

    /** `vault/ack/<sha256>/<device>` */
    fun key(sha256: String, device: String): String {
        val sha = normalizeSha256(sha256) ?: throw IllegalArgumentException("bad sha256")
        return "$PREFIX/$sha/${Requester.clean(device)}"
    }

    /** Inverse of [key]: `(sha256, device)`, or `null`. */
    fun parseKey(key: String): Pair<String, String>? {
        val rest = key.removePrefix("$PREFIX/").takeIf { it != key && '/' in it } ?: return null
        val sha = rest.substringBefore('/')
        val dev = Requester.clean(rest.substringAfter('/'))
        val norm = normalizeSha256(sha) ?: return null
        if (dev == Requester.UNKNOWN || '/' in dev) return null
        return norm to dev
    }

    /** `0.4s`, `1.2s`, `0.0s`. */
    fun fmtSecs(durationMs: Long): String = String.format(java.util.Locale.ROOT, "%.1fs", durationMs / 1000.0)

    /** `<device> received <path> (<bytes>) ok in X.Xs` or `... fail: <reason>`. */
    fun logLine(device: String, ack: TransferAck): String = when (ack.status) {
        AckStatus.OK -> "$device received ${ack.path} (${ack.bytes}) ok in ${fmtSecs(ack.durationMs)}"
        AckStatus.FAIL -> "$device received ${ack.path} (${ack.bytes}) fail: ${ack.reason ?: "-"}"
    }
}

enum class AckStatus(val wire: String) { OK("ok"), FAIL("fail") }

data class TransferAck(
    val status: AckStatus,
    val reason: String? = null,
    val path: String,
    val bytes: Long,
    val durationMs: Long,
) {
    fun encode(): ByteArray {
        val sb = StringBuilder("{\"status\":")
        JsonOut.string(sb, status.wire)
        if (reason != null) {
            sb.append(",\"reason\":"); JsonOut.string(sb, reason)
        }
        sb.append(",\"path\":"); JsonOut.string(sb, path)
        sb.append(",\"bytes\":").append(bytes)
        sb.append(",\"duration_ms\":").append(durationMs).append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun ok(path: String, bytes: Long, durationMs: Long) = TransferAck(AckStatus.OK, null, path, bytes, durationMs)

        fun fail(path: String, bytes: Long, durationMs: Long, reason: String) =
            TransferAck(AckStatus.FAIL, reason, path, bytes, durationMs)

        fun decode(bytes: ByteArray): TransferAck {
            val o = JsonIn.parseObject(bytes)
            val status = when (val s = JsonIn.str(o, "status")) {
                "ok" -> AckStatus.OK
                "fail" -> AckStatus.FAIL
                else -> throw JsonIn.Bad("status: expected ok or fail")
            }
            val path = JsonIn.str(o, "path") ?: throw JsonIn.Bad("path: missing")
            val n = JsonIn.u64(o, "bytes") ?: throw JsonIn.Bad("bytes: missing")
            val ms = JsonIn.u64(o, "duration_ms") ?: throw JsonIn.Bad("duration_ms: missing")
            return TransferAck(status, JsonIn.optStr(o, "reason"), path, n, ms)
        }
    }
}
