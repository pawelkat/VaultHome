package com.vaulthome.events

/** Rust `conflict::Decision`. */
sealed class Decision {
    /** Nothing to do. */
    data object Ignore : Decision()

    /** Disk already holds the incoming state; record the stamp only. */
    data object Adopt : Decision()

    /** Write (or delete) the live path from the incoming event; keep current disk bytes as [saveLocalAs] first. */
    data class Apply(val saveLocalAs: String?) : Decision()

    /** Local unpublished edit wins: keep disk, publish [local], save incoming bytes as [saveIncomingAs]. */
    data class KeepLocal(val local: Stamp, val saveIncomingAs: String?) : Decision()
}

/**
 * What to do with one incoming event, given what this device knows. Port of
 * `crates/vault-events/src/conflict.rs` (`decide`, `conflict_name_for`, `utc_date`).
 */
object Conflict {
    /**
     * @param state last stamp this device accepted for the path, if any
     * @param disk SHA-256 of the file on disk right now (`null` = absent)
     * @param incoming the event's stamp
     * @param me this device's id
     * @param localTsMs mtime (or now) of the dirty local file
     */
    fun decide(state: Stamp?, disk: String?, incoming: Stamp, me: String, localTsMs: Long): Decision {
        val incomingSha = if (incoming.deleted) null else incoming.sha256
        val newer = state == null || incoming.beats(state)

        // 1. Disk already matches.
        if (disk == incomingSha) return if (newer) Decision.Adopt else Decision.Ignore
        // 2. Stale or echo.
        if (!newer) return Decision.Ignore
        val stateSha = state?.takeIf { !it.deleted }?.sha256
        val dirty = disk != stateSha

        if (dirty) {
            // 3. Concurrent local edit (or an untracked local file).
            val local = Stamp(
                path = incoming.path,
                version = state?.let { it.version + 1 } ?: 1,
                tsMs = localTsMs,
                origin = me,
                deleted = disk == null,
                sha256 = disk ?: "",
                prevSha256 = stateSha,
            )
            if (local.beats(incoming)) {
                val saveIncomingAs = if (!incoming.deleted) conflictName(incoming) else null
                return Decision.KeepLocal(local, saveIncomingAs)
            }
            return Decision.Apply(if (disk != null) conflictName(local) else null)
        }

        // 4. Clean.
        return when {
            state == null -> Decision.Apply(null)
            state.deleted -> Decision.Apply(null)
            else -> {
                val fastForward = incoming.prevSha256 == state.sha256
                if (fastForward || disk == null) Decision.Apply(null) else Decision.Apply(conflictName(state))
            }
        }
    }

    /** Keep device ids filename-safe (Rust `char::is_alphanumeric` or `-` `_` `.`; else `-`). */
    fun safeDevice(d: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < d.length) {
            val cp = d.codePointAt(i)
            if (isAlphanumeric(cp) || cp == '-'.code || cp == '_'.code || cp == '.'.code) sb.appendCodePoint(cp) else sb.append('-')
            i += Character.charCount(cp)
        }
        return if (sb.isEmpty()) "unknown" else sb.toString()
    }

    /** Rust `char::is_alphanumeric`: Unicode Alphabetic or Numeric (Nd, Nl, No). */
    private fun isAlphanumeric(cp: Int): Boolean {
        if (Character.isAlphabetic(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
    }

    /** UTC calendar date for Unix milliseconds (proleptic Gregorian, Howard Hinnant's civil_from_days). */
    fun utcDate(tsMs: Long): String {
        val days = java.lang.Long.divideUnsigned(tsMs, 86_400_000L)
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = Math.floorMod(z, 146_097L)
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y0 = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = if (m <= 2) y0 + 1 else y0
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m, d)
    }

    /** `Notes/a.md` + loser {origin s24, ts 2026-11-18} -> `Notes/a (conflict s24 2026-11-18).md` */
    fun conflictName(loser: Stamp): String = conflictNameFor(loser.path, loser.origin, loser.tsMs)

    fun conflictNameFor(path: String, origin: String, tsMs: Long): String {
        val i = path.lastIndexOf('/')
        val dir = if (i >= 0) path.substring(0, i + 1) else ""
        val file = if (i >= 0) path.substring(i + 1) else path
        val tag = "(conflict ${safeDevice(origin)} ${utcDate(tsMs)})"
        val dot = file.lastIndexOf('.')
        return if (dot > 0) "$dir${file.substring(0, dot)} $tag${file.substring(dot)}" else "$dir$file $tag"
    }

    fun isConflictCopy(path: String): Boolean = path.contains(" (conflict ")
}
