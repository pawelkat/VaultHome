package com.vaulthome.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaulthome.app.events.EventSyncService
import com.vaulthome.app.events.EventSyncSettings
import com.vaulthome.app.events.label
import com.vaulthome.events.EngineStatus
import com.vaulthome.events.LinkMode

private val Hint = Color(0xFF8A8A8A)

/** Settings → Event sync: Zenoh client / peer / router for vault note events (off by default). */
@Composable
fun EventSyncSection(onGrantVaultWrite: () -> Unit) {
    val ctx = LocalContext.current
    val graph = (ctx.applicationContext as com.vaulthome.app.VaultHomeApp).graph
    val store = graph.eventSync
    val settings by store.state.collectAsStateWithLifecycle()
    val svc by EventSyncService.state.collectAsStateWithLifecycle()
    var device by remember { mutableStateOf(settings.device) }
    var connect by remember { mutableStateOf(settings.connect.joinToString(", ")) }
    var listen by remember { mutableStateOf(settings.listen.joinToString(", ")) }
    var mode by remember { mutableStateOf(settings.mode) }
    val writeGranted = remember(svc, settings) {
        val tree = graph.store.treeUri()
        tree != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }
    val draft = settings.copy(
        device = device.trim(),
        connect = EventSyncSettings.parseList(connect),
        listen = EventSyncSettings.parseList(listen),
        mode = mode,
    )
    val problem = draft.problem()
    val apply = { enabled: Boolean ->
        val next = draft.copy(enabled = enabled)
        store.update(next)
        if (enabled && next.problem() == null) EventSyncService.start(ctx) else EventSyncService.stop(ctx)
    }

    Text("Event sync", style = MaterialTheme.typography.titleMedium, color = Ink)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Sync vault notes over Zenoh", color = Ink, modifier = Modifier.weight(1f))
        Switch(checked = settings.enabled, onCheckedChange = { on -> apply(on) })
    }
    if (!writeGranted) {
        Text(
            "Event sync writes incoming notes into the vault folder, so it needs write access to the same folder " +
                "VaultHome reads (pick /sdcard/DriveSyncFiles/Vault again and allow).",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onGrantVaultWrite) { Text("Grant write access") }
    }
    OutlinedTextField(
        value = device,
        onValueChange = { device = it },
        label = { Text("Device name (origin in events and conflict names)", color = Muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = connect,
        onValueChange = { connect = it },
        label = { Text("Connect endpoint(s), comma separated", color = Muted) },
        singleLine = false,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
    )
    Text("Mode", color = Ink)
    LinkMode.entries.forEach { m ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = mode == m, onClick = { mode = m }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = mode == m, onClick = null)
            Column2(m.label, modeHint(m))
        }
    }
    if (mode != LinkMode.CLIENT) {
        OutlinedTextField(
            value = listen,
            onValueChange = { listen = it },
            label = { Text("Listen endpoint", color = Muted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        )
    }
    if (mode == LinkMode.ROUTER) {
        Text(
            "Router mode makes this phone the meeting point instead of note4: other devices and the box hub connect " +
                "to tcp/<this phone's Tailscale name>:7447. It only forwards; notes are still stored by the box hub " +
                "(set VAULT_EVENTS_HUB_CONNECT on the box to this phone). The Tailscale app must be connected, and " +
                "VaultHome must keep running: the notification keeps the session open and a wake lock keeps it " +
                "awake while the screen is off. Set VaultHome's battery use to Unrestricted, or Android may stop it.",
            color = Hint, style = MaterialTheme.typography.bodySmall,
        )
    }
    if (mode != LinkMode.CLIENT) {
        OutlinedButton(onClick = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Battery optimization settings") }
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = problem == null, onClick = { apply(settings.enabled) }) {
            Text(if (settings.enabled) "Save & restart" else "Save")
        }
        if (settings.enabled && svc.running) {
            OutlinedButton(onClick = { EventSyncService.syncNow(ctx) }) { Text("Catch up now") }
        }
        OutlinedButton(onClick = {
            device = EventSyncSettings.DEFAULT_DEVICE
            connect = EventSyncSettings.DEFAULT_CONNECT
            listen = EventSyncSettings.DEFAULT_LISTEN
            mode = LinkMode.CLIENT
        }) { Text("Defaults") }
    }
    Text(statusLine(settings.enabled, svc), color = Ink, style = MaterialTheme.typography.bodySmall)
    svc.status.lastEvent?.let { ev ->
        val at = svc.status.lastEventAtMs?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }
        Text("Last event: $ev${at?.let { " at $it" } ?: ""}", color = Hint, style = MaterialTheme.typography.bodySmall)
    }
    svc.status.lastCatchUp?.let { Text("Last catch-up: $it", color = Hint, style = MaterialTheme.typography.bodySmall) }
    (svc.error ?: svc.status.lastError)?.let {
        Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Notes travel as vault/notes/<path> events (format 1, same as the box's vault-events). Local edits are found " +
            "by rescanning the folder every minute. Conflicts keep both versions as \"<name> (conflict <device> " +
            "<date>).md\". Do not run rclone bisync (vault-sync in Termux) on this folder while event sync is on: two " +
            "writers on one tree loop and double the conflict copies. No keys are stored; access relies on Tailscale.",
        color = Hint, style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Column2(title: String, hint: String) {
    androidx.compose.foundation.layout.Column(Modifier.padding(start = 8.dp)) {
        Text(title, color = Ink)
        Text(hint, color = Hint, style = MaterialTheme.typography.bodySmall)
    }
}

private fun modeHint(m: LinkMode) = when (m) {
    LinkMode.CLIENT -> "Connect to a router (note4 or the box hub). Recommended."
    LinkMode.PEER -> "Connect directly to the endpoints and accept peers on the listen endpoint."
    LinkMode.ROUTER -> "This phone is the router other devices connect to."
}

private fun statusLine(enabled: Boolean, s: com.vaulthome.app.events.EventSyncState): String {
    if (!enabled) return "Status: off"
    if (!s.running) return "Status: not running"
    val st = s.status
    val phase = when (st.phase) {
        EngineStatus.Phase.CONNECTED -> "connected"
        EngineStatus.Phase.CONNECTING -> "connecting"
        EngineStatus.Phase.ERROR -> "error"
        EngineStatus.Phase.STOPPED -> "stopped"
    }
    return "Status: $phase (${s.endpoint ?: ""}) · ${st.routers} routers · ${st.peers} peers · ${st.tracked} notes tracked · " +
        "${st.published} sent · ${st.applied} applied · ${st.conflicts} conflicts"
}
