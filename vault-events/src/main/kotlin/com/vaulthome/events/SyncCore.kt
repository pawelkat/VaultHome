package com.vaulthome.events

import java.io.File

/** What one incoming event did (Rust `agent::Applied`). */
data class Applied(
    val decision: Decision? = null,
    /** Live path written or deleted. */
    val changed: Boolean = false,
    /** Conflict copies written (relative paths). */
    val conflicts: List<String> = emptyList(),
    /** Events to publish as a result (local winner, conflict copies). */
    val publish: List<NoteEvent> = emptyList(),
)

/** Result of turning a batch of local paths into events (Rust `agent::Batch`). */
data class Batch(val publish: List<NoteEvent> = emptyList(), val refusedDeletes: List<String> = emptyList())

/**
 * Synchronous decision core, a port of Rust `agent::AgentCore`. Not thread-safe: the engine calls
 * it from one worker thread.
 *
 * Echo suppression is content-based: after a write the incoming stamp is recorded together with the
 * file's SHA-256, so a later scan sees an unchanged file and publishes nothing. An event that comes
 * back to its own session (zenoh-kotlin has no `Locality::Remote`) does not beat the recorded
 * stamp, so [Conflict.decide] returns [Decision.Ignore].
 */
class SyncCore(
    val fs: VaultTree,
    val device: String,
    val state: AgentState,
    private val stateFile: File? = null,
    var maxDelete: Int = DEFAULT_MAX_DELETE,
    var minFiles: Int = DEFAULT_MIN_FILES,
    private val now: () -> Long = System::currentTimeMillis,
    /** Program part of the writer label of writes this app makes (`<device>/vaulthome`). */
    val program: String = DEFAULT_PROGRAM,
    /** Log sink for conflict details (the engine passes Logcat). */
    var log: (String) -> Unit = {},
) {
    private var dirty = false

    /** First scan of a fresh state: untracked files are [Cause.SEED]. */
    var seeding: Boolean = state.entries.isEmpty()

    /** rel -> sha of writes the app itself made outside the sync core ([noteOwnWrite]); consumed by the next scan. */
    private val ownWrites = HashMap<String, String>()

    /** `<device>/<program>`. */
    val ownWriter: String get() = "$device/$program"

    /**
     * The app is about to write [bytes] to [rel] itself (not through an incoming event): the change the next scan
     * finds is published as `<device>/<program>` (`local-edit`) instead of `<device>/external`.
     */
    fun noteOwnWrite(rel: String, bytes: ByteArray?) {
        ownWrites[rel] = bytes?.let { sha256Hex(it) } ?: ""
    }

    /**
     * Who changed [rel] on disk to [sha] (`null` = deleted): the app itself ([noteOwnWrite]) or someone else
     * (Obsidian, Termux, Autosync, a file manager) = `<device>/external`.
     */
    fun classify(rel: String, sha: String?, tracked: Boolean): Pair<String, String> {
        val own = ownWrites[rel]
        if (own != null && own == (sha ?: "")) {
            ownWrites.remove(rel)
            return ownWriter to if (sha == null) Cause.DELETE else Cause.LOCAL_EDIT
        }
        val cause = when {
            sha == null -> Cause.DELETE
            seeding && !tracked -> Cause.SEED
            else -> Cause.EXTERNAL_EDIT
        }
        return "$device/external" to cause
    }

    fun saveIfDirty() {
        if (dirty) {
            stateFile?.let { state.save(it) }
            dirty = false
        }
    }

    private fun record(stamp: Stamp) {
        state.record(stamp, fs.stat(stamp.path))
        dirty = true
    }

    /**
     * Whether applying blob reference [ev] needs its bytes (it would be written or saved as a conflict copy).
     * `false`: the stamp alone is enough (ignored, or the disk already holds these bytes).
     */
    fun needsBytes(ev: NoteEvent): Boolean {
        val path = ev.stamp.path
        if (VaultRules.isIgnored(path)) return false
        val disk = fs.read(path)
        if (disk is Disk.Skipped) return false
        val localTs = (disk as? Disk.Present)?.mtimeMs ?: now()
        return needsBytes(Conflict.decide(state.stamp(path), disk.sha, ev.stamp, device, localTs))
    }

    private fun needsBytes(d: Decision): Boolean =
        d is Decision.Apply || (d is Decision.KeepLocal && d.saveIncomingAs != null)

    /** Apply one event that arrived from the network. */
    fun onIncoming(ev: NoteEvent): Applied {
        val path = ev.stamp.path
        if (VaultRules.isIgnored(path)) return Applied()
        val disk = fs.read(path)
        if (disk is Disk.Skipped) return Applied()
        val localTs = (disk as? Disk.Present)?.mtimeMs ?: now()
        var d = Conflict.decide(state.stamp(path), disk.sha, ev.stamp, device, localTs)
        val inc = ev.stamp
        val incSha = if (inc.deleted) null else inc.sha256
        if (d is Decision.KeepLocal || (d is Decision.Apply && d.saveLocalAs != null)) {
            // Re-check right before making a copy: SAF reads are not atomic (a provider, Obsidian or Autosync may
            // have been mid-write when we hashed). If the live file now holds exactly the incoming bytes there is
            // no conflict; record the stamp (rule 1 of decide, applied to the fresh read).
            val fresh = fs.read(path)
            if (fresh !is Disk.Skipped && fresh.sha == incSha) {
                log("conflict on $path avoided: disk now holds the incoming bytes (${ev.size} B ${short(inc.sha256)}); adopted")
                d = Decision.Adopt
            }
        }
        if (ev.isBlobRef && needsBytes(d)) {
            // Never apply (or save as a conflict copy) a file whose bytes were not fetched and verified.
            throw IllegalStateException("$path: blob ${short(inc.sha256)} not fetched yet")
        }
        val conflicts = ArrayList<String>()
        val publish = ArrayList<NoteEvent>()
        var changed = false
        when (d) {
            is Decision.Ignore -> {}
            is Decision.Adopt -> record(ev.stamp)
            is Decision.Apply -> {
                if (d.saveLocalAs != null && disk is Disk.Present) {
                    val info = ConflictInfo(path, inc.sha256, ev.size, inc.origin, disk.sha256, disk.bytes.size.toLong(), device)
                    writeConflict(d.saveLocalAs, disk.bytes, info, conflicts, publish)
                }
                val c = ev.content
                if (c != null && !ev.stamp.deleted) fs.writeAtomic(path, c) else fs.remove(path)
                changed = true
                record(ev.stamp)
            }
            is Decision.KeepLocal -> {
                val (writer, why) = classify(path, disk.sha, state.stamp(path) != null)
                val local = d.local.by(writer, why)
                val content = (disk as? Disk.Present)?.bytes
                val c = ev.content
                if (d.saveIncomingAs != null && c != null) {
                    val info = ConflictInfo(path, local.sha256, content?.size?.toLong() ?: 0L, device, inc.sha256, c.size.toLong(), inc.origin)
                    writeConflict(d.saveIncomingAs, c, info, conflicts, publish)
                }
                record(local)
                publish += NoteEvent(FORMAT_VERSION, local, content)
            }
        }
        return Applied(d, changed, conflicts, publish)
    }

    /** Save the losing bytes under a conflict name and publish the copy; never for identical bytes. */
    private fun writeConflict(name: String, bytes: ByteArray, info: ConflictInfo, conflicts: MutableList<String>, publish: MutableList<NoteEvent>) {
        if (info.loserSha256 == info.winnerSha256) {
            log("conflict on ${info.of}: both sides identical, no copy")
            return
        }
        val slot = fs.conflictSlot(name, bytes)
        if (slot == null) {
            log("conflict on ${info.of}: copy $name already holds these bytes")
            return
        }
        fs.writeAtomic(slot, bytes)
        log(
            "conflict on ${info.of}: kept ${info.winnerSize} B ${short(info.winnerSha256)} from ${info.winnerOrigin}, " +
                "saved ${info.loserSize} B ${short(info.loserSha256)} from ${info.loserOrigin} as $slot by $ownWriter",
        )
        if (info.keptSmaller) log("WARN conflict on ${info.of}: the live path keeps the SMALLER side (${info.winnerSize} B < ${info.loserSize} B); check $slot")
        conflicts += slot
        localChange(slot, trustStat = false, label = ownWriter to Cause.CONFLICT_COPY)?.let { publish += it.withConflict(info) }
    }

    private fun short(sha: String) = sha.take(12)

    /** A local path may have changed. Always hashes. Returns the event to publish, if any. */
    fun onLocal(rel: String): NoteEvent? = localChange(rel, trustStat = false)

    /** `trustStat`: skip hashing when mtime+size match what was recorded (periodic rescans only). */
    private fun localChange(rel: String, trustStat: Boolean, label: Pair<String, String>? = null): NoteEvent? {
        if (VaultRules.isIgnored(rel) || !VaultKey.isValid(rel)) return null
        val entry = state.entries[rel]
        if (trustStat && entry != null && !entry.stamp.deleted) {
            val st = fs.stat(rel)
            if (st != null && entry.mtimeMs == st.first && entry.size == st.second) return null
        }
        val prev = entry?.stamp
        val prevSha = prev?.takeIf { !it.deleted }?.sha256
        val version = prev?.let { it.version + 1 } ?: 1
        return when (val disk = fs.read(rel)) {
            is Disk.Skipped -> null
            is Disk.Present -> {
                if (prevSha == disk.sha256) {
                    // Content unchanged (touch, or our own write): refresh the stat cache.
                    record(prev)
                    if (ownWrites[rel] == disk.sha256) ownWrites.remove(rel)
                    return null
                }
                val (writer, why) = label ?: classify(rel, disk.sha256, prev != null)
                // ts = file mtime (the edit time); only a tie-break after the version counter.
                val ts = if (disk.mtimeMs > 0) disk.mtimeMs else now()
                NoteEvent.put(rel, disk.bytes, device, version, prevSha, ts).by(writer, why).also { record(it.stamp) }
            }
            is Disk.Absent -> if (prev != null && !prev.deleted) {
                val (writer, why) = label ?: classify(rel, null, true)
                NoteEvent.delete(rel, device, version, prevSha, now()).by(writer, why).also { record(it.stamp) }
            } else {
                null
            }
        }
    }

    /** Process possibly-changed local paths with the mass-delete guard. */
    fun localBatch(paths: Collection<String>): Batch = batch(paths, trustStat = false)

    private fun batch(paths: Collection<String>, trustStat: Boolean): Batch {
        if (!fs.rootExists()) return Batch()
        // stat first (cheap on SAF); only a path with no regular file is read to confirm it is gone.
        val vanished = paths.filter { p ->
            state.stamp(p)?.let { !it.deleted } == true && fs.stat(p) == null && fs.read(p) is Disk.Absent
        }
        val refuse = vanished.size > maxDelete
        val vanishedSet = vanished.toHashSet()
        val out = ArrayList<NoteEvent>()
        for (p in paths) {
            if (refuse && p in vanishedSet) continue
            localChange(p, trustStat)?.let { out += it }
        }
        return Batch(out, if (refuse) vanished else emptyList())
    }

    /** Full scan: every file on disk plus every tracked path (with the min-files guard). */
    fun scan(): Batch {
        if (!fs.rootExists()) return Batch()
        val files = fs.scan()
        val liveTracked = state.entries.values.count { !it.stamp.deleted }
        if (files.size < minFiles && liveTracked >= minFiles) {
            lastScanRefused = "vault has ${files.size} files (< $minFiles) but $liveTracked are tracked; refusing scan"
            return Batch()
        }
        lastScanRefused = null
        val all = java.util.TreeSet<String> { a, b -> Utf8.compare(a, b) }
        all.addAll(files)
        all.addAll(state.entries.keys)
        return batch(all, trustStat = true)
    }

    /** Set when the last [scan] was refused by the min-files guard. */
    var lastScanRefused: String? = null
        private set

    /**
     * Given the hub's stamps: which paths to fetch (hub newer or unknown here) and which local states
     * to (re)publish (hub older or missing).
     */
    fun planCatchup(hub: List<Stamp>): Pair<List<String>, List<String>> {
        val hubMap = hub.associateBy { it.path }
        val fetch = ArrayList<String>()
        for (s in hub) {
            if (VaultRules.isIgnored(s.path)) continue
            val mine = state.stamp(s.path)
            if (mine == null || s.beats(mine)) fetch += s.path
        }
        val republish = ArrayList<String>()
        for ((p, e) in state.entries) {
            // Tracked before a filter change (rclone conflict files): never sent again.
            if (VaultRules.isIgnored(p)) continue
            val h = hubMap[p]
            if (h == null || e.stamp.beats(h)) republish += p
        }
        return fetch to republish
    }

    /**
     * Shortcut for a path [planCatchup] wants to fetch: when the disk already holds exactly the hub's
     * state, record the stamp without a network round trip. Same outcome as fetching the event and
     * [onIncoming] returning [Decision.Adopt] (first join of a phone whose tree came from bisync).
     */
    fun adoptIfSame(hubStamp: Stamp): Boolean {
        if (VaultRules.isIgnored(hubStamp.path) || !VaultKey.isValid(hubStamp.path)) return false
        val disk = fs.read(hubStamp.path)
        if (disk is Disk.Skipped) return false
        val want = if (hubStamp.deleted) null else hubStamp.sha256
        if (disk.sha != want) return false
        val mine = state.stamp(hubStamp.path)
        if (mine != null && !hubStamp.beats(mine)) return false
        record(hubStamp)
        return true
    }

    /** Rebuild the event for our recorded state of [rel]; if the disk moved on, treat it as a local change. */
    fun republish(rel: String): NoteEvent? {
        if (VaultRules.isIgnored(rel)) return null
        val recorded = state.stamp(rel) ?: return null
        // Keep who made this state; mark that it came back during catch-up.
        val st = recorded.by(recorded.writer ?: "$device/unknown", Cause.CATCHUP_REPUBLISH)
        val disk = fs.read(rel)
        return when {
            disk is Disk.Absent && st.deleted -> NoteEvent(FORMAT_VERSION, st, null)
            disk is Disk.Present && !st.deleted && disk.sha256 == st.sha256 -> NoteEvent(FORMAT_VERSION, st, disk.bytes)
            disk is Disk.Skipped -> null
            else -> onLocal(rel)
        }
    }

    companion object {
        const val DEFAULT_MAX_DELETE = 50
        const val DEFAULT_MIN_FILES = 3
        const val DEFAULT_PROGRAM = "vaulthome"
    }
}
