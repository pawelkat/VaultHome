package com.vaulthome.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.vault.DailyNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Landing page: the latest daily note in the note viewer (search overlay on top), or an empty state. */
@Composable
fun HomeScreen(viewModel: VaultHomeViewModel, onIndex: () -> Unit, onSettings: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val index by viewModel.index.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(true) }
    var daily by remember { mutableStateOf<String?>(null) }
    // Re-resolve on launch and whenever an index run finishes (a new daily note may have appeared).
    LaunchedEffect(index.phase == "ready") {
        val graph = (context.applicationContext as VaultHomeApp).graph
        daily = withContext(Dispatchers.IO) {
            val indexed = graph.database.chunks().allNotes().map { it.path }
            val paths = indexed.ifEmpty { runCatching { graph.reader.scan().map { it.relativePath } }.getOrDefault(emptyList()) }
            DailyNotes.latest(paths, LocalDate.now())
        }
        loading = false
    }
    val actions: @Composable RowScope.() -> Unit = { HomeActions(viewModel, onIndex, onSettings) }
    var opened by remember { mutableStateOf<String?>(null) }
    val path = daily ?: opened
    if (path != null) {
        NoteScreen(path = path, viewModel = viewModel, onBack = { if (daily == null) opened = null else onExit() }, actions = actions)
    } else {
        BackHandler(onBack = onExit)
        SearchScaffold(viewModel = viewModel, onOpen = { opened = it }, actions = actions) {
            IndexStatusLine(viewModel)
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Looking for today's daily note…"
                    else "No daily note (YYYY-MM-DD.md) found.\nPick the vault in Settings and run Index, or search above.",
                    color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun HomeActions(viewModel: VaultHomeViewModel, onIndex: () -> Unit, onSettings: () -> Unit) {
    val index by viewModel.index.collectAsStateWithLifecycle()
    val busy = index.phase == "downloading" || index.phase == "indexing"
    IconButton(onClick = onIndex, enabled = !busy) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Filled.Refresh, contentDescription = "Reindex vault", tint = Color(0xFFB0B0B0))
    }
    IconButton(onClick = onSettings) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color(0xFFB0B0B0))
    }
}

/** Small index status line under the bar while indexing (or after an error). */
@Composable
fun IndexStatusLine(viewModel: VaultHomeViewModel) {
    val index by viewModel.index.collectAsStateWithLifecycle()
    val busy = index.phase == "downloading" || index.phase == "indexing"
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (busy || index.phase == "error") {
        Text(index.message, color = Color(0xFF8A8A8A), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
