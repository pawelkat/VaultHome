package com.vaulthome.panel

import java.security.MessageDigest

/**
 * Content hash of the *parsed* panel (SHA-256, hex). Whitespace or formatting-only edits of the
 * Markdown do not change it, so the phone doesn't republish and the watch doesn't buzz again.
 */
object PanelHash {
    fun of(note: PanelNote): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(PanelCodec.encode(note).toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Dedupe rules on both ends of the Data Layer. */
object PanelDedupe {
    /** Phone: publish when forced (app start) or when the parsed content changed since the last publish. */
    fun shouldPublish(lastPublishedHash: String?, newHash: String, force: Boolean = false): Boolean =
        newHash.isNotBlank() && (force || newHash != lastPublishedHash)

    /** Watch: (re)post the notification only for a hash it has not notified yet. */
    fun shouldNotify(lastNotifiedHash: String?, newHash: String?): Boolean =
        !newHash.isNullOrBlank() && newHash != lastNotifiedHash
}
