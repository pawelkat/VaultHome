package com.vaulthome.app.index

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What one event-driven pass does (pure, unit-tested). */
sealed interface ReindexPlan {
    /** Re-embed or drop exactly these notes. */
    data class Paths(val paths: List<String>) : ReindexPlan

    /** Incremental pass over the whole vault (mtime diff): a big burst, or a catch-up with no path list. */
    data object Full : ReindexPlan

    companion object {
        /** Above this many changed notes a path pass costs more than the mtime diff of a full incremental pass. */
        const val MAX_PATHS = 100

        /** Only markdown notes are indexed; dot-folders and sync temp files never are. */
        fun isIndexable(path: String): Boolean {
            val lower = path.lowercase()
            return (lower.endsWith(".md") || lower.endsWith(".markdown")) && path.split('/').none { it.startsWith(".") }
        }

        fun of(changed: Collection<String>, full: Boolean): ReindexPlan? {
            if (full) return Full
            val paths = changed.filter(::isIndexable).distinct()
            return when {
                paths.isEmpty() -> null
                paths.size > MAX_PATHS -> Full
                else -> Paths(paths)
            }
        }
    }
}

/**
 * Turns "the vault changed" events from the Zenoh sync into index passes. Bursts (a catch-up, several notes in a
 * row) are collected: the pass starts [quietMs] after the last event, or after [maxWaitMs] from the first one
 * if events keep coming, so a long sync still gets indexed in steps. [run] returns false when the pass could not run
 * (e.g. no model yet); the paths are then kept for the next event or a manual Index.
 */
class EventReindexer(
    private val scope: CoroutineScope,
    private val quietMs: Long = 4_000,
    private val maxWaitMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val run: suspend (ReindexPlan) -> Boolean,
) {
    private val lock = Any()
    private val pending = LinkedHashSet<String>()
    private var full = false
    private var firstAt = 0L
    private var job: Job? = null

    /** Called by the sync engine after it wrote, deleted or conflict-copied notes. */
    fun onVaultChanged(paths: Collection<String>) = add(paths, false)

    /** A session just connected and caught up: check everything once (cheap when nothing changed). */
    fun requestFull() = add(emptyList(), true)

    private fun add(paths: Collection<String>, wantFull: Boolean) {
        val relevant = paths.filter(ReindexPlan::isIndexable)
        if (relevant.isEmpty() && !wantFull) return
        synchronized(lock) {
            if (pending.isEmpty() && !full) firstAt = clock()
            pending += relevant
            if (wantFull) full = true
            job?.cancel()
            val wait = minOf(quietMs, maxOf(0L, firstAt + maxWaitMs - clock()))
            job = scope.launch {
                delay(wait)
                flush()
            }
        }
    }

    private suspend fun flush() {
        val (paths, wantFull) = synchronized(lock) {
            val taken = pending.toList() to full
            pending.clear(); full = false
            taken
        }
        val plan = ReindexPlan.of(paths, wantFull) ?: return
        val ok = try { run(plan) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
        if (!ok) synchronized(lock) { pending += paths; if (wantFull) full = true }
    }

    /** Paths waiting for the next pass (tests, diagnostics). */
    fun pendingCount(): Int = synchronized(lock) { pending.size }
}
