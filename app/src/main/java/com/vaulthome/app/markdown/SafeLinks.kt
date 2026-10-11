package com.vaulthome.app.markdown

/**
 * Only https links open, and only in the external browser (VaultHome itself never fetches them).
 * http, file:, content:, intent:, javascript:, obsidian:, relative paths and user@host are rejected.
 */
object SafeLinks {
    fun isSafe(uri: String): Boolean {
        val u = uri.trim()
        if (!u.startsWith("https://", ignoreCase = true) || u.any { it.isWhitespace() }) return false
        val host = u.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotEmpty() && '@' !in host
    }
}
