package com.vaulthome.app.ask

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** Live view for the Providers panel and Ask routing. */
data class ProviderState(
    /** A Zenoh session is running (event sync on). */
    val session: Boolean = false,
    /** `vault/presence/<device>` tokens seen: device -> alive. A device never seen is simply absent (unknown). */
    val alive: Map<String, Boolean> = emptyMap(),
    /** Last good `vault/caps/<device>` manifest per device. */
    val manifests: Map<String, List<Provider>> = emptyMap(),
    /** Last caps problem per device (no reply, bad JSON, unsupported schema). */
    val errors: Map<String, String> = emptyMap(),
)

/**
 * Follows presence tokens and queries `vault/caps/<device>` once a device is alive; drops the manifest when it leaves.
 * Presence stays as it is and no other keys are used (brief: "do not invent other keys"). The event sync service feeds
 * [onToken] from a wildcard liveliness subscription and sets [caller] while its Zenoh session runs.
 */
object ProviderMonitor {
    private val mutable = MutableStateFlow(ProviderState())
    val state: StateFlow<ProviderState> = mutable

    /** `(selector, payload, timeoutMs) -> reply`, set while a Zenoh session runs. */
    @Volatile var caller: ((String, String?, Long) -> String?)? = null

    /** This phone's presence id: its own caps are not queried. */
    @Volatile var selfDevice: String = ""

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "provider-caps").apply { isDaemon = true } }

    fun alive(device: String): Boolean? = mutable.value.alive[device]

    /** Presence prefix without the trailing `*`, e.g. `vault/presence/`. */
    private const val PREFIX = "vault/presence/"

    fun deviceOf(key: String): String? = key.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() && it != "*" && '/' !in it }

    /** Transport thread: never blocks; the caps query runs on [io]. */
    fun onToken(key: String, alive: Boolean) {
        if (key == "vault/presence/*") { if (!alive) onSessionClosed(); return }
        val device = deviceOf(key) ?: return
        mutable.update { s ->
            s.copy(
                session = true,
                alive = s.alive + (device to alive),
                manifests = if (alive) s.manifests else s.manifests - device,
                errors = if (alive) s.errors else s.errors - device,
            )
        }
        if (alive && device != selfDevice) io.execute { refresh(device) }
    }

    /** Asks `vault/caps/<device>` now (also for a manual refresh); blocking, call off the main thread. */
    fun refresh(device: String) {
        val c = caller ?: return
        val reply = runCatching { c(ProviderManifest.key(device), null, 5_000) }.getOrNull()
        val parsed = if (reply == null) Result.failure(IllegalStateException("no caps reply")) else ProviderManifest.parse(reply)
        mutable.update { s ->
            // Left while we waited: do not resurrect it.
            if (s.alive[device] != true) s
            else parsed.fold(
                { list -> s.copy(manifests = s.manifests + (device to list), errors = s.errors - device) },
                { e -> s.copy(errors = s.errors + (device to (e.message ?: "bad caps"))) },
            )
        }
    }

    fun onSessionStarted() = mutable.update { it.copy(session = true) }

    /** Session closed: everything is unknown again. */
    fun onSessionClosed() { mutable.value = ProviderState() }
}
