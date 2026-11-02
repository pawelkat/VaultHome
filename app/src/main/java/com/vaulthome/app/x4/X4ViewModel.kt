package com.vaulthome.app.x4

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vaulthome.app.VaultHomeApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Detection state of the X4 at the configured address. Never guessed: only what the last probe saw. */
sealed interface X4Detect {
    data object Unknown : X4Detect
    data class Probing(val url: String) : X4Detect
    data class Detected(val url: String, val status: X4Status) : X4Detect
    data class NotFound(val url: String, val reason: String) : X4Detect
}

data class X4UiState(val detect: X4Detect = X4Detect.Unknown, val sending: Boolean = false) {
    val detected: Boolean get() = detect is X4Detect.Detected
}

/** Pure wording for the feedback toast / Settings line (unit-tested). */
object X4Text {
    fun sent(r: X4ShowResult): String {
        val pages = r.pages?.let { "$it page" + if (it == 1) "" else "s" } ?: "the note"
        val cut = if (r.cut) " — cut at the 24-page limit" else ""
        return "Sent to X4: $pages$cut. The glass refreshes in a few seconds."
    }

    fun detect(d: X4Detect): String = when (d) {
        X4Detect.Unknown -> "X4 not checked yet"
        is X4Detect.Probing -> "Looking for the X4 at ${d.url}…"
        is X4Detect.Detected -> "X4 found at ${d.url}" + (d.status.stored?.let { " · stored $it" } ?: "")
        is X4Detect.NotFound -> "X4 not found: ${d.reason}"
    }
}

class X4ViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as VaultHomeApp).graph
    private val flow = MutableStateFlow(X4UiState())
    val state: StateFlow<X4UiState> = flow
    private val messages = Channel<String>(Channel.BUFFERED)
    /** One-shot feedback (toast). */
    val events = messages.receiveAsFlow()
    private var probeJob: Job? = null
    private var lastProbeAt = 0L
    private var lastProbeUrl: String? = null

    /** Probes the configured address. Throttled to once per [minGapMs] for the same URL unless [force]. */
    fun probe(force: Boolean = false, minGapMs: Long = 15_000) {
        if (flow.value.sending || probeJob?.isActive == true) return
        val url = graph.x4Settings.state.value.baseUrl
        val now = System.currentTimeMillis()
        if (!force && url == lastProbeUrl && now - lastProbeAt < minGapMs) return
        lastProbeAt = now; lastProbeUrl = url
        flow.value = flow.value.copy(detect = X4Detect.Probing(url))
        probeJob = viewModelScope.launch {
            val next = try {
                X4Detect.Detected(url, graph.x4.probe(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                X4Detect.NotFound(url, e.message ?: "unreachable")
            }
            flow.value = flow.value.copy(detect = next)
        }
    }

    fun sendNote(path: String, markdown: String) = send(X4Payload.forNote(path, markdown))

    fun sendAnswer(markdown: String) = send(X4Payload.forAnswer(markdown))

    private fun send(check: X4Payload.Check) {
        when (check) {
            is X4Payload.Check.Refused -> { messages.trySend(check.reason); return }
            is X4Payload.Check.Ok -> Unit
        }
        if (flow.value.sending) return
        val url = graph.x4Settings.state.value.baseUrl
        probeJob?.cancel()
        flow.value = flow.value.copy(sending = true)
        viewModelScope.launch {
            try {
                val result = graph.x4.showText(url, check.text)
                flow.value = flow.value.copy(detect = X4Detect.Detected(url, result.status ?: X4Status("")))
                messages.trySend(X4Text.sent(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: "unreachable"
                if (e !is X4Exception || !reason.startsWith("X4:")) {
                    flow.value = flow.value.copy(detect = X4Detect.NotFound(url, reason))
                }
                messages.trySend("Not sent: $reason")
            } finally {
                flow.value = flow.value.copy(sending = false)
                lastProbeAt = System.currentTimeMillis(); lastProbeUrl = url
            }
        }
    }
}
