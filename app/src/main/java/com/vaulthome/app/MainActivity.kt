package com.vaulthome.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vaulthome.app.ui.NoteScreen
import com.vaulthome.app.ui.SearchScreen
import com.vaulthome.app.ui.SettingsScreen
import com.vaulthome.app.ui.VaultHomeTheme
import com.vaulthome.app.ui.VaultHomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pickVault = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                return@registerForActivityResult
            }
            pendingVault.value?.onVaultPicked(uri)
        }
        val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingVault.value?.startIndex()
        }
        setContent {
            val viewModel: VaultHomeViewModel = viewModel()
            pendingVault.value = viewModel
            var notePath by rememberSaveable { mutableStateOf<String?>(null) }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            val pickVaultTree = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    )
                }
                pickVault.launch(intent)
            }
            val startIndex = {
                val needsNotification = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                if (needsNotification) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.startIndex()
                }
            }
            VaultHomeTheme {
                val path = notePath
                when {
                    path != null -> NoteScreen(path = path, onBack = { notePath = null })
                    showSettings -> SettingsScreen(
                        viewModel = viewModel,
                        onPickVault = pickVaultTree,
                        onBack = { showSettings = false },
                    )
                    else -> SearchScreen(
                        viewModel = viewModel,
                        onSettings = { showSettings = true },
                        onIndex = startIndex,
                        onOpen = { notePath = it },
                    )
                }
            }
        }
    }

    private val pendingVault = androidx.compose.runtime.mutableStateOf<VaultHomeViewModel?>(null)
}
