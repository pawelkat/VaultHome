package com.vaulthome.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PageInk = Color(0xFFE6E6E6)
private val PageMuted = Color(0xFF8A8A8A)

@Composable
fun NoteScreen(path: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var body by remember(path) { mutableStateOf("Loading the note…") }
    var error by remember(path) { mutableStateOf<String?>(null) }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101010))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    text = body,
                    color = PageInk,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text("Raw note", color = PageMuted, style = MaterialTheme.typography.labelSmall)
    }
}
