package com.vaulthome.events

/** Why a vault path or note key was rejected (names match Rust `key::PathError`). */
enum class PathError { Empty, Absolute, ParentDir, EmptyComponent, NotUtf8, BadEscape, NotANoteKey }

class PathException(val error: PathError) : IllegalArgumentException(error.name)

/**
 * Vault path <-> Zenoh key expression, a port of `crates/vault-events/src/key.rs`.
 *
 * Every note lives under `vault/notes/<path>`. `%`, `#`, `?`, `*` and `$` are percent-encoded
 * (upper-case hex); everything else, spaces and non-ASCII included, passes through. Only the
 * canonical encoding is accepted, so one path has exactly one key.
 */
object VaultKey {
    const val NOTES_PREFIX = "vault/notes"
    const val NOTES_WILDCARD = "vault/notes/**"
    const val INDEX_KEY = "vault/hub/index"

    /** `null` when [path] is a valid vault-relative `/`-separated path. */
    fun check(path: String): PathError? {
        if (path.isEmpty()) return PathError.Empty
        if (path.startsWith('/') || path.contains('\\') || path.contains('\u0000')) return PathError.Absolute
        for (c in path.split('/')) {
            if (c.isEmpty()) return PathError.EmptyComponent
            if (c == "." || c == "..") return PathError.ParentDir
        }
        return null
    }

    fun validateRel(path: String): String {
        check(path)?.let { throw PathException(it) }
        return path
    }

    fun isValid(path: String): Boolean = check(path) == null

    private fun needsEscape(c: Char) = c == '%' || c == '#' || c == '?' || c == '*' || c == '$'

    /** `Notes/A #1.md` -> `vault/notes/Notes/A %231.md` */
    fun pathToKey(path: String): String {
        validateRel(path)
        val out = StringBuilder(NOTES_PREFIX.length + 1 + path.length)
        out.append(NOTES_PREFIX).append('/')
        for (ch in path) {
            if (needsEscape(ch)) out.append('%').append(HEX[ch.code shr 4]).append(HEX[ch.code and 0xF]) else out.append(ch)
        }
        return out.toString()
    }

    /** Inverse of [pathToKey]; throws [PathException]. */
    fun keyToPath(key: String): String {
        val prefix = "$NOTES_PREFIX/"
        if (!key.startsWith(prefix)) throw PathException(PathError.NotANoteKey)
        val bytes = key.substring(prefix.length).toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '%'.code.toByte()) {
                if (i + 3 > bytes.size) throw PathException(PathError.BadEscape)
                val hi = bytes[i + 1].toInt() and 0xFF
                val lo = bytes[i + 2].toInt() and 0xFF
                val v = hexPair(hi, lo) ?: throw PathException(PathError.BadEscape)
                out.write(v)
                i += 3
            } else {
                out.write(bytes[i].toInt() and 0xFF)
                i += 1
            }
        }
        val s = Utf8.decodeStrict(out.toByteArray()) ?: throw PathException(PathError.NotUtf8)
        validateRel(s)
        if (pathToKey(s) != key) throw PathException(PathError.BadEscape)
        return s
    }

    /** Like Rust `u8::from_str_radix(s, 16)` on a 2-byte str: optional leading `+`, hex digits. */
    private fun hexPair(a: Int, b: Int): Int? {
        if (a >= 0x80 || b >= 0x80) return null
        val s = "${a.toChar()}${b.toChar()}"
        val digits = if (s.startsWith('+')) s.substring(1) else s
        if (digits.isEmpty() || digits.any { Character.digit(it, 16) < 0 || it.code >= 0x80 }) return null
        return digits.toInt(16).takeIf { it in 0..255 }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}

internal object Utf8 {
    /** Decode UTF-8, `null` on malformed input (Rust `String::from_utf8`). */
    fun decodeStrict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }

    /** Rust `str` ordering: byte-wise over UTF-8. */
    fun compare(a: String, b: String): Int {
        if (a == b) return 0
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val c = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
