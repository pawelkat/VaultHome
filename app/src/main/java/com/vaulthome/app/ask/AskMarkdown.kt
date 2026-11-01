package com.vaulthome.app.ask

/**
 * Turns an [AskOutcome] into markdown for the home note panel (rendered by `RenderedNote` / `MarkdownText`).
 * First line says where the answer came from (remote Nitro vs on-device vs search-only); sources are wikilinks
 * built from local-index paths only, so a tap opens the note in-app.
 */
object AskMarkdown {
    fun phaseText(phase: AskPhase) = when (phase) {
        AskPhase.CheckingNitro -> "Checking Nitro…"
        AskPhase.AskingNitro -> "Asking Nitro…"
        AskPhase.Searching -> "Searching the vault…"
        AskPhase.Generating -> "Answering on the phone…"
    }

    /** One-line source label, also used as the panel badge. */
    fun sourceLabel(outcome: AskOutcome): String = when (outcome) {
        is AskOutcome.Nitro -> "Answer from Nitro (remote)" + (outcome.model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        is AskOutcome.Local -> "On-device answer (local)"
        is AskOutcome.SearchOnly -> "No answer · matching notes (local search)"
    }

    fun format(question: String, outcome: AskOutcome): String = buildString {
        append("**").append(escapeInline(sourceLabel(outcome))).append("**\n\n")
        if (question.isNotBlank()) append("*Q: ").append(escapeInline(oneLine(question))).append("*\n\n")
        when (outcome) {
            is AskOutcome.Nitro -> append(body(outcome.answer)).append("\n\n")
            is AskOutcome.Local -> {
                append("> Nitro skipped: ").append(escapeInline(oneLine(outcome.nitroDetail))).append("\n\n")
                append(body(outcome.answer)).append("\n\n")
            }
            is AskOutcome.SearchOnly -> {
                append("> Nitro: ").append(escapeInline(oneLine(outcome.nitroDetail)))
                if (outcome.localDetail.isNotBlank()) append(" · On-device: ").append(escapeInline(oneLine(outcome.localDetail)))
                append("\n\n")
            }
        }
        outcome.searchError?.let { append("*Local search failed: ").append(escapeInline(oneLine(it))).append("*\n\n") }
        if (outcome.citations.isNotEmpty()) {
            append("---\n\n### Sources\n\n")
            outcome.citations.forEach { c -> append("- ").append(citationLink(c)).append('\n') }
        } else if (outcome.searchError == null) {
            append("*No matching notes in the local index.*\n")
        }
    }.trimEnd() + "\n"

    /** `[[path|path › heading]]`; paths that would break wikilink syntax fall back to code text. */
    fun citationLink(c: Citation): String {
        val label = if (c.heading.isBlank()) c.path else "${c.path} › ${c.heading}"
        val safeLabel = label.replace("]", "").replace("|", "/").replace("[", "")
        return if (c.path.contains("]") || c.path.contains("|") || c.path.contains("[") || c.path.contains("#")) "`${c.path.replace("`", "'")}`"
        else "[[${c.path}|$safeLabel]]"
    }

    /** Model text is usually plain or light markdown: normalise newlines; a leading `---` must not read as frontmatter. */
    fun body(answer: String): String {
        val t = answer.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (t.isEmpty()) return "*(empty answer)*"
        return if (t.startsWith("---")) "\u200B$t" else t
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** The inline parser has no backslash escapes: neutralise emphasis, code and wikilink markers instead. */
    fun escapeInline(s: String) = s.replace("*", "\u2217").replace("__", "_\u200B_").replace("`", "'")
        .replace("[[", "[\u200B[")
}
