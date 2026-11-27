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
        /** Which chat proxy answered (device label, e.g. `TowerX` or `Nitro`). */
        val host: String = "Nitro",
        /** Set when the app applied its own token cap (the nitro5 1024 fallback when `n_ctx` is missing). */
        val sizingNote: String? = null,
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
 * Ask flow: the chat providers chosen by [ProviderRouting] (pinned, else the auto-pick rule, then any other ready one;
 * no host is hardcoded first) are tried in turn with a short `/health`, then `/v1/chat/completions` sized from the
 * provider's manifest. Local EmbeddingGemma search runs in parallel for citations; when no provider answers it falls
 * through to [local] and finally to search-only. Never throws for network trouble; only cancellation propagates.
 */
class AskOrchestrator(
    private val nitro: NitroGateway,
    private val settings: () -> AskSettings,
    private val search: suspend (String) -> List<SearchHit>,
    private val local: AnswerEngine = LocalAnswerEngineStub,
    private val maxCitations: Int = com.vaulthome.app.index.SearchConfig.ASK_TOP_K,
    /** Providers from caps manifests (`device -> providers`); devices without one use the Settings URLs. */
    private val manifests: () -> Map<String, List<Provider>> = { emptyMap() },
    /** Zenoh presence token `vault/presence/<device>`: true/false, `null` = unknown or no Zenoh session. */
    private val deviceAlive: (String) -> Boolean? = { null },
    /** Last `/health` per device (temporary S24 availability source). */
    private val healthSample: (String) -> HealthSample? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    /** Reports each `/health` result per device (for the Settings indicators). */
    private val onHealth: (String, Boolean, String) -> Unit = { _, _, _ -> },
    /** devices.json (and the built-in list); used with [routerEndpoints] to resolve chat-proxy URLs. */
    private val registry: () -> List<com.vaulthome.app.devices.RegDevice> = { com.vaulthome.app.devices.DeviceRegistryJson.bundled().devices },
    /** Configured Zenoh connect endpoints, for the tailnet suffix. */
    private val routerEndpoints: () -> List<String> = { emptyList() },
) {
    /** The providers [ask] would try right now, in order (also used by the badge and the Providers panel). */
    fun routing(): Routing {
        val s = settings()
        val sources = ProviderAvailability.chain(s, deviceAlive, healthSample, now)
        val all = ProviderRouting.all(manifests(), s, registry = registry(), suffix = TailnetUrls.suffix(routerEndpoints()))
        return ProviderRouting.route(all, s.pinnedProviderId, s.skills) { ProviderAvailability.of(it, s.skills, sources) }
    }

    suspend fun ask(question: String, onPhase: (AskPhase) -> Unit = {}): AskOutcome = coroutineScope {
        val q = question.trim()
        require(q.isNotEmpty()) { "Empty question" }
        val config = settings()
        val hitsJob = async { runSearch(q) }
        val routing = routing()

        // Earlier providers that failed: "Label: reason; ", kept in front of the last reason.
        var notes = ""
        var skip = NitroSkip.Disabled
        // Hosts switched off in Settings are the user's choice, not news; the others say why they were left out.
        routing.skipped.filter { it.reason != Availability.Disabled.label }.forEach { notes += "${it.provider.hostLabel}: ${it.reason}; " }
        var detail = if (notes.isEmpty()) "No chat provider is enabled in Settings" else ""
        for ((index, p) in routing.order.withIndex()) {
            onPhase(AskPhase.CheckingNitro)
            val (health, url) = probe(p)
            onHealth(p.device, health == Health.Ok, describe(health))
            var failure: String
            when (health) {
                Health.Ok -> {
                    onPhase(AskPhase.AskingNitro)
                    try {
                        val (options, sizing) = ProviderRouting.options(p, config.skills)
                        val reply = nitro.chat(url, NitroPrompt.messages(q), options)
                        // The provider's answer cites its own sources; the on-device search was only a fallback.
                        hitsJob.cancel()
                        return@coroutineScope AskOutcome.Nitro(reply.content, reply.model, emptyList(), null, host = p.hostLabel, sizingNote = sizing)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        skip = NitroSkip.ChatFailed
                        failure = e.message ?: "chat failed"
                    }
                }
                is Health.Loading -> { skip = NitroSkip.Loading; failure = health.detail }
                is Health.Down -> { skip = NitroSkip.Down; failure = health.reason }
            }
            if (index < routing.order.lastIndex) notes += "${p.hostLabel}: $failure; " else detail = failure
        }
        if (routing.order.isNotEmpty() && detail.isEmpty()) detail = "no answer"
        detail = (notes + detail).trim().trimEnd(';').trim()
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

    private fun describe(h: Health): String = when (h) {
        Health.Ok -> "up"
        is Health.Loading -> "not ready (${h.detail.take(160)})"
        is Health.Down -> h.reason.replace("Nitro", "proxy")
    }

    private class Hits(val citations: List<Citation>, val error: String?)

    private suspend fun runSearch(q: String): Hits = try {
        Hits(toCitations(search(q), maxCitations), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Hits(emptyList(), e.message ?: "Search failed")
    }


    /**
     * `/health` on the tailnet (or manifest) URL first; if that is down and devices.json listed a lan endpoint,
     * try the lan URL. Over Tailscale the first packet often has to wake the path (DERP/peer setup), so one slow
     * `/health` must not end the ask: retry a timeout once, and treat a second timeout as "unknown" and try chat.
     */
    private suspend fun probe(p: Provider): Pair<Health, String> {
        var last: Health = Health.Down("no URL")
        var lastUrl = p.baseUrl
        for (url in p.urls()) {
            lastUrl = url
            var health = nitro.health(url)
            if (health is Health.Down && health.reason.contains("timed out")) {
                health = nitro.health(url)
                if (health is Health.Down && health.reason.contains("timed out")) health = Health.Ok
            }
            last = health
            if (health is Health.Down) continue
            return health to url
        }
        return last to lastUrl
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
