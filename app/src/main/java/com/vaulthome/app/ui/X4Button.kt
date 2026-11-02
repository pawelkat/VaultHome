package com.vaulthome.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vaulthome.app.x4.X4Detect
import com.vaulthome.app.x4.X4Text
import com.vaulthome.app.x4.X4ViewModel

private val X4On = Color(0xFFE6E6E6)
private val X4Off = Color(0xFF555555)

/**
 * Compact "X4" action for the note row and the Ask answer header. Bright when the X4 answered the last probe,
 * grey otherwise; a grey tap re-probes the configured address and says why it is not found. The probe runs when
 * the screen resumes (throttled), so the state follows the panel being switched on.
 */
@Composable
fun X4SendButton(onSend: (X4ViewModel) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: X4ViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.probe()
        onPauseOrDispose { }
    }
    LaunchedEffect(vm) {
        vm.events.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    val detected = state.detected
    val busy = state.sending || state.detect is X4Detect.Probing
    IconButton(
        onClick = {
            when {
                state.sending -> Unit
                detected -> onSend(vm)
                else -> {
                    Toast.makeText(context, X4Text.detect(state.detect) + " — checking again", Toast.LENGTH_SHORT).show()
                    vm.probe(force = true)
                }
            }
        },
        modifier = modifier.semantics {
            contentDescription = if (detected) "Send to X4" else "X4 not found"
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "X4", color = if (detected) X4On else X4Off, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium,
            )
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFF8A8A8A), strokeWidth = 1.5.dp)
        }
    }
}
