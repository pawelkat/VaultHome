package com.vaulthome.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Column
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
import com.vaulthome.app.events.RouterProbe
import com.vaulthome.app.events.RouterListStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import androidx.compose.runtime.LaunchedEffect
import com.vaulthome.app.events.label
import com.vaulthome.events.LinkMode
import kotlinx.coroutines.withContext
import com.vaulthome.events.SharedEndpoints
import com.vaulthome.events.SharedConfig
import com.vaulthome.events.Disk
import com.vaulthome.app.events.SafVaultTree

private val Hint = Color(0xFF8A8A8A)

/** Settings → Event sync: Zenoh client / peer / router for vault note events (off by default). */
@Composable
fun EventSyncSection(onGrantVaultWrite: () -> Unit, onRequestNotifications: () -> Unit = {}) {
    val ctx = LocalContext.current
    val graph = (ctx.applicationContext as com.vaulthome.app.VaultHomeApp).graph
    val store = graph.eventSync
    val settings by store.state.collectAsStateWithLifecycle()
    val svc by EventSyncService.state.collectAsStateWithLifecycle()
    var device by remember { mutableStateOf(settings.device) }
    var connect by remember { mutableStateOf(settings.connect.joinToString(", ")) }
    var listen by remember { mutableStateOf(settings.listen.joinToString(", ")) }
    var mode by remember { mutableStateOf(settings.mode) }
    var tailnet by remember { mutableStateOf(settings.tailnetName) }
    var notify by remember { mutableStateOf(settings.notifySynced) }
    var useShared by remember { mutableStateOf(settings.useSharedEndpoints) }
    var add by remember { mutableStateOf("") }
    val writeGranted = remember(svc, settings) {
        val tree = graph.store.treeUri()
        tree != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }
    val draft = settings.copy(
        device = device.trim(),
        connect = EventSyncSettings.parseList(connect),
        listen = EventSyncSettings.parseList(listen),
        mode = mode,
        tailnetName = tailnet.trim(),
        notifySynced = notify,
        useSharedEndpoints = useShared,
    )
    val problem = draft.problem()
    val apply = { enabled: Boolean ->
        val next = draft.copy(enabled = enabled)
        store.update(next)
        if (enabled && next.notifySynced) onRequestNotifications()
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
    Text("Zenoh router list", color = Ink)
    Text(
        "Routers this phone dials, in order. Add one per row or paste several separated by comma, space or a new line " +
            "(tcp/host:7447 — also udp, tls, quic). Duplicates are dropped. In Client mode Zenoh links to one router " +
            "at a time (usually the first in this list that answers); the others are failover if that host is down, " +
            "not simultaneous sessions. A TCP probe of port 7447 is shown on each row so you can see which hosts are " +
            "listening. This list is used first; if “Use shared endpoint list” is on, dialable routers and other devices from " +
            "app_settings/VaultEvents/endpoints.json are added after it (they do not replace what you type). " +
            "The Grok Bot box is outbound-only and is never dialed. The chat-proxy tailnet suffix is taken from the first " +
            "*.ts.net entry here.",
        color = Hint, style = MaterialTheme.typography.bodySmall,
    )
    val routers = EventSyncSettings.parseList(connect)
    var shared by remember { mutableStateOf<SharedConfig?>(null) }
    LaunchedEffect(useShared, writeGranted, svc.status.lastCatchUp, svc.running) {
        if (!useShared) { shared = null; return@LaunchedEffect }
        val tree = graph.store.treeUri()
        if (tree == null) { shared = null; return@LaunchedEffect }
        shared = withContext(Dispatchers.IO) {
            SharedEndpoints.parse((SafVaultTree(ctx, tree).read(SharedEndpoints.PATH) as? Disk.Present)?.bytes)
        }
    }
    val dialed = remember(routers, shared, useShared, device, mode, tailnet, listen) {
        val self = draft.selfEntry()
        if (!useShared) {
            routers.mapNotNull { ep ->
                SharedEndpoints.normalizeEndpoint(ep)?.let { SharedEndpoints.DialTarget(it, SharedEndpoints.DialSource.SETTINGS) }
            }
        } else {
            SharedEndpoints.dialTargets(routers, shared, self)
        }
    }
    val vaultRows = dialed.filter { it.source == SharedEndpoints.DialSource.VAULT }
    val probeList = (routers + vaultRows.map { it.endpoint }).distinct()
    var reach by remember { mutableStateOf<Map<String, RouterListStatus.Reach>>(emptyMap()) }
    LaunchedEffect(probeList) {
        reach = probeList.associateWith { RouterListStatus.Reach.CHECKING }
        while (true) {
            val next = coroutineScope {
                probeList.map { ep ->
                    async(Dispatchers.IO) {
                        ep to if (RouterProbe.tcpOpen(ep)) RouterListStatus.Reach.REACHABLE else RouterListStatus.Reach.UNREACHABLE
                    }
                }.awaitAll().toMap()
            }
            reach = next
            delay(RouterProbe.INTERVAL_MS)
        }
    }
    routers.forEachIndexed { i, ep ->
        val err = EventSyncSettings.endpointProblem(ep)
        val host = RouterListStatus.parse(ep)?.host
        val label = buildString {
            append("Router ${i + 1}")
            if (host != null) append(" · ").append(RouterListStatus.shortName(host))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ep,
                onValueChange = { raw ->
                    val parts = EventSyncSettings.parseList(raw)
                    val next = routers.take(i) + parts + routers.drop(i + 1)
                    connect = EventSyncSettings.encodeList(next.distinct())
                },
                label = { Text(label, color = Muted) },
                isError = err != null,
                supportingText = {
                    if (err != null) Text(err, color = MaterialTheme.colorScheme.error)
                    else Text(
                        RouterListStatus.rowLabel(reach[ep]),
                        color = when (reach[ep]) {
                            RouterListStatus.Reach.REACHABLE -> Color(0xFF2E7D32)
                            RouterListStatus.Reach.UNREACHABLE -> MaterialTheme.colorScheme.error
                            else -> Hint
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            )
            TextButton(onClick = { connect = EventSyncSettings.encodeList(routers.filterIndexed { j, _ -> j != i }) }) { Text("Remove") }
        }
    }
    val addProblem = add.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        EventSyncSettings.parseList(raw).mapNotNull { EventSyncSettings.endpointProblem(it) }.firstOrNull()
    }
    fun addRouters() {
        val parts = EventSyncSettings.parseList(add)
        if (parts.isEmpty()) return
        connect = EventSyncSettings.encodeList((routers + parts).distinct())
        add = ""
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = add,
            onValueChange = { add = it },
            label = { Text("Add router", color = Muted) },
            placeholder = { Text("tcp/n910c.tailf52603.ts.net:7447", color = Hint) },
            isError = addProblem != null,
            singleLine = false,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addRouters() }),
        )
        TextButton(enabled = EventSyncSettings.parseList(add).isNotEmpty(), onClick = { addRouters() }) { Text("Add") }
    }
    if (addProblem != null) Text(addProblem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (routers.isEmpty()) Text("The list is empty — add at least one router to connect in Client mode.", color = Hint, style = MaterialTheme.typography.bodySmall)
    if (useShared && vaultRows.isNotEmpty()) {
        Text("From vault", color = Ink)
        Text(
            "Also dialed from ${SharedEndpoints.PATH} (not in the list above). These are not editable here.",
            color = Hint, style = MaterialTheme.typography.bodySmall,
        )
        vaultRows.forEach { row ->
            val host = RouterListStatus.parse(row.endpoint)?.host
            val title = buildString {
                append("From vault")
                row.id?.let { append(" · ").append(it) }
                if (host != null && (row.id == null || RouterListStatus.shortName(host) != row.id)) {
                    append(" · ").append(RouterListStatus.shortName(host))
                }
            }
            OutlinedTextField(
                value = row.endpoint,
                onValueChange = {},
                readOnly = true,
                label = { Text(title, color = Muted) },
                supportingText = {
                    Text(
                        RouterListStatus.rowLabel(reach[row.endpoint]),
                        color = when (reach[row.endpoint]) {
                            RouterListStatus.Reach.REACHABLE -> Color(0xFF2E7D32)
                            RouterListStatus.Reach.UNREACHABLE -> MaterialTheme.colorScheme.error
                            else -> Hint
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column2("Use shared endpoint list", "Read and update ${SharedEndpoints.PATH} in the vault. Extra dialable routers from that file appear as “from vault” rows under the list you type (this list stays first; same host is kept once). Also writes this phone's entry (device, mode, listen, Tailscale name, last seen).", Modifier.weight(1f))
        Switch(checked = useShared, onCheckedChange = { useShared = it })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column2("Notify synced notes", "One notification per note another device changed (one summary when many, e.g. catch-up). Tap opens the note.", Modifier.weight(1f))
        Switch(checked = notify, onCheckedChange = { notify = it; if (it) onRequestNotifications() })
    }
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
    if (mode != LinkMode.CLIENT) {
        OutlinedTextField(
            value = tailnet,
            onValueChange = { tailnet = it },
            label = { Text("This phone's Tailscale name (advertised in the shared list)", color = Muted) },
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
            connect = EventSyncSettings.DEFAULT_CONNECT_LIST.joinToString(", ")
            listen = EventSyncSettings.DEFAULT_LISTEN
            mode = LinkMode.CLIENT
            tailnet = EventSyncSettings.DEFAULT_TAILNET_NAME
            notify = true
            useShared = true
        }) { Text("Defaults") }
    }
    Text(RouterListStatus.statusLine(settings.enabled, svc, dialed.map { it.endpoint }, fromVault = vaultRows.size), color = Ink, style = MaterialTheme.typography.bodySmall)
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
private fun Column2(title: String, hint: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier.padding(start = 8.dp)) {
        Text(title, color = Ink)
        Text(hint, color = Hint, style = MaterialTheme.typography.bodySmall)
    }
}

private fun modeHint(m: LinkMode) = when (m) {
    LinkMode.CLIENT -> "Link to one router at a time (the first in the list that answers). Recommended. Peer mode can keep more than one link open; that is a larger change and is not switched on here."
    LinkMode.PEER -> "Connect directly to the endpoints and accept peers on the listen endpoint."
    LinkMode.ROUTER -> "This phone is the router other devices connect to."
}

