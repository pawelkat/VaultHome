package com.vaulthome.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaulthome.app.search.SearchHit

@Composable
fun SearchScreen(
    viewModel: VaultHomeViewModel,
    onSettings: () -> Unit,
    onIndex: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val indexing = index.phase == "downloading" || index.phase == "indexing"
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("VaultHome", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(
                onClick = onSettings,
                border = BorderStroke(1.dp, Color(0xFF8A8A8A)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE6E6E6)),
            ) { Text("Settings") }
        }
        OutlinedTextField(
            value = home.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Search the vault") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8A8A8A),
                unfocusedBorderColor = Color(0xFF8A8A8A),
                focusedTextColor = Color(0xFFE6E6E6),
                unfocusedTextColor = Color(0xFFE6E6E6),
                cursorColor = Color(0xFFE6E6E6),
                focusedPlaceholderColor = Color(0xFF8A8A8A),
                unfocusedPlaceholderColor = Color(0xFF8A8A8A),
                focusedTrailingIconColor = Color(0xFF8A8A8A),
                unfocusedTrailingIconColor = Color(0xFF8A8A8A),
            ),
            trailingIcon = {
                if (home.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
        )
        Button(
            onClick = onIndex,
            enabled = !indexing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (indexing) "Indexing" else "Index") }
        if (indexing || home.searching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (index.message.isNotEmpty()) {
            Text(
                text = index.message,
                color = Color(0xFF8A8A8A),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (home.message.isNotEmpty()) {
            Text(home.message, style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(home.hits) { _, hit ->
                HitRow(hit = hit, onOpen = { onOpen(hit.path) })
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(hit.path, color = Color(0xFFE6E6E6), style = MaterialTheme.typography.titleSmall)
        if (hit.heading.isNotEmpty()) {
            Text(hit.heading, color = Color(0xFF8A8A8A))
        }
        Text(hit.snippet, color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "score ${"%.2f".format(hit.score)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF666666),
        )
    }
}

