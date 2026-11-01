package com.vaulthome.app.ask

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Ask surface: question → Nitro answer (or a clear fallback) + local sources with Obsidian links. */
@Composable
fun AskScreen(viewModel: AskViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val busy = state.phase != null
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Ask the vault", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = state.question,
            onValueChange = viewModel::onQuestionChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask a question about your notes") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { viewModel.ask() }),
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::ask, enabled = !busy && state.question.isNotBlank()) { Text("Ask") }
            if (busy) OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(phaseText(state.phase!!), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        val outcome = state.outcome
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (outcome != null) {
                item { OutcomeHeader(outcome) }
                val answer = when (outcome) {
                    is AskOutcome.Nitro -> outcome.answer
                    is AskOutcome.Local -> outcome.answer
                    is AskOutcome.SearchOnly -> null
                }
                if (answer != null) {
                    item {
                        SelectionContainer {
                            Text(answer, color = Color(0xFFE6E6E6), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                outcome.searchError?.let { item { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) } }
                if (outcome.citations.isNotEmpty()) {
                    item { Text("Sources", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(outcome.citations) { c ->
                        Column(
                            Modifier.fillMaxWidth().background(Color(0xFF242424), RoundedCornerShape(8.dp))
                                .clickable {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.obsidianUri)))
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(context, "Obsidian is not installed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(10.dp),
                        ) {
                            Text(c.path, color = Color(0xFFE6E6E6), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.heading.isNotEmpty()) Text(c.heading, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Text(c.snippet, color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Open in Obsidian", color = Color(0xFF9BC4F0), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else if (outcome.searchError == null) {
                    item { Text("No matching notes in the local index.", color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private val Muted = Color(0xFF8A8A8A)

private fun phaseText(phase: AskPhase) = when (phase) {
    AskPhase.CheckingNitro -> "Checking Nitro…"
    AskPhase.AskingNitro -> "Asking Nitro…"
    AskPhase.Searching -> "Searching the vault…"
    AskPhase.Generating -> "Answering on the phone…"
}

@Composable
private fun OutcomeHeader(outcome: AskOutcome) {
    val text = when (outcome) {
        is AskOutcome.Nitro -> "Answer from Nitro" + (outcome.model?.let { " ($it)" } ?: "")
        is AskOutcome.Local -> "Answer on the phone · Nitro: ${outcome.nitroDetail}"
        is AskOutcome.SearchOnly -> "No answer: ${outcome.nitroDetail}. ${outcome.localDetail}. Showing matching notes."
    }
    Text(text, color = Muted, style = MaterialTheme.typography.labelMedium)
}
