package com.vaulthome.app.x4

/** Pure checks before anything is sent to the X4 (unit-tested). The text itself is never changed. */
object X4Payload {
    /** Firmware `TEXT_MAX` (characters, counted as Unicode code points like Rust `chars()`). */
    const val MAX_CHARS = 32_000

    /** The firmware reads at most 128 KiB per request (headers + JSON). Keep headroom for headers. */
    const val MAX_REQUEST_BYTES = 128 * 1024 - 2 * 1024

    sealed interface Check {
        data class Ok(val text: String) : Check
        data class Refused(val reason: String) : Check
    }

    fun isVaultKeep(path: String): Boolean {
        val p = path.replace('\\', '/').trimStart('/')
        return p.split('/').any { it.equals("VaultKeep", ignoreCase = true) }
    }

    /** Markdown that names a VaultKeep note (link or path) is not put on the device. */
    fun mentionsVaultKeep(markdown: String): Boolean =
        Regex("""(?i)(^|[\s\[(/|])VaultKeep(/|\]|\||$)""", RegexOption.MULTILINE).containsMatchIn(markdown)

    fun charCount(text: String): Int = text.codePointCount(0, text.length)

    /** A note from the vault: refuses VaultKeep paths, empty notes and over-long ones. */
    fun forNote(path: String, markdown: String): Check = when {
        isVaultKeep(path) -> Check.Refused("VaultKeep notes are never sent to the X4")
        else -> common(markdown)
    }

    /** The Ask answer as shown in the panel. */
    fun forAnswer(markdown: String): Check = when {
        mentionsVaultKeep(markdown) -> Check.Refused("This answer cites VaultKeep, so it is not sent to the X4")
        else -> common(markdown)
    }

    private fun common(markdown: String): Check {
        if (markdown.isBlank()) return Check.Refused("Nothing to send")
        val n = charCount(markdown.trim())
        if (n > MAX_CHARS) return Check.Refused("Too long for the X4: %,d characters (max %,d). Not sent.".format(n, MAX_CHARS))
        return Check.Ok(markdown)
    }
}
