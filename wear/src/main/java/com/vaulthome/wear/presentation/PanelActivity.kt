package com.vaulthome.wear.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.vaulthome.panel.PanelFormat
import com.vaulthome.wear.R
import com.vaulthome.wear.data.PanelStore
import com.vaulthome.wear.data.StoredPanel
import com.vaulthome.wear.notify.PanelNotifier
import com.vaulthome.wear.tile.PanelTileService

/** Full panel, opened from the notification, the tile or the launcher. Asks for notification permission once. */
class PanelActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val store = PanelStore(this)
            store.load()?.let { PanelNotifier.notifyIfNew(this, store, it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PanelNotifier.ensureChannel(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { PanelApp() }
    }
}

@Composable
fun PanelApp() {
    val context = LocalContext.current
    var panel by remember { mutableStateOf<StoredPanel?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val store = PanelStore(context)
        panel = store.load()
        // Pick up a DataItem the listener may have missed (e.g. published before install).
        store.fetchFromPhone()?.let { fetched ->
            if (fetched.hash != panel?.hash) PanelTileService.requestUpdate(context)
            panel = fetched
        }
        // First open after install: the listener never saw this DataItem, so notify for it now
        // (no-op if this hash was already notified or the permission is not granted yet).
        panel?.let { PanelNotifier.notifyIfNew(context, store, it) }
        loaded = true
    }
    MaterialTheme {
        AppScaffold(timeText = { TimeText() }) {
            ScreenScaffold {
                ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
                    val note = panel?.note
                    item {
                        Text(
                            note?.let { PanelFormat.shortDate(it.date) } ?: stringResource(R.string.tile_label),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (note == null) {
                        if (loaded) item { Body(stringResource(R.string.no_data)) }
                    } else {
                        val sections = PanelFormat.orderedSections(note).filter { it.items.isNotEmpty() }
                        if (sections.isEmpty()) item { Body("Nothing on the panel today.") }
                        sections.forEach { section ->
                            item {
                                Text(
                                    section.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                            }
                            section.items.forEach { line -> item { Body("• $line") } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
}
