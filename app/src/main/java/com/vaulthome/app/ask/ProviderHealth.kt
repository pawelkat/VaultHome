package com.vaulthome.app.ask

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Last `/health` per device: written by Ask and by the Providers page probe, read by [ProviderAvailability.health]. */
object ProviderHealth {
    private val mutable = MutableStateFlow<Map<String, HealthSample>>(emptyMap())
    val state: StateFlow<Map<String, HealthSample>> = mutable

    fun report(device: String, ok: Boolean, detail: String, now: Long = System.currentTimeMillis()) =
        mutable.update { it + (device to HealthSample(ok, detail, now)) }

    fun sample(device: String): HealthSample? = mutable.value[device]

    fun clear() { mutable.value = emptyMap() }
}

/** Probes every chat provider's `/health` in parallel (page open and every few seconds while it is open). */
object ProviderProbe {
    const val INTERVAL_MS = 15_000L

    suspend fun probe(gateway: NitroGateway, providers: List<Provider>, report: (String, Boolean, String) -> Unit = ProviderHealth::report) = coroutineScope {
        providers.filter { it.isChat }.distinctBy { it.device }.map { p ->
            async {
                var ok = false
                var detail = "no URL"
                for (url in p.urls()) {
                    val h = gateway.health(url)
                    when (h) {
                        Health.Ok -> { ok = true; detail = "up"; break }
                        is Health.Loading -> { ok = false; detail = "not ready (${h.detail.take(160)})" }
                        is Health.Down -> { ok = false; detail = h.reason }
                    }
                }
                report(p.device, ok, detail)
            }
        }.forEach { it.await() }
    }
}

/** Opens the Providers page from the "Remote · …" badge (one global flag, so no callback through three screens). */
object ProvidersNav {
    private val mutable = MutableStateFlow(false)
    val open: StateFlow<Boolean> = mutable
    fun show() { mutable.value = true }
    fun hide() { mutable.value = false }
}
