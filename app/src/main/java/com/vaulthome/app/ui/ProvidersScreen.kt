package com.vaulthome.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.ask.Availability
import com.vaulthome.app.ask.ProviderAvailability
import com.vaulthome.app.ask.ProviderHealth
import com.vaulthome.app.ask.ProviderMonitor
import com.vaulthome.app.ask.ProviderProbe
import com.vaulthome.app.ask.ProviderRouting
import com.vaulthome.app.ask.Skills
import kotlinx.coroutines.delay

private val Green = Color(0xFF5CC489)

/**
 * Ask providers page, opened from the "Remote · …" badge. Every provider shows its availability; unavailable ones are
 * greyed out and cannot be picked. "Auto" follows the routing rule; a pinned provider that goes offline stays pinned
 * (greyed) and Ask falls through to the next one. Availability sources: see [ProviderAvailability].
 */
@Composable
fun ProvidersScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val graph = (ctx.applicationContext as VaultHomeApp).graph
    val store = graph.askSettings
    val ask by store.state.collectAsStateWithLifecycle()
    val ps by ProviderMonitor.state.collectAsStateWithLifecycle()
    val health by ProviderHealth.state.collectAsStateWithLifecycle()

    // Probe on open and every few seconds while the page is shown (TowerX off → greyed; Nitro5 down → greyed).
    LaunchedEffect(Unit) {
        while (true) {
            val s = store.state.value
            val toProbe = ProviderRouting.all(ProviderMonitor.state.value.manifests, s)
                .filter { ProviderAvailability.settingsSwitch(s).availability(it) == null }
            ProviderProbe.probe(graph.nitro, toProbe)
            delay(ProviderProbe.INTERVAL_MS)
        }
    }

    val all = ProviderRouting.all(ps.manifests, ask)
    val sources = ProviderAvailability.chain(ask, { ps.alive[it] }, { health[it] })
    val state = all.associate { it.id to ProviderAvailability.of(it, ask.skills, sources) }
    val chat = all.filter { it.isChat }
    val routing = ProviderRouting.route(all, ask.pinnedProviderId, ask.skills) { state[it.id] ?: Availability.Unknown }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Ask providers", style = MaterialTheme.typography.titleLarge, color = Ink)
        }
        if (!ps.session) Text("Event sync is off: no Zenoh caps. Availability comes from /health probes and the Settings URLs.", color = Muted, style = MaterialTheme.typography.bodySmall)

        val autoNow = routing.order.firstOrNull()
        Row(
            Modifier.fillMaxWidth().selectable(selected = ask.pinnedProviderId == null, onClick = { store.update { it.copy(pinnedProviderId = null) } }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = ask.pinnedProviderId == null, onClick = null)
            Text("Auto" + (autoNow?.let { " (now: ${it.hostLabel})" } ?: " (none available)"), color = Ink, modifier = Modifier.padding(start = 8.dp))
        }

        chat.forEach { p ->
            val a = state[p.id] ?: Availability.Unknown
            val usable = a.routable
            val pinned = ask.pinnedProviderId == p.id
            val ink = if (usable) Ink else Muted.copy(alpha = 0.55f)
            Row(
                Modifier.fillMaxWidth().let { if (usable) it.selectable(selected = pinned, onClick = { store.update { s -> s.copy(pinnedProviderId = p.id) } }) else it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = pinned, onClick = null, enabled = usable)
                Column(Modifier.padding(start = 8.dp)) {
                    Text("${p.title} · ${p.hostLabel}", color = ink)
                    val detail = buildList {
                        add(a.label)
                        if (pinned && !usable) add("pinned, skipped while ${a.label}")
                        add("skills ${p.skills.joinToString("/")}")
                        p.nCtx?.let { add("n_ctx $it") }
                        health[p.device]?.takeIf { !it.ok }?.let { add(it.detail.take(80)) }
                    }.joinToString(" · ")
                    Text(detail, color = if (a == Availability.Available) Green else Muted.copy(alpha = if (usable) 1f else 0.55f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        all.filter { !it.isChat }.forEach { p ->
            Text("${p.title}: ${p.skills.joinToString("/")} only, not a chat backend", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        // Skills the user can ask for: only those a currently routable provider advertises.
        val offered = chat.filter { (state[it.id] ?: Availability.Unknown).routable }.flatMap { it.skills }.toSet()
        if (Skills.CODE in offered) {
            val v = Skills.VAULT
            val c = Skills.CODE
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Skills", color = Ink)
                listOf("Vault" to setOf(v), "Code" to setOf(c), "Both" to setOf(v, c)).forEach { (label, set) ->
                    FilterChip(selected = ask.skills == set, onClick = { store.update { it.copy(skills = set) } }, label = { Text(label) })
                }
            }
        } else if (ask.skills != setOf(Skills.VAULT)) {
            Text("Skills ${ask.skills.joinToString("/")} selected, but no available provider offers code.", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { store.update { it.copy(skills = setOf(Skills.VAULT)) } }) { Text("Use Vault only") }
        }
        ps.errors.forEach { (d, e) -> Text("$d caps: $e", color = Muted, style = MaterialTheme.typography.bodySmall) }
        Text("URLs and on/off switches per host stay in Settings.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
