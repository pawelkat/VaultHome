package com.vaulthome.events

/** Which vault paths sync (Rust `vault::is_ignored`, `TMP_PREFIX`, `DEFAULT_MAX_BYTES`). */
object VaultRules {
    /** Prefix of the agent's own temp files (never synced). */
    const val TMP_PREFIX = ".vault-events-tmp-"

    /** Default per-file cap. Larger files stay on the Drive fallback path. */
    const val DEFAULT_MAX_BYTES: Long = 8L * 1024 * 1024

    private val TEMP_SUFFIXES = listOf(".tmp", ".swp", ".swx", ".part", ".crdownload", ".partial")

    /** Dot-directories/dotfiles (`.obsidian/`, `.trash/`, ...), editor/download temp files, our own temp files. */
    fun isIgnored(rel: String): Boolean {
        for (comp in rel.split('/')) if (comp.startsWith('.')) return true
        val name = rel.substringAfterLast('/')
        if (isRcloneConflictArtifact(name)) return true
        return name.endsWith('~') || name.startsWith("~$") || name.startsWith(".#") || TEMP_SUFFIXES.any { name.endsWith(it) }
    }

    /**
     * rclone bisync's own conflict losers (Rust `vault::is_rclone_conflict_artifact`): `<name>.conflict-<x>`,
     * `<name>..conflict-<x>` and the older `<name>.conflict<N>`. Every bisync device makes its own, so they are
     * never synced. The agent's own `<name> (conflict <origin> <date>)` copies still sync.
     */
    fun isRcloneConflictArtifact(fileName: String): Boolean {
        if (fileName.contains(".conflict-")) return true
        val i = fileName.lastIndexOf(".conflict")
        if (i < 0) return false
        val tail = fileName.substring(i + ".conflict".length)
        return tail.isNotEmpty() && tail.all { it in '0'..'9' }
    }

    /** `base` with ` n` before the extension (Rust `vault::numbered`). */
    fun numbered(base: String, n: Int): String {
        val i = base.lastIndexOf('/')
        val dir = if (i >= 0) base.substring(0, i + 1) else ""
        val file = if (i >= 0) base.substring(i + 1) else base
        val dot = file.lastIndexOf('.')
        return if (dot > 0) "$dir${file.substring(0, dot)} $n${file.substring(dot)}" else "$dir$file $n"
    }
}
