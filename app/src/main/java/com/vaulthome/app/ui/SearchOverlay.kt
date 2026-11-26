package com.vaulthome.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaulthome.app.search.HistoryEntry
import com.vaulthome.app.search.QueryMode
import com.vaulthome.app.search.SearchFilter
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.vaulthome.app.ask.AskBadge
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.ask.AskMarkdown
import com.vaulthome.app.ask.AskOutcome
import com.vaulthome.app.ask.AskTarget
import com.vaulthome.app.ask.AskViewModel
import com.vaulthome.app.markdown.WikiLinks

val TagPalette = listOf(Color(0xFF4A90E2), Color(0xFFF2A93B), Color(0xFF5CC489), Color(0xFFE5677A), Color(0xFFA77BE0), Color(0xFF3FC1C9), Color(0xFFD9D45B), Color(0xFFE08A4F))

/**
 * Screen frame with the vault search bar on top. While the bar has focus a dropdown overlay is shown
 * below it: tag chips (persisted; journal / reminder by default, '+' adds from the tag index, 'x' removes) and live results (>= 3 chars, 250 ms debounce in the VM).
 * Tapping a result calls [onOpen] and closes the overlay; back, tapping outside or hiding the IME closes it.
 *
 * When Ask is enabled in Settings, a compact Search ↔ Ask switch sits in the top bar left of Settings (mode persisted in AskSettings).
 * In Ask mode the same field takes a question (IME Send); the answer replaces [content] with a markdown panel
 * rendered by [RenderedNote] until it is dismissed (×, back, clear) or the mode goes back to Search. Opening a source
 * only hides it (in-memory cache in [AskViewModel]); back from that note shows the same answer again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScaffold(
    viewModel: VaultHomeViewModel,
    onOpen: (String) -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    topBar: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val askStore = remember { (ctx.applicationContext as VaultHomeApp).graph.askSettings }
    val askSettings by askStore.state.collectAsStateWithLifecycle()
    val askVm: AskViewModel = viewModel()
    val ask by askVm.state.collectAsStateWithLifecycle()
    val askMode = askSettings.askActive
    val askBusy = ask.phase != null
    val showAnswer = com.vaulthome.app.ask.AskCache.visible(ask, askMode)
    val focusManager = LocalFocusManager.current
    var open by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var barBottom by remember { mutableIntStateOf(0) }
    val historyStore = remember { (ctx.applicationContext as VaultHomeApp).graph.queryHistory }
    val history by historyStore.state.collectAsStateWithLifecycle()
    val close = { open = false; picker = false; showHistory = false; focusManager.clearFocus(force = true) }
    val recordSearch = { q: String ->
        if (SearchFilter.shouldSearch(q)) historyStore.record(q, QueryMode.Search)
    }
    val replay = { entry: HistoryEntry ->
        showHistory = false
        if (entry.mode == QueryMode.Ask) {
            if (!askMode) askVm.modeChanged()
            askStore.update { it.copy(askMode = true) }
            askVm.replay(entry.text)
            focusManager.clearFocus(force = true)
        } else {
            if (askMode) askVm.modeChanged()
            askStore.update { it.copy(askMode = false) }
            viewModel.onQueryChange(entry.text)
            recordSearch(entry.text)
            open = true
        }
    }
    BackHandler(enabled = open) { if (picker) picker = false else close() }
    BackHandler(enabled = showHistory && !open) { showHistory = false }
    // Back over an Ask answer (or a running ask) returns to the note underneath.
    BackHandler(enabled = !open && !showHistory && askMode && (showAnswer || askBusy)) { askVm.dismissAnswer() }
    val ime = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(ime, open) {
        if (!open) imeSeen = false
        else if (ime) imeSeen = true
        else if (imeSeen) close()
    }
    LaunchedEffect(askMode) { if (askMode) { open = false; picker = false } }
    val submitAsk = { if (!askBusy && ask.question.isNotBlank()) { askVm.ask(); focusManager.clearFocus(force = true) } }
    val submitSearch = { recordSearch(home.query); close() }
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Top bar: [remote/local] [Search ◯ Ask] [Settings] — the compact switch sits left of Settings so the
            // markdown panel keeps the vertical space.
            if (topBar != null || askSettings.askEnabled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (askSettings.askEnabled) {
                        CompactAskSwitch(askMode = askMode, target = askSettings.target) { mode ->
                            close()
                            if (mode != askMode) askVm.modeChanged()
                            askStore.update { it.copy(askMode = mode) }
                        }
                    }
                    if (topBar != null) topBar()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.onGloballyPositioned { barBottom = (it.positionInParent().y + it.size.height).toInt() }) {
            IconButton(
                onClick = { showHistory = !showHistory; if (showHistory) { open = false; picker = false; focusManager.clearFocus(force = true) } },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(HistoryIcon, contentDescription = "Search and Ask history", tint = Color(0xFFB0B0B0), modifier = Modifier.size(22.dp))
            }
            OutlinedTextField(
                value = if (askMode) ask.question else home.query,
                onValueChange = if (askMode) askVm::onQuestionChange else viewModel::onQueryChange,
                modifier = Modifier.weight(1f)
                    .onFocusChanged { if (it.isFocused && !askMode) open = true },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text(if (askMode) "Ask a question…" else "Search the vault") },
                keyboardOptions = KeyboardOptions(imeAction = if (askMode) ImeAction.Send else ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }, onSend = { submitAsk() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFB0B0B0), unfocusedBorderColor = Color(0xFF8A8A8A),
                    focusedTextColor = Color(0xFFE6E6E6), unfocusedTextColor = Color(0xFFE6E6E6),
                    cursorColor = Color(0xFFE6E6E6),
                    focusedPlaceholderColor = Color(0xFF8A8A8A), unfocusedPlaceholderColor = Color(0xFF8A8A8A),
                    focusedTrailingIconColor = Color(0xFF8A8A8A), unfocusedTrailingIconColor = Color(0xFF8A8A8A),
                ),
                trailingIcon = {
                    if (askMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (askBusy) {
                                IconButton(onClick = askVm::cancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel ask") }
                            } else if (ask.question.isNotEmpty()) {
                                IconButton(onClick = askVm::clear) { Icon(Icons.Filled.Close, contentDescription = "Clear question") }
                            }
                        }
                    } else if (home.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }
                },
            )
            actions()
            }
            if (askMode && askBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(AskMarkdown.phaseText(ask.phase!!), color = Color(0xFF8A8A8A), style = MaterialTheme.typography.labelSmall)
            }
            val outcome = ask.outcome
            if (showAnswer && outcome != null) {
                AskAnswerPanel(
                    question = ask.asked,
                    outcome = outcome,
                    onDismiss = askVm::dismissAnswer,
                    onOpen = { path -> askVm.openSource(path); onOpen(path) },
                )
            } else {
                content()
            }
        }
        if (open) {
            val top = with(LocalDensity.current) { barBottom + 16.dp.roundToPx() + 4.dp.roundToPx() }
            // Scrim: tap outside closes.
            Box(
                Modifier.fillMaxSize().offset { IntOffset(0, top) }.background(Color(0x99000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            )
            Column(
                Modifier.offset { IntOffset(0, top) }.padding(horizontal = 16.dp).fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    home.chips.forEach { chip ->
                        val c = TagPalette[chip.color % TagPalette.size]
                        val sel = chip.tag in home.selectedTags
                        FilterChip(
                            selected = sel,
                            onClick = { viewModel.toggleTag(chip.tag) },
                            label = { Text("#${chip.tag}") },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close, contentDescription = "Remove #${chip.tag}",
                                    modifier = Modifier.size(16.dp).clickable { viewModel.removeChip(chip.tag) },
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = c, iconColor = c, selectedContainerColor = c, selectedLabelColor = Color.Black,
                                selectedLeadingIconColor = Color.Black, selectedTrailingIconColor = Color.Black,
                            ),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = sel, borderColor = c),
                        )
                    }
                    IconButton(onClick = { picker = !picker }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add tag chip", tint = Color(0xFFE6E6E6))
                    }
                }
                if (picker) TagPicker(viewModel, home.query, home.rawHits, home.chips.size) { tag -> viewModel.addChip(tag); picker = false }
                if (home.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    !SearchFilter.shouldSearch(home.query) ->
                        Text("Type ${SearchFilter.MIN_QUERY}+ characters", color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodySmall)
                    home.message.isNotEmpty() && home.hits.isEmpty() ->
                        Text(home.message, color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodySmall)
                }
                if (SearchFilter.shouldSearch(home.query)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(home.hits) { hit ->
                            Column(
                                Modifier.fillMaxWidth().background(Color(0xFF242424), RoundedCornerShape(8.dp))
                                    .clickable { recordSearch(home.query); close(); onOpen(hit.path) }.padding(10.dp),
                            ) {
                                Text(hit.path, color = Color(0xFFE6E6E6), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (hit.heading.isNotEmpty()) Text(hit.heading, color = Color(0xFF8A8A8A), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                Text(hit.snippet, color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (showHistory && !open) {
            val top = with(LocalDensity.current) { barBottom + 16.dp.roundToPx() + 4.dp.roundToPx() }
            Box(
                Modifier.fillMaxSize().offset { IntOffset(0, top) }.background(Color(0x99000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showHistory = false },
            )
            Column(
                Modifier.offset { IntOffset(0, top) }.padding(horizontal = 16.dp).fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("History", color = Color(0xFFE6E6E6), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (history.isNotEmpty()) {
                        androidx.compose.material3.TextButton(onClick = { historyStore.clear() }) {
                            Text("Clear", color = Color(0xFF8A8A8A))
                        }
                    }
                }
                if (history.isEmpty()) {
                    Text("No search or Ask history yet.", color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(history, key = { "${it.mode}:${it.atMs}:${it.text}" }) { entry ->
                            Column(
                                Modifier.fillMaxWidth().background(Color(0xFF242424), RoundedCornerShape(8.dp))
                                    .clickable { replay(entry) }.padding(10.dp),
                            ) {
                                Text(if (entry.mode == QueryMode.Ask) "Ask" else "Search", color = Color(0xFF9BC4F0), style = MaterialTheme.typography.labelSmall)
                                Text(entry.text, color = Color(0xFFE6E6E6), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Material History icon, inlined so we don't pull in material-icons-extended. */
private val HistoryIcon: ImageVector = ImageVector.Builder(
    name = "History", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(13f, 3f)
        curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
        horizontalLineTo(1f)
        lineToRelative(3.89f, 3.89f)
        lineToRelative(0.07f, 0.14f)
        lineTo(9f, 12f)
        horizontalLineTo(6f)
        curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f)
        reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
        reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
        curveToRelative(-1.93f, 0f, -3.68f, -0.79f, -4.94f, -2.06f)
        lineToRelative(-1.42f, 1.42f)
        curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
        curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f)
        reflectiveCurveToRelative(-4.03f, -9f, -9f, -9f)
        close()
        moveTo(12f, 8f)
        verticalLineToRelative(5f)
        lineToRelative(4.28f, 2.54f)
        lineToRelative(0.72f, -1.21f)
        lineToRelative(-3.5f, -2.08f)
        verticalLineTo(8f)
        horizontalLineTo(12f)
        close()
    }
}.build()

@Composable
private fun TagPicker(viewModel: VaultHomeViewModel, query: String, hits: Any, chipCount: Int, onPick: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<com.vaulthome.app.search.TagSuggestion>>(emptyList()) }
    LaunchedEffect(filter, query, hits, chipCount) { items = viewModel.tagSuggestions(filter) }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF232323), RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = { Text("Filter tags") }, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        if (items.isEmpty()) {
            Text("No tags in the index yet. Run Index.", color = Color(0xFF8A8A8A), style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.heightIn(max = 180.dp)) {
            items(items) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(s.tag) }.padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("#${s.tag}", color = Color(0xFFE6E6E6), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (s.relevant) "%.2f".format(s.score) else "${s.score.toInt()}",
                        color = if (s.relevant) Color(0xFF9BC4F0) else Color(0xFF6E6E6E),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private val SwitchInk = Color(0xFFE6E6E6)
private val SwitchMuted = Color(0xFF8A8A8A)
private val RemoteInk = Color(0xFF9BC4F0)
private val LocalInk = Color(0xFF5CC489)

/**
 * Compact "Search ◯ Ask" switch for the top bar (left of Settings). In Ask mode a small badge before it says where
 * the question goes (remote Nitro / local phone).
 */
@Composable
private fun CompactAskSwitch(askMode: Boolean, target: AskTarget, onMode: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (askMode) {
            // Who would answer right now: first of the routing (pin, auto-pick rule, then any ready provider).
            val graph = (LocalContext.current.applicationContext as com.vaulthome.app.VaultHomeApp).graph
            val settingsNow by graph.askSettings.state.collectAsStateWithLifecycle()
            val providers by com.vaulthome.app.ask.ProviderMonitor.state.collectAsStateWithLifecycle()
            val healthNow by com.vaulthome.app.ask.ProviderHealth.state.collectAsStateWithLifecycle()
            val host = remember(settingsNow, providers, healthNow) { graph.askOrchestrator.routing().order.firstOrNull()?.hostLabel ?: "none available" }
            val (label, ink) = AskBadge.label(target, host) to if (target == AskTarget.Remote) RemoteInk else LocalInk
            Text(
                label, color = ink, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { com.vaulthome.app.ask.ProvidersNav.show() }
                    .background(Color(0xFF1A1A1A)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "Search", color = if (askMode) SwitchMuted else SwitchInk, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable { onMode(false) }.padding(horizontal = 3.dp, vertical = 4.dp),
        )
        // M3 Switch is 52×32 with a 48dp touch target; scale it down inside a fixed 36×22 slot.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Box(Modifier.size(width = 36.dp, height = 22.dp), contentAlignment = Alignment.Center) {
                Switch(
                    checked = askMode, onCheckedChange = onMode,
                    modifier = Modifier.scale(0.65f).semantics { contentDescription = "Ask mode" },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF101010), checkedTrackColor = Color(0xFFB0B0B0),
                        uncheckedThumbColor = Color(0xFF8A8A8A), uncheckedTrackColor = Color(0xFF242424), uncheckedBorderColor = Color(0xFF8A8A8A),
                    ),
                )
            }
        }
        Text(
            "Ask", color = if (askMode) SwitchInk else SwitchMuted, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable { onMode(true) }.padding(horizontal = 3.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(4.dp))
    }
}

/** Ask answer in the same markdown panel as notes; source wikilinks open notes in-app. */
@Composable
private fun ColumnScope.AskAnswerPanel(question: String, outcome: AskOutcome, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val markdown = remember(question, outcome) { AskMarkdown.format(question, outcome) }
    val remote = outcome is AskOutcome.Nitro
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (remote) "Remote" else "Local",
            color = if (remote) RemoteInk else LocalInk, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            AskMarkdown.sourceLabel(outcome), color = SwitchInk, style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        // Sends exactly the markdown rendered below (header line, answer body, sources).
        X4SendButton(onSend = { it.sendAnswer(markdown) }, modifier = Modifier.size(32.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Close answer", tint = Color(0xFFB0B0B0), modifier = Modifier.size(20.dp))
        }
    }
    Box(
        Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101010))
            .verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        RenderedNote(
            markdown = markdown,
            onLink = { uri -> VaultLinkOpener.onLink(context, scope, uri, onOpenNote = onOpen) },
            onWikiLink = { target -> VaultLinkOpener.onWikiLink(context, scope, target, onOpenNote = onOpen) },
        )
    }
}
