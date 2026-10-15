package com.vaulthome.app.markdown

/** External URL helpers: bare-URL trimming/normalisation and YouTube classification (pure Kotlin). */
object Links {
    /** Strips trailing sentence punctuation and an unbalanced closing paren from a bare URL. */
    fun trimBare(raw: String): String {
        var u = raw
        while (u.isNotEmpty()) {
            val c = u.last()
            if (c in ".,;:!?'\"*_") { u = u.dropLast(1); continue }
            if (c == ')' && u.count { it == ')' } > u.count { it == '(' }) { u = u.dropLast(1); continue }
            break
        }
        return u
    }

    /** `www.x` -> `https://www.x`; other values are returned trimmed. */
    fun normalize(url: String): String {
        val u = url.trim()
        return if (u.startsWith("www.", ignoreCase = true)) "https://$u" else u
    }

    private fun host(url: String): String? {
        val u = normalize(url)
        val rest = when {
            u.startsWith("https://", true) -> u.substring(8)
            u.startsWith("http://", true) -> u.substring(7)
            else -> return null
        }
        return rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            .substringBefore(':').lowercase()
    }

    private val ytHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com")

    /** True for youtube.com (www/m/music, incl. /shorts/), youtu.be. */
    fun isYouTube(url: String): Boolean = host(url) in ytHosts

    const val NEWPIPE = "org.schabi.newpipe"
    const val YOUTUBE = "com.google.android.youtube"
}
