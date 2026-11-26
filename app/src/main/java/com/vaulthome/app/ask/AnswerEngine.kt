package com.vaulthome.app.ask

/**
 * On-device generator hook. PR-B plugs Gemma 4 E2B (LiteRT-LM, GPU then CPU) in here; the
 * orchestrator already calls it after Nitro fails and before falling back to search-only.
 */
interface AnswerEngine {
    /** True only when the generator model is downloaded and can run. */
    suspend fun isReady(): Boolean

    /** Answer from [citations] only (retrieve-then-generate). */
    suspend fun answer(question: String, citations: List<Citation>): String

    /** Short user-facing status when not ready (shown in the search-only fallback). */
    val unavailableReason: String
}

/** PR-A stub: no local generator yet. */
object LocalAnswerEngineStub : AnswerEngine {
    override suspend fun isReady() = false
    override suspend fun answer(question: String, citations: List<Citation>): String =
        throw UnsupportedOperationException("Local generator arrives in PR-B")
    override val unavailableReason = "On-device answers (Gemma 4 E2B) are coming in a later build"
}
