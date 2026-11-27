package com.vaulthome.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.ask.CapsEvidence
import com.vaulthome.app.ask.CapsJson
import com.vaulthome.app.ask.CapsSource
import com.vaulthome.app.ask.ProviderMonitor
import com.vaulthome.app.ask.ProviderRouting

/**
 * Dialog showing the exact raw caps payload (pretty-printed, monospace, selectable) plus source and age.
 * Synthesized fallbacks also show the underlying /health and /props bodies; devices.json shows the entry.
 */
@Composable
fun CapsJsonSheet(device: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ps by ProviderMonitor.state.collectAsStateWithLifecycle()
    val registry = (ctx.applicationContext as? VaultHomeApp)
        ?.graph?.deviceRegistry?.state?.value?.registry?.devices
        ?: emptyList()
    val ev = ps.evidence[device] ?: ProviderMonitor.evidenceFor(device, registry)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Caps · ${hostLabel(device)}") },
        text = { if (ev == null) Text("No caps document for $device yet.", color = Muted) else CapsJsonBody(ev) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                com.vaulthome.app.ask.ProvidersNav.show()
            }) { Text("Ask providers") }
        },
    )
}

@Composable
internal fun CapsJsonBody(ev: CapsEvidence, nowMs: Long = System.currentTimeMillis()) {
    val ctx = LocalContext.current
    val pretty = CapsJson.pretty(ev.rawJson)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(CapsJson.sourceLabel(ev) + if (ev.stale) " (stale)" else "", color = Ink, style = MaterialTheme.typography.bodyMedium)
        CapsJson.receivedLine(ev.atMs, nowMs)?.let {
            Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
        if (ev.source == CapsSource.HealthProps) {
            Text("This document is synthesized, not advertised.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            ev.healthJson?.let { snippet ->
                Text("GET /health", color = Ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                JsonBlock(CapsJson.pretty(snippet))
            }
            ev.propsJson?.let { snippet ->
                Text("GET /props", color = Ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                JsonBlock(CapsJson.pretty(snippet))
            }
            Text("Synthesized caps", color = Ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        }
        if (ev.source == CapsSource.Registry) {
            Text("devices.json entry", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("JSON", color = Ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { copy(ctx, pretty) }) { Text("Copy") }
        }
        JsonBlock(pretty)
    }
}

@Composable
private fun JsonBlock(text: String) {
    SelectionContainer {
        Text(
            text,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "caps JSON" }
                .padding(top = 4.dp),
        )
    }
}

private fun hostLabel(device: String) = when (device) {
    ProviderRouting.TOWERX_DEVICE -> "TowerX"
    ProviderRouting.NITRO_DEVICE -> "Nitro"
    else -> device
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("caps", text))
}
