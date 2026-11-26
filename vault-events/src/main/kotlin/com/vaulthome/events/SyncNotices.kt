package com.vaulthome.events

/** A note another device changed and this device applied. */
data class SyncedNote(
    val path: String,
    val origin: String,
    val deleted: Boolean = false,
    /** A conflict copy written because of the event (the incoming or the local loser). */
    val conflictCopy: Boolean = false,
) {
    /** Note title as VaultHome shows it: file name without `.md`. */
    val title: String get() = path.substringAfterLast('/').removeSuffix(".md")
}

/** What to post: one notification per note, or one summary for many. */
sealed class Notice {
    data class Single(val note: SyncedNote) : Notice()

    data class Summary(val notes: List<SyncedNote>, val catchUp: Boolean) : Notice() {
        val count: Int get() = notes.size
    }
}

/**
 * Which applied events become "Synced notes" notifications, and how they are grouped.
 *
 * Only remote changes count: an event whose origin is this device (its own edit coming back, e.g.
 * after the state file was reset), an echo of its own put (zenoh-kotlin delivers those; the core
 * returns `Ignore`), `Adopt` (disk already had the content) and `KeepLocal` (local wins, nothing
 * changed on disk) post nothing. Shared settings under [SHARED_PREFIX] change silently.
 */
object SyncNotices {
    const val SHARED_PREFIX = "app_settings/VaultEvents/"

    /** Above this many notes in one batch, post a single summary instead. */
    const val MAX_INDIVIDUAL = 3

    fun fromIncoming(ev: NoteEvent, applied: Applied, ownDevice: String): List<SyncedNote> {
        if (ev.stamp.origin == ownDevice) return emptyList()
        val out = ArrayList<SyncedNote>(1 + applied.conflicts.size)
        if (applied.changed && applied.decision is Decision.Apply && !ev.path.startsWith(SHARED_PREFIX)) {
            out += SyncedNote(ev.path, ev.stamp.origin, deleted = ev.stamp.deleted)
        }
        for (c in applied.conflicts) out += SyncedNote(c, ev.stamp.origin, conflictCopy = true)
        return out
    }

    /**
     * Group a batch (live notes collected over a short debounce, or one whole catch-up pass).
     * The latest entry per path wins; up to [maxIndividual] notes get their own notification.
     */
    fun plan(batch: List<SyncedNote>, catchUp: Boolean, maxIndividual: Int = MAX_INDIVIDUAL): List<Notice> {
        if (batch.isEmpty()) return emptyList()
        val byPath = LinkedHashMap<String, SyncedNote>()
        for (n in batch) {
            byPath.remove(n.path)
            byPath[n.path] = n
        }
        val notes = byPath.values.toList()
        return if (notes.size <= maxIndividual) notes.map { Notice.Single(it) } else listOf(Notice.Summary(notes, catchUp))
    }

    /** Notification text for one note. */
    fun singleText(n: SyncedNote): Pair<String, String> {
        val title = when {
            n.conflictCopy -> "Conflict copy: ${n.title}"
            n.deleted -> "Deleted: ${n.title}"
            else -> n.title
        }
        return title to "${n.path} · from ${n.origin}"
    }

    fun summaryTitle(s: Notice.Summary): String {
        val origins = s.notes.map { it.origin }.distinct()
        val from = if (origins.size == 1) " from ${origins[0]}" else " from ${origins.size} devices"
        return "${s.count} notes synced$from"
    }
}
