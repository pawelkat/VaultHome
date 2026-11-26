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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Landing page: today's Daily/yyyy-MM-dd.md (search overlay on top), or an empty state / most recent past note. */
@Composable
fun HomeScreen(
    viewModel: VaultHomeViewModel,
    onIndex: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
    /** Note opened from a "Synced notes" notification; shown over the usual landing page until closed. */
    requestedNote: String? = null,
    onRequestedNoteClosed: () -> Unit = {},
) {
    val context = LocalContext.current
    val index by viewModel.index.collectAsStateWithLifecycle()
    val today = DailyNotes.today()
    // Saveable so the first composition after Settings / recreation / process death goes straight to the note.
    var loading by rememberSaveable { mutableStateOf(true) }
    var daily by rememberSaveable { mutableStateOf<String?>(null) }
    var dailyNotice by rememberSaveable { mutableStateOf<String?>(null) }
    // Re-resolve on launch, when the local date rolls over, and whenever an index run finishes.
    // Paths come from the index PLUS a direct look at Daily/ — the index alone can be days stale
    // (the 2026-11-23 start-page bug: today's Daily/2026-11-26.md was on disk but not indexed yet).
    LaunchedEffect(today.toString(), index.phase) {
        val graph = (context.applicationContext as VaultHomeApp).graph
        val landing = withContext(Dispatchers.IO) {
            val todayPath = DailyNotes.pathFor(today)
            val onDisk = buildList {
                if (graph.reader.findByPath(todayPath) != null) add(todayPath)
                addAll(runCatching { graph.reader.listFolder(DailyNotes.FOLDER).map { it.relativePath } }.getOrDefault(emptyList()))
            }
            val indexed = runCatching { graph.database.chunks().allNotes().map { it.path } }.getOrDefault(emptyList())
            DailyNotes.landing(indexed + onDisk, today)
        }
        daily = landing?.path
        dailyNotice = if (landing != null && !landing.isToday)
            "Today's note (${DailyNotes.pathFor(today)}) was not found. Showing ${landing.path}."
        else null
        loading = false
    }
    val actions: @Composable RowScope.() -> Unit = { ReindexAction(viewModel, onIndex) }
    val topBar: @Composable RowScope.() -> Unit = { SettingsAction(onSettings) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val path = daily ?: opened
    if (requestedNote != null) {
        NoteScreen(path = requestedNote, viewModel = viewModel, onBack = onRequestedNoteClosed, canLeave = true, actions = actions, topBar = topBar)
    } else if (path != null) {
        NoteScreen(path = path, viewModel = viewModel, onBack = { if (daily == null) opened = null else onExit() }, canLeave = daily == null, notice = if (daily != null) dailyNotice else null, actions = actions, topBar = topBar)
    } else {
        BackHandler(onBack = onExit)
        SearchScaffold(viewModel = viewModel, onOpen = { opened = it }, actions = actions, topBar = topBar) {
            IndexStatusLine(viewModel)
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Looking for today's daily note…"
                    else "No daily note for $today (${DailyNotes.pathFor(today)}).\nPick the vault in Settings, or search above.",
                    color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun ReindexAction(viewModel: VaultHomeViewModel, onIndex: () -> Unit) {
    val index by viewModel.index.collectAsStateWithLifecycle()
    val busy = index.phase == "downloading" || index.phase == "indexing"
    IconButton(onClick = onIndex, enabled = !busy) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Filled.Refresh, contentDescription = "Reindex vault", tint = Color(0xFFB0B0B0))
    }
}

@Composable
fun SettingsAction(onSettings: () -> Unit) {
    IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color(0xFFB0B0B0), modifier = Modifier.size(20.dp))
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
