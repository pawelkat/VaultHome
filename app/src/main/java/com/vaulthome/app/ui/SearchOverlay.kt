package com.vaulthome.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaulthome.app.search.SearchFilter

val TagPalette = listOf(Color(0xFF4A90E2), Color(0xFFF2A93B), Color(0xFF5CC489), Color(0xFFE5677A), Color(0xFFA77BE0), Color(0xFF3FC1C9), Color(0xFFD9D45B), Color(0xFFE08A4F))

/**
 * Screen frame with the vault search bar on top. While the bar has focus a dropdown overlay is shown
 * below it: tag chips (persisted; journal / reminder by default, '+' adds from the tag index, 'x' removes) and live results (>= 3 chars, 250 ms debounce in the VM).
 * Tapping a result calls [onOpen] and closes the overlay; back, tapping outside or hiding the IME closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScaffold(
    viewModel: VaultHomeViewModel,
    onOpen: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var open by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var barBottom by remember { mutableIntStateOf(0) }
    val close = { open = false; picker = false; focusManager.clearFocus(force = true) }
    BackHandler(enabled = open) { if (picker) picker = false else close() }
    val ime = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(ime, open) {
        if (!open) imeSeen = false
        else if (ime) imeSeen = true
        else if (imeSeen) close()
    }
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = home.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) open = true }
                    .onGloballyPositioned { barBottom = it.size.height },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text("Search the vault") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { close() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFB0B0B0), unfocusedBorderColor = Color(0xFF8A8A8A),
                    focusedTextColor = Color(0xFFE6E6E6), unfocusedTextColor = Color(0xFFE6E6E6),
                    cursorColor = Color(0xFFE6E6E6),
                    focusedPlaceholderColor = Color(0xFF8A8A8A), unfocusedPlaceholderColor = Color(0xFF8A8A8A),
                    focusedTrailingIconColor = Color(0xFF8A8A8A), unfocusedTrailingIconColor = Color(0xFF8A8A8A),
                ),
                trailingIcon = {
                    if (home.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }
                },
            )
            content()
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
                                    .clickable { close(); onOpen(hit.path) }.padding(10.dp),
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
    }
}

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
