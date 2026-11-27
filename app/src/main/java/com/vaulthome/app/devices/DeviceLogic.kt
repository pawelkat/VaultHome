package com.vaulthome.app.devices

import com.vaulthome.app.ask.HealthSample
import com.vaulthome.app.towerx.PowerAction
import com.vaulthome.app.towerx.TowerXKeys
import com.vaulthome.app.towerx.TowerXState

/** Live status of a device, from the mechanisms the app already has (Zenoh presence/liveliness and chat proxy `/health`). */
enum class Live(val label: String) { Online("online"), Offline("offline"), Unknown("unknown") }

object DeviceLive {
    const val HEALTH_TTL_MS = 60_000L

    /**
     * A Zenoh token decides when there is one (`alive` non-null; `null` = no session or no such token). Otherwise a
     * fresh `/health` sample does (ok = online, failed = offline); a stale or missing one is unknown.
     */
    fun of(alive: Boolean?, health: HealthSample?, now: Long): Live = when {
        alive == true -> Live.Online
        alive == false -> Live.Offline
        health != null && now - health.atMs <= HEALTH_TTL_MS -> if (health.ok) Live.Online else Live.Offline
        else -> Live.Unknown
    }

    /**
     * Token for [d]: its own liveliness key (ESP32 board, from [TowerXMonitor]'s state) or its `vault/presence/<id>`
     * ([presence] lookup, i.e. ProviderMonitor). `null` when there is no source.
     */
    fun alive(d: RegDevice, tx: TowerXState, presence: (String) -> Boolean?): Boolean? {
        d.liveliness?.let { return if (it == TowerXKeys.POWER_TOKEN) tx.espAlive else null }
        return presence(d.presenceId)
    }
}

/** Renders the registry's power descriptor into buttons, dialogs and notes. */
object PowerUi {
    fun action(name: String): PowerAction? = when (name) {
        "on" -> PowerAction.On
        "off" -> PowerAction.Off
        "status" -> PowerAction.Status
        else -> null
    }

    /** Buttons in a fixed order (Status, On, Off), only those the descriptor lists. */
    fun buttons(p: PowerDescriptor): List<PowerAction> =
        listOf("status", "on", "off").filter { it in p.actions }.mapNotNull(::action)

    fun needsConfirm(p: PowerDescriptor, a: PowerAction): Boolean = when (a) {
        PowerAction.On -> "on" in p.confirm
        PowerAction.Off -> "off" in p.confirm
        PowerAction.Status -> "status" in p.confirm
    }

    /** The board's liveliness: only the TowerX board has a source today. */
    fun controllerAlive(p: PowerDescriptor, tx: TowerXState): Boolean? =
        if (p.key == TowerXKeys.POWER_TOKEN) tx.espAlive else null

    fun enabled(p: PowerDescriptor, tx: TowerXState): Boolean = !tx.busy && controllerAlive(p, tx) == true

    fun confirmTitle(deviceName: String, a: PowerAction) = "${a.label} $deviceName?"

    fun confirmText(deviceName: String, p: PowerDescriptor, a: PowerAction): String = when (a) {
        PowerAction.Off ->
            (if (p.forceOffHoldS > 0) "Holds the power button for ${p.forceOffHoldS} s. " else "") + "Unsaved work on $deviceName is lost."
        else -> "Send ${a.label.lowercase()} to $deviceName?"
    }

    fun cooldownNote(p: PowerDescriptor): String? =
        p.cooldownS.takeIf { it > 0 }?.let { "After a pulse the board ignores another for $it s." }

    fun label(a: PowerAction): String = a.label
}

/** Wording of the "last seen" line. */
object LastSeen {
    fun text(atMs: Long?, now: Long, live: Live): String = when {
        live == Live.Online -> "now"
        atMs == null -> "never seen"
        else -> {
            val s = ((now - atMs) / 1000).coerceAtLeast(0)
            when {
                s < 60 -> "just now"
                s < 3600 -> "${s / 60} min ago"
                s < 86_400 -> "${s / 3600} h ago"
                else -> "${s / 86_400} d ago"
            }
        }
    }
}
