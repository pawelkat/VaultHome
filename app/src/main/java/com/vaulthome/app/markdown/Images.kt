package com.vaulthome.app.markdown

/** Image helpers that stay pure Kotlin so they can be unit tested. */
object Images {
    val EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif", "heic")

    fun isImage(name: String): Boolean =
        name.substringBefore('?').substringAfterLast('.', "").lowercase() in EXTENSIONS

    private val embed = Regex("""!\[\[([^\]|]+?)(?:\|([^\]]*))?]]""")
    private val std = Regex("""!\[([^\]]*)]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")

    /** Parses a line consisting only of image embeds; null if the line holds anything else or a non-image embed. */
    fun parseLine(line: String): List<MdBlock.Image>? {
        val t = line.trim()
        if (!t.startsWith("![")) return null
        val out = mutableListOf<MdBlock.Image>()
        var pos = 0
        while (pos < t.length) {
            if (t[pos].isWhitespace()) { pos++; continue }
            val e = embed.matchAt(t, pos)
            if (e != null) {
                val target = e.groupValues[1].trim()
                if (!isImage(target)) return null
                val opt = e.groupValues[2].trim()
                val w = Regex("^(\\d+)(?:x\\d+)?$").find(opt)?.groupValues?.get(1)?.toIntOrNull()
                out += MdBlock.Image(target, if (w == null) opt.ifEmpty { target } else target, w)
                pos = e.range.last + 1; continue
            }
            val s = std.matchAt(t, pos) ?: return null
            var alt = s.groupValues[1]
            val w = Regex("\\|\\s*(\\d+)(?:x\\d+)?\\s*$").find(alt)
            val width = w?.groupValues?.get(1)?.toIntOrNull()
            if (w != null) alt = alt.substring(0, w.range.first).trim()
            val src = s.groupValues[2].replace("%20", " ").let { if (it.startsWith("http", true)) s.groupValues[2] else it }
            out += MdBlock.Image(src, alt.ifEmpty { src.substringAfterLast('/') }, width)
            pos = s.range.last + 1
        }
        return out.ifEmpty { null }
    }

    /** Vault-relative image lookup: exact path, then path suffix, then filename anywhere (case-insensitive). */
    fun resolve(target: String, paths: Collection<String>): String? {
        val t = target.trim().trimStart('/').removePrefix("./").lowercase()
        if (t.isEmpty()) return null
        val sorted = paths.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it.length }, { it }))
        sorted.firstOrNull { it.lowercase() == t }?.let { return it }
        sorted.firstOrNull { it.lowercase().endsWith("/$t") }?.let { return it }
        val name = t.substringAfterLast('/')
        return sorted.firstOrNull { it.lowercase().substringAfterLast('/') == name }
    }

    /** Power-of-two BitmapFactory sample size so the decoded width is >= [reqWidth]. */
    fun sampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, maxPixels: Long = 16_000_000L): Int {
        var s = 1
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        while (srcWidth / (s * 2) >= reqWidth.coerceAtLeast(1)) s *= 2
        while ((srcWidth.toLong() / s) * (srcHeight.toLong() / s) > maxPixels) s *= 2
        return s
    }
}
