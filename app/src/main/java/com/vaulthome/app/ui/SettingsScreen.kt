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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: VaultHomeViewModel,
    onPickVault: () -> Unit,
    onBack: () -> Unit,
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = onBack) { Text("Back") }
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text("Vault", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (home.vaultGranted) "Read access is granted" else "The vault is not granted",
            color = Color(0xFF8A8A8A),
        )
        if (home.vaultGranted) {
            OutlinedButton(onClick = onPickVault) { Text("Change vault") }
        } else {
            Button(onClick = onPickVault) { Text("Grant vault") }
        }
        Text("Model", style = MaterialTheme.typography.titleMedium)
        if (!home.modelReady) {
            Button(onClick = viewModel::downloadModel) { Text("Download model") }
        } else {
            Text("Model is in app storage", color = Color(0xFF8A8A8A))
        }
    }
}
