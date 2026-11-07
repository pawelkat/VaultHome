package com.vaulthome.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun SettingsScreen(
    viewModel: VaultHomeViewModel,
    onPickVault: () -> Unit,
    onBack: () -> Unit,
    askViewModel: com.vaulthome.app.ask.AskViewModel? = null,
) {
    BackHandler(onBack = onBack)
    val home by viewModel.home.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    val busy = index.phase == "downloading" || index.phase == "indexing"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrow(onBack)
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.padding(start = 4.dp))
        }
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text("Vault", style = MaterialTheme.typography.titleMedium, color = Ink)
        Text(
            text = if (home.vaultGranted) "Read access is granted" else "The vault is not granted",
            color = Color(0xFF8A8A8A),
        )
        if (home.vaultGranted) {
            OutlinedButton(onClick = onPickVault) { Text("Change vault") }
        } else {
            Button(onClick = onPickVault) { Text("Grant vault") }
        }
        Text("Open YouTube links with", style = MaterialTheme.typography.titleMedium, color = Ink)
        val ctx = LocalContext.current
        var yt by remember { mutableStateOf(LinkOpener.youTubeApp(ctx)) }
        YouTubeApp.entries.forEach { opt ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = yt == opt, onClick = { yt = opt; LinkOpener.setYouTubeApp(ctx, opt) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = yt == opt, onClick = null)
                Text(opt.label, color = Ink, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text("Model", style = MaterialTheme.typography.titleMedium, color = Ink)
        if (!home.modelReady) {
            Button(onClick = viewModel::downloadModel) { Text("Download model") }
        } else {
            Text("Model is in app storage", color = Color(0xFF8A8A8A))
        }
        if (askViewModel != null) AskSettingsSection(askViewModel)
        X4SettingsSection()
    }
}

@Composable
private fun AskSettingsSection(askViewModel: com.vaulthome.app.ask.AskViewModel) {
    val ctx = LocalContext.current
    val store = (ctx.applicationContext as com.vaulthome.app.VaultHomeApp).graph.askSettings
    val ask by store.state.collectAsStateWithLifecycle()
    val health by askViewModel.healthText.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(ask.nitroBaseUrl) }
    Text("Ask", style = MaterialTheme.typography.titleMedium, color = Ink)
    SwitchRow("Show Ask ↔ Search switch", ask.askEnabled) { v -> store.update { it.copy(askEnabled = v) } }
    SwitchRow("Use Nitro first", ask.nitroEnabled) { v -> store.update { it.copy(nitroEnabled = v) } }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Nitro chat proxy URL", color = Muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { store.update { it.copy(nitroBaseUrl = url) }; url = store.state.value.nitroBaseUrl }),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { store.update { it.copy(nitroBaseUrl = url) }; url = store.state.value.nitroBaseUrl }) { Text("Save URL") }
        OutlinedButton(onClick = {
            store.update { it.copy(nitroBaseUrl = url) }; url = store.state.value.nitroBaseUrl; askViewModel.testNitro()
        }) { Text("Test /health") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { url = com.vaulthome.app.ask.AskSettings.DEFAULT_NITRO_URL; store.update { it.copy(nitroBaseUrl = url) } }) { Text("Home Wi‑Fi") }
        TextButton(onClick = { url = com.vaulthome.app.ask.AskSettings.TAILSCALE_NITRO_URL; store.update { it.copy(nitroBaseUrl = url) } }) { Text("Tailscale") }
    }
    Text(
        "Ask must use Nitro's chat proxy (adds the Nitro5 Qwen system prompt and runs vault_search), " +
            "not raw llama-server. Home Wi‑Fi: http://192.168.1.18:8082. Tailscale: https://nitro5.tailf52603.ts.net. " +
            "Raw :8080 answers without vault tools — avoid it for Ask. The Wi‑Fi URL needs the proxy listening on the LAN " +
            "(BIND=0.0.0.0); the shipped service binds 127.0.0.1, which only Tailscale Serve reaches.",
        color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodySmall,
    )
    health?.let { Text(it, color = Color(0xFF8A8A8A)) }
    Text(
        "Plain http works only to 192.168.1.18 and localhost; Tailscale uses HTTPS. No API key is stored. On-device answers (Gemma 4 E2B) come in a later build; until then Ask falls back to matching notes.",
        color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun X4SettingsSection() {
    val ctx = LocalContext.current
    val store = (ctx.applicationContext as com.vaulthome.app.VaultHomeApp).graph.x4Settings
    val x4: com.vaulthome.app.x4.X4ViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val settings by store.state.collectAsStateWithLifecycle()
    val state by x4.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(settings.deviceName) }
    var address by remember { mutableStateOf(settings.addressHint) }
    val askScan = rememberX4BleScan(x4)
    val save = {
        store.setDevice(name, address)
        name = store.state.value.deviceName
        address = store.state.value.addressHint
        askScan()
    }
    Text("X4 e-ink", style = MaterialTheme.typography.titleMedium, color = Ink)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("X4 Bluetooth name", color = Muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("X4 Bluetooth address", color = Muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
    )
    com.vaulthome.app.x4.X4Settings.addressProblem(com.vaulthome.app.x4.X4Settings.normalizeAddress(address))?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { save() }) { Text("Save & scan") }
        TextButton(onClick = {
            name = com.vaulthome.app.x4.X4Ble.DEFAULT_NAME
            address = com.vaulthome.app.x4.X4Ble.DEFAULT_ADDRESS
            save()
        }) { Text("Defaults") }
    }
    Text(com.vaulthome.app.x4.X4Text.detect(state.detect), color = Color(0xFF8A8A8A))
    Text(
        "The current X4 image speaks BLE only (no Wi-Fi, no pairing, no PIN). It will not appear in the phone's " +
            "Bluetooth device list — VaultHome scans for the name VaultHome X4 itself. If nothing is found, press " +
            "any key on the reader to wake it (do not hold power). The X4 button on a note sends its raw markdown " +
            "and on an Ask answer sends the answer as shown. Up to 32,000 characters; VaultKeep is never sent.",
        color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodySmall,
    )
    X4McpServerSection()
}

/** VaultHome's own display MCP for Grok Bot: POST /mcp on this phone, forwarded to the X4 over BLE. */
@Composable
private fun X4McpServerSection() {
    val ctx = LocalContext.current
    val store = (ctx.applicationContext as com.vaulthome.app.VaultHomeApp).graph.x4Settings
    val server by store.server.collectAsStateWithLifecycle()
    val running by com.vaulthome.app.x4.mcp.X4McpService.state.collectAsStateWithLifecycle()
    var portText by remember { mutableStateOf(server.port.toString()) }
    val portValue = com.vaulthome.app.x4.mcp.X4McpHttpServer.parsePort(portText)
    var urls by remember { mutableStateOf(emptyList<com.vaulthome.app.x4.mcp.X4McpHttpServer.Companion.ListenUrl>()) }
    LaunchedEffect(running, server.port) {
        urls = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.vaulthome.app.x4.mcp.X4McpHttpServer.listenUrls(
                server.port, com.vaulthome.app.x4.mcp.X4McpHttpServer.localIpv4(),
            )
        }
    }
    val enable = {
        val port = portValue ?: server.port
        store.setServer(true, port)
        portText = port.toString()
        com.vaulthome.app.x4.mcp.X4McpService.start(ctx)
    }
    val needed = buildList {
        addAll(com.vaulthome.app.x4.X4BlePermissions.required())
        if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    // Enable anyway after the dialog: show_text / panel_status then report "Bluetooth permission needed".
    val askPermissions = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { enable() }

    Text("X4 MCP server (for Grok Bot)", style = MaterialTheme.typography.titleSmall, color = Ink)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = server.enabled,
            onCheckedChange = { on ->
                if (on) {
                    val missing = needed.any {
                        androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (missing) askPermissions.launch(needed) else enable()
                } else {
                    store.setServer(false, server.port)
                    com.vaulthome.app.x4.mcp.X4McpService.stop(ctx)
                }
            },
        )
        Text(
            when {
                running.running -> "Listening on port ${running.port}"
                server.enabled && running.error != null -> "Not listening: ${running.error}"
                server.enabled -> "Starting…"
                else -> "Off"
            },
            color = if (running.error != null && server.enabled) MaterialTheme.colorScheme.error else Muted,
        )
    }
    OutlinedTextField(
        value = portText,
        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
        label = { Text("MCP port (default ${com.vaulthome.app.x4.X4McpServerSettings.DEFAULT_PORT})", color = Muted) },
        singleLine = true,
        isError = portValue == null,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            val p = portValue ?: return@KeyboardActions
            store.setServer(server.enabled, p)
            if (server.enabled) com.vaulthome.app.x4.mcp.X4McpService.start(ctx)
        }),
    )
    if (portValue == null) {
        Text("Port must be 1024–65535", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else if (portValue != server.port) {
        OutlinedButton(onClick = {
            store.setServer(server.enabled, portValue)
            if (server.enabled) com.vaulthome.app.x4.mcp.X4McpService.start(ctx)
        }) { Text("Use port $portValue") }
    }
    if (server.enabled) {
        Text("Connect URLs:", color = Muted, style = MaterialTheme.typography.bodySmall)
        urls.forEach { u ->
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text("${u.url}  (${u.kind})", color = Ink, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (urls.none { it.kind == "Tailscale" }) {
            Text(
                "No Tailscale address (100.x) found — open the Tailscale app and connect so Grok Bot can reach this phone.",
                color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Text(
        "Grok Bot calls POST /mcp here (initialize 2025-03-26, then tools/call show_text with the markdown unchanged). " +
            "VaultHome forwards it to the X4 over Bluetooth LE, not Wi-Fi, and reports success only when the reader " +
            "answers pages:N. No authentication yet — anyone who can reach this port can show text on the X4. " +
            "Keep the notification running; if Android stops it, open VaultHome to restart it.",
        color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodySmall,
    )
}
