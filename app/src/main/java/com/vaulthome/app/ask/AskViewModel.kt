package com.vaulthome.app.ask

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vaulthome.app.VaultHomeApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AskUiState(
    val question: String = "",
    val phase: AskPhase? = null,
    val outcome: AskOutcome? = null,
    val asked: String = "",
    /** The cached answer is hidden while a source note opened from it is on screen. */
    val hidden: Boolean = false,
    /** Path of the source note opened from the answer; leaving it (back) restores the cached answer. */
    val returnTo: String? = null,
)

/**
 * Pure transitions for the in-memory Ask answer cache (unit-tested). Opening a source hides the last answer
 * instead of dropping it; going back from that source shows it again with the same question, markdown and sources.
 */
object AskCache {
    fun visible(s: AskUiState, askMode: Boolean) = askMode && s.outcome != null && !s.hidden

    fun openSource(s: AskUiState, path: String): AskUiState =
        if (s.outcome == null) s else s.copy(hidden = true, returnTo = path)

    /** Back from [path]: when it is the source opened from the answer, unhide the answer and report true. */
    fun returnFrom(s: AskUiState, path: String): Pair<AskUiState, Boolean> =
        if (s.outcome != null && s.hidden && s.returnTo == path) s.copy(hidden = false, returnTo = null) to true
        else s to false

    fun canReturnFrom(s: AskUiState, path: String) = s.outcome != null && s.hidden && s.returnTo == path

    /** Switching Search/Ask: the cached answer is shown again the next time Ask is active. */
    fun modeChanged(s: AskUiState): AskUiState = s.copy(hidden = false, returnTo = null)
}

class AskViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as VaultHomeApp).graph
    private val flow = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = flow
    private var job: Job? = null

    /** Settings "Test" button result. */
    private val healthFlow = MutableStateFlow<String?>(null)
    val healthText: StateFlow<String?> = healthFlow

    fun onQuestionChange(value: String) {
        flow.value = flow.value.copy(question = value)
    }

    fun ask() {
        val q = flow.value.question.trim()
        if (q.isEmpty()) return
        job?.cancel()
        flow.value = flow.value.copy(phase = AskPhase.CheckingNitro, outcome = null, asked = q, hidden = false, returnTo = null)
        job = viewModelScope.launch {
            val outcome = graph.askOrchestrator.ask(q) { phase -> flow.value = flow.value.copy(phase = phase) }
            flow.value = flow.value.copy(phase = null, outcome = outcome)
        }
    }

    /** Opening a source from the answer keeps the answer cached (hidden) so back from that note restores it. */
    fun openSource(path: String) {
        flow.value = AskCache.openSource(flow.value, path)
    }

    /** Called when navigating back from [path]; returns true when it restored the cached answer. */
    fun returnFrom(path: String): Boolean {
        val (next, restored) = AskCache.returnFrom(flow.value, path)
        flow.value = next
        return restored
    }

    fun modeChanged() {
        flow.value = AskCache.modeChanged(flow.value)
    }

    /** Drops the shown answer (the home body goes back to the note); keeps the question text. */
    fun dismissAnswer() {
        job?.cancel()
        flow.value = flow.value.copy(phase = null, outcome = null, hidden = false, returnTo = null)
    }

    /** Clear button in Ask mode: empties the field and the answer. */
    fun clear() {
        job?.cancel()
        flow.value = AskUiState()
    }

    fun cancel() {
        job?.cancel()
        flow.value = flow.value.copy(phase = null)
    }

    fun testNitro() {
        val url = graph.askSettings.state.value.nitroBaseUrl
        healthFlow.value = "Checking $url/health…"
        viewModelScope.launch {
            healthFlow.value = when (val h = graph.nitro.health(url)) {
                Health.Ok -> "Nitro is up"
                is Health.Loading -> "Nitro is loading: ${h.detail}"
                is Health.Down -> "Nitro unreachable: ${h.reason}"
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }
}
