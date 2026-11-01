package com.vaulthome.app.ask

import com.vaulthome.app.index.ObsidianLinks
import com.vaulthome.app.search.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** One source under the answer. Path + heading come from the local index, never from a model. */
data class Citation(val path: String, val heading: String, val snippet: String) {
    val obsidianUri: String get() = ObsidianLinks.vaultFileUri(path)
}

enum class AskPhase { CheckingNitro, AskingNitro, Searching, Generating }

/** Why the Nitro path was not used. */
enum class NitroSkip { Disabled, Down, Loading, ChatFailed }

sealed interface AskOutcome {
    val citations: List<Citation>
    /** Non-null when local search could not run (e.g. model not downloaded) — citations are then empty. */
    val searchError: String?

    data class Nitro(
        val answer: String,
        val model: String?,
        override val citations: List<Citation>,
        override val searchError: String?,
    ) : AskOutcome

    /** PR-B: answer from the on-device generator. */
    data class Local(
        val answer: String,
        val nitro: NitroSkip,
        val nitroDetail: String,
        override val citations: List<Citation>,
        override val searchError: String?,
    ) : AskOutcome

    /** Neither Nitro nor a local generator: show the hits (current search behaviour). */
    data class SearchOnly(
        val nitro: NitroSkip,
        val nitroDetail: String,
        val localDetail: String,
        override val citations: List<Citation>,
        override val searchError: String?,
    ) : AskOutcome
}

/**
 * Ask flow: Nitro first (short `/health`, then `/v1/chat/completions`), local EmbeddingGemma search in
 * parallel for citations; on any Nitro failure fall through to [local] and finally to search-only.
 * Never throws for network trouble; only cancellation propagates.
 */
class AskOrchestrator(
    private val nitro: NitroGateway,
    private val settings: () -> AskSettings,
    private val search: suspend (String) -> List<SearchHit>,
    private val local: AnswerEngine = LocalAnswerEngineStub,
    private val maxCitations: Int = com.vaulthome.app.index.SearchConfig.ASK_TOP_K,
) {
    suspend fun ask(question: String, onPhase: (AskPhase) -> Unit = {}): AskOutcome = coroutineScope {
        val q = question.trim()
        require(q.isNotEmpty()) { "Empty question" }
        val config = settings()
        val hitsJob = async { runSearch(q) }

        var skip: NitroSkip
        var detail: String
        if (!config.nitroEnabled) {
            skip = NitroSkip.Disabled
            detail = "Nitro is off in Settings"
        } else {
            onPhase(AskPhase.CheckingNitro)
            when (val health = nitro.health(config.nitroBaseUrl)) {
                Health.Ok -> {
                    val context = if (config.groundNitro) hitsJob.await().citations else emptyList()
                    onPhase(AskPhase.AskingNitro)
                    try {
                        val reply = nitro.chat(config.nitroBaseUrl, NitroPrompt.messages(q, context))
                        val hits = hitsJob.await()
                        return@coroutineScope AskOutcome.Nitro(reply.content, reply.model, hits.citations, hits.error)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        skip = NitroSkip.ChatFailed
                        detail = e.message ?: "Nitro chat failed"
                    }
                }
                is Health.Loading -> { skip = NitroSkip.Loading; detail = health.detail }
                is Health.Down -> { skip = NitroSkip.Down; detail = health.reason }
            }
        }

        onPhase(AskPhase.Searching)
        val hits = hitsJob.await()
        if (hits.error == null && local.isReady()) {
            onPhase(AskPhase.Generating)
            try {
                val answer = local.answer(q, hits.citations)
                return@coroutineScope AskOutcome.Local(answer, skip, detail, hits.citations, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@coroutineScope AskOutcome.SearchOnly(skip, detail, e.message ?: "Local answer failed", hits.citations, null)
            }
        }
        AskOutcome.SearchOnly(skip, detail, local.unavailableReason, hits.citations, hits.error)
    }

    private class Hits(val citations: List<Citation>, val error: String?)

    private suspend fun runSearch(q: String): Hits = try {
        Hits(toCitations(search(q), maxCitations), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Hits(emptyList(), e.message ?: "Search failed")
    }

    companion object {
        /** Keeps the best hit per (path, heading), in score order. */
        fun toCitations(hits: List<SearchHit>, max: Int): List<Citation> =
            hits.sortedByDescending { it.score }
                .distinctBy { it.path to it.heading }
                .take(max)
                .map { Citation(it.path, it.heading, it.snippet) }
    }
}

/** Light system prompt mirroring the Nitro chat page; local hits are only attached when grounding is on. */
object NitroPrompt {
    const val SYSTEM =
        "You answer questions about Pawel's Obsidian vault (notes, projects, devices, people). " +
            "Be brief and concrete. If you do not know from the vault, say so. Do not invent note paths."

    fun messages(question: String, context: List<Citation>): List<ChatMessage> {
        val user = if (context.isEmpty()) {
            question
        } else {
            buildString {
                append("Vault excerpts (path › heading):\n")
                context.forEachIndexed { i, c ->
                    append("[${i + 1}] ").append(c.path)
                    if (c.heading.isNotBlank()) append(" › ").append(c.heading)
                    append("\n").append(c.snippet).append("\n\n")
                }
                append("Question: ").append(question)
            }
        }
        return listOf(ChatMessage("system", SYSTEM), ChatMessage("user", user))
    }
}
