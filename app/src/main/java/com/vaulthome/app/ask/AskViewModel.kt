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
)

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
        flow.value = flow.value.copy(phase = AskPhase.CheckingNitro, outcome = null, asked = q)
        job = viewModelScope.launch {
            val outcome = graph.askOrchestrator.ask(q) { phase -> flow.value = flow.value.copy(phase = phase) }
            flow.value = flow.value.copy(phase = null, outcome = outcome)
        }
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
