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

val TagColors = mapOf("daily" to Color(0xFF4A90E2), "reminder" to Color(0xFFF2A93B))

/**
 * Screen frame with the vault search bar on top. While the bar has focus a dropdown overlay is shown
 * below it: tag chips (daily / reminder) and live results (>= 3 chars, 250 ms debounce in the VM).
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
    var focused by remember { mutableStateOf(false) }
    var barBottom by remember { mutableIntStateOf(0) }
    val close = { focusManager.clearFocus(force = true) }
    BackHandler(enabled = focused) { close() }
    val ime = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(ime, focused) {
        if (!focused) imeSeen = false
        else if (ime) imeSeen = true
        else if (imeSeen) close()
    }
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = home.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
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
        if (focused) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchFilter.TEST_TAGS.forEach { tag ->
                        val c = TagColors.getValue(tag)
                        FilterChip(
                            selected = tag in home.selectedTags,
                            onClick = { viewModel.toggleTag(tag) },
                            label = { Text("#$tag") },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = c, selectedContainerColor = c, selectedLabelColor = Color.Black,
                                selectedLeadingIconColor = Color.Black,
                            ),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = tag in home.selectedTags, borderColor = c),
                        )
                    }
                }
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
