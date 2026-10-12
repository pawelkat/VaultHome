package com.vaulthome.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.vaulthome.app.markdown.WikiLinks
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.index.ObsidianLinks
import com.vaulthome.app.markdown.MarkdownBlocks
import com.vaulthome.app.markdown.MarkdownText
import com.vaulthome.app.markdown.SafeLinks
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PageInk = Color(0xFFE6E6E6)
private val PageMuted = Color(0xFF8A8A8A)

@Composable
fun NoteScreen(path: String, viewModel: VaultHomeViewModel, onBack: () -> Unit) {
    // In-app back stack for wikilink navigation; system back pops it before leaving the screen.
    var stack by rememberSaveable(path) { mutableStateOf(listOf(path)) }
    NoteContent(
        path = stack.last(),
        onBack = { if (stack.size > 1) stack = stack.dropLast(1) else onBack() },
        onOpenNote = { stack = stack + it },
        viewModel = viewModel,
    )
}

@Composable
private fun NoteContent(path: String, onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: VaultHomeViewModel) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var body by remember(path) { mutableStateOf("Loading the note…") }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var raw by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(path) {
        keyboard?.hide()
        val graph = (context.applicationContext as VaultHomeApp).graph
        val indexed = withContext(Dispatchers.IO) {
            graph.database.chunks().chunksFor(path).joinToString("\n\n") { chunk ->
                if (chunk.heading.isBlank()) chunk.chunkText else "${chunk.heading}\n\n${chunk.chunkText}"
            }
        }
        if (indexed.isNotBlank()) {
            body = indexed
        }
        val file = try {
            withContext(Dispatchers.IO) { graph.reader.readNote(path) }
        } catch (failure: Exception) {
            error = failure.message ?: "Could not read $path"
            null
        }
        if (file != null) {
            body = file
            error = null
        } else if (indexed.isBlank()) {
            error = error ?: "Could not read $path"
            body = ""
        } else if (error == null) {
            error = "Showing the indexed text. The full file could not be read."
        }
    }
    SearchScaffold(viewModel = viewModel, onOpen = { if (it != path) onOpenNote(it) }) {
        Text(path, color = PageInk, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(
                onClick = {
                    val uri = ObsidianLinks.vaultFileUri(path)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        error = "Obsidian is not installed"
                    }
                },
            ) { Text("Open in Obsidian") }
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !raw, onClick = { raw = false }, label = { Text("Rendered") })
            FilterChip(selected = raw, onClick = { raw = true }, label = { Text("Raw") })
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101010))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            if (raw) {
                SelectionContainer {
                    Text(
                        text = body,
                        color = PageInk,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                RenderedNote(
                    markdown = body,
                    onLink = { uri ->
                        if (SafeLinks.isSafe(uri)) {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } catch (_: ActivityNotFoundException) {}
                        }
                    },
                    onWikiLink = { target ->
                        scope.launch {
                            val graph = (context.applicationContext as VaultHomeApp).graph
                            val paths = withContext(Dispatchers.IO) { graph.database.chunks().allNotes().map { it.path } }
                            val resolved = WikiLinks.resolve(target, paths)
                            if (resolved != null && resolved != path) onOpenNote(resolved)
                            else if (resolved == null) Toast.makeText(context, "Note not found in the index: $target", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
        Text(if (raw) "Raw note" else "Rendered note (read-only)", color = PageMuted, style = MaterialTheme.typography.labelSmall)
    }
}

/** Frontmatter stripped (tags shown as chips), body rendered by [MarkdownText]. Read-only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenderedNote(markdown: String, onLink: (String) -> Unit, onWikiLink: ((String) -> Unit)? = null) {
    val parts = remember(markdown) { MarkdownBlocks.splitFrontmatter(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (parts.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.tags.forEach { tag ->
                    Text(
                        "#$tag",
                        color = PageMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        SelectionContainer {
            MarkdownText(parts.body, onLink = onLink, onWikiLink = onWikiLink, modifier = Modifier.fillMaxWidth(), image = { VaultImage(it) })
        }
    }
}
