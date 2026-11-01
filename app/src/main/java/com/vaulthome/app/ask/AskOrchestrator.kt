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
                    onPhase(AskPhase.AskingNitro)
                    try {
                        val reply = nitro.chat(config.nitroBaseUrl, NitroPrompt.messages(q))
                        // Nitro's answer cites its own sources; the on-device search was only a fallback.
                        hitsJob.cancel()
                        return@coroutineScope AskOutcome.Nitro(reply.content, reply.model, emptyList(), null)
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

/**
 * Thin client of Nitro's chat proxy: send only the question. The proxy injects `System/Nitro5 Qwen.md`, the live
 * Europe/Zurich date block ("Yesterday = …") and the `vault_search` tool, then runs the tool loop. A client system
 * prompt or stuffed local excerpts would compete with that (local EmbeddingGemma hits cannot resolve "yesterday",
 * so Qwen answered from whatever old Daily notes matched), so none are sent. Local hits stay citations only.
 */
object NitroPrompt {
    fun messages(question: String): List<ChatMessage> = listOf(ChatMessage("user", question))
}
