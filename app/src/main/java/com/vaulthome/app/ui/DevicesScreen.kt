package com.vaulthome.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.ask.ProviderHealth
import com.vaulthome.app.ask.ProviderMonitor
import com.vaulthome.app.ask.ProviderRouting
import com.vaulthome.app.devices.DeviceLive
import com.vaulthome.app.devices.DeviceProbe
import com.vaulthome.app.devices.DeviceSeenStore
import com.vaulthome.app.devices.LastSeen
import com.vaulthome.app.devices.Live
import com.vaulthome.app.devices.PowerDescriptor
import com.vaulthome.app.devices.PowerUi
import com.vaulthome.app.devices.RegDevice
import com.vaulthome.app.towerx.PowerAction
import com.vaulthome.app.towerx.TowerXMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Green = Color(0xFF5CC489)

/**
 * Devices page (top bar icon, Providers page, Settings link). The list comes from `app_settings/VaultEvents/devices.json`
 * (see [com.vaulthome.app.devices.DeviceRegistryRepo]); status uses the existing mechanisms: Zenoh presence/liveliness
 * and the chat proxy `/health`. Power buttons are rendered from each device's `power` descriptor.
 */
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val graph = (ctx.applicationContext as VaultHomeApp).graph
    val loaded by graph.deviceRegistry.state.collectAsStateWithLifecycle()
    val ev by graph.eventSync.state.collectAsStateWithLifecycle()
    val suffix = com.vaulthome.app.ask.TailnetUrls.suffix(ev.connect)
    val ps by ProviderMonitor.state.collectAsStateWithLifecycle()
    val health by ProviderHealth.state.collectAsStateWithLifecycle()
    val tx by TowerXMonitor.state.collectAsStateWithLifecycle()
    val seenStore = remember { DeviceSeenStore(ctx) }
    val seen by seenStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmOff by remember { mutableStateOf<Triple<RegDevice, PowerDescriptor, PowerAction>?>(null) }

    // Re-read the vault file, then probe /health on open and every few seconds while the page is shown.
    LaunchedEffect(Unit) { graph.deviceRegistry.reload() }
    LaunchedEffect(loaded.registry, suffix) {
        while (true) {
            DeviceProbe.probe(graph.nitro, loaded.registry.devices, suffix) { id, ok, detail ->
                ProviderHealth.report(id, ok, detail)
                if (id == ProviderRouting.TOWERX_DEVICE) TowerXMonitor.onHealth(ok, detail)
            }
            delay(DeviceProbe.INTERVAL_MS)
        }
    }

    val now = System.currentTimeMillis()
    val live = loaded.registry.devices.associate { d ->
        d.id to DeviceLive.of(DeviceLive.alive(d, tx) { ps.alive[it] }, health[d.id], now)
    }
    LaunchedEffect(live) { live.forEach { (id, l) -> if (l == Live.Online) seenStore.seen(id, System.currentTimeMillis()) } }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackArrow(onBack)
            Text("Devices", style = MaterialTheme.typography.titleLarge, color = Ink)
        }
        if (!ps.session) Text("Event sync is off: no Zenoh presence. Status comes from /health probes only and power is unavailable.", color = Muted, style = MaterialTheme.typography.bodySmall)

        loaded.registry.devices.forEach { d ->
            val l = live[d.id] ?: Live.Unknown
            val ink = if (l == Live.Online) Ink else Muted.copy(alpha = 0.7f)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${d.name} · ${d.kind}", style = MaterialTheme.typography.titleMedium, color = ink)
                val sample = health[d.id]
                val resolved = if (com.vaulthome.app.ask.DeviceUrls.isChatHost(d) || d.kind == RegDevice.KIND_PC)
                    com.vaulthome.app.ask.DeviceUrls.resolve(d.id, suffix, d) else null
                val status = buildList {
                    add(l.label)
                    add("last seen " + LastSeen.text(seen[d.id], now, l))
                    if (resolved != null && sample != null && !sample.ok) add(sample.detail.take(80))
                }.joinToString(" · ")
                Text(status, color = if (l == Live.Online) Green else Muted, style = MaterialTheme.typography.bodySmall)
                resolved?.let { r ->
                    Text("chat proxy  ${r.primary}" + if (r.source == com.vaulthome.app.ask.UrlSource.Derived) " (derived)" else " (${r.source.name.lowercase()})", color = Muted, style = MaterialTheme.typography.bodySmall)
                    r.lan?.let { Text("lan  $it", color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
                d.endpoints.filter { (k, _) -> k != "lan" && k != "tailnet" }.forEach { (k, v) ->
                    Text("$k  $v", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                d.liveliness?.let { Text("Zenoh  $it", color = Muted, style = MaterialTheme.typography.bodySmall) }
                d.via?.let { Text("via  $it", color = Muted, style = MaterialTheme.typography.bodySmall) }
                d.note?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }

                d.power?.let { p ->
                    val controller = loaded.registry.byId(p.controller)
                    val boardAlive = PowerUi.controllerAlive(p, tx)
                    Text(
                        "Power via ${controller?.name ?: p.controller}: " + when (boardAlive) { true -> "online"; false -> "offline"; null -> "unknown" },
                        color = if (boardAlive == true) Ink else Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PowerUi.buttons(p).forEach { a ->
                            OutlinedButton(enabled = PowerUi.enabled(p, tx), onClick = {
                                if (PowerUi.needsConfirm(p, a)) confirmOff = Triple(d, p, a)
                                else scope.launch { TowerXMonitor.power(a, key = p.key) }
                            }) { Text(PowerUi.label(a)) }
                        }
                    }
                    if (tx.lastReply.isNotBlank() && p.key == com.vaulthome.app.towerx.TowerXKeys.POWER_TOKEN) {
                        Text(tx.lastReply, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    PowerUi.cooldownNote(p)?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Text(
            "List: ${loaded.source.label}" + (loaded.problem?.let { " · $it" } ?: "") + ". Edit app_settings/VaultEvents/devices.json in the vault to change it.",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
    }

    confirmOff?.let { (d, p, a) ->
        AlertDialog(
            onDismissRequest = { confirmOff = null },
            title = { Text(PowerUi.confirmTitle(d.name, a)) },
            text = { Text(PowerUi.confirmText(d.name, p, a)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = null
                    scope.launch { TowerXMonitor.power(a, confirmed = true, key = p.key) }
                }) { Text(a.label) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = null }) { Text("Cancel") } },
        )
    }
}
