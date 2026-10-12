package com.vaulthome.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.index.IndexProgress
import com.vaulthome.app.index.IndexWorker
import com.vaulthome.app.search.SearchHit
import com.vaulthome.app.search.SearchFilter
import com.vaulthome.app.search.TagChips
import com.vaulthome.app.search.TagSuggestion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val vaultGranted: Boolean = false,
    val modelReady: Boolean = false,
    val chunkCount: Int = 0,
    val message: String = "",
    val selectedTags: Set<String> = emptySet(),
    val chips: List<com.vaulthome.app.search.TagChip> = emptyList(),
    val rawHits: List<SearchHit> = emptyList(),
)

class VaultHomeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as VaultHomeApp).graph
    private val searchState = MutableStateFlow(HomeState())
    private var searchJob: Job? = null
    private val tagCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private val prefs = app.getSharedPreferences("vaulthome_ui", android.content.Context.MODE_PRIVATE)

    init {
        searchState.value = searchState.value.copy(chips = TagChips.decode(prefs.getString("chips", null)))
    }

    private fun saveChips(chips: List<com.vaulthome.app.search.TagChip>) {
        prefs.edit().putString("chips", TagChips.encode(chips)).apply()
    }

    fun addChip(tag: String) {
        val chips = TagChips.add(searchState.value.chips, tag)
        val t = TagChips.normalize(tag)
        searchState.value = searchState.value.copy(chips = chips, selectedTags = searchState.value.selectedTags + t)
        saveChips(chips)
        onQueryChange(searchState.value.query)
    }

    fun removeChip(tag: String) {
        val chips = TagChips.remove(searchState.value.chips, tag)
        searchState.value = searchState.value.copy(chips = chips, selectedTags = searchState.value.selectedTags - tag)
        saveChips(chips)
        onQueryChange(searchState.value.query)
    }

    /** Picker suggestions; runs off the main thread (may read tags of uncached hit notes). */
    suspend fun tagSuggestions(filter: String): List<TagSuggestion> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val st = searchState.value
        TagChips.rank(
            frequencies = graph.tags.frequencies(),
            hits = st.rawHits.map { it.path to it.score },
            tagsOf = ::tagsOf,
            query = st.query,
            exclude = st.chips.map { it.tag }.toSet(),
            filter = filter,
        ).take(100)
    }

    fun toggleTag(tag: String) {
        val cur = searchState.value.selectedTags
        searchState.value = searchState.value.copy(selectedTags = if (tag in cur) cur - tag else cur + tag)
        onQueryChange(searchState.value.query)
    }

    private fun tagsOf(path: String): Set<String> = graph.tags.get(path) ?: tagCache.getOrPut(path) {
        runCatching { graph.reader.readNote(path) }.getOrNull()?.let { SearchFilter.extractTags(it) } ?: emptySet()
    }

    val home: StateFlow<HomeState> = searchState
    val index: StateFlow<IndexProgress> = graph.status.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        graph.status.state.value,
    )

    init {
        refresh()
        viewModelScope.launch {
            var previous = graph.status.state.value.phase
            graph.status.state.collect { progress ->
                if (progress.phase != previous && (progress.phase == "ready" || progress.phase == "error")) {
                    refresh()
                }
                previous = progress.phase
            }
        }
    }

    fun refresh() {
        tagCache.clear()
        viewModelScope.launch {
            val count = graph.database.chunks().chunkCount()
            searchState.value = searchState.value.copy(
                vaultGranted = graph.store.hasReadAccess(),
                modelReady = graph.models.isReady(),
                chunkCount = count,
            )
        }
    }

    fun onVaultPicked(uri: Uri) {
        val previous = graph.store.treeUri()
        graph.store.setTreeUri(uri)
        graph.reader.invalidate()
        viewModelScope.launch {
            if (previous != null && previous != uri) {
                graph.database.chunks().clearChunks()
                graph.database.chunks().clearNotes()
                graph.tags.clear()
            }
            refresh()
        }
    }

    fun onQueryChange(value: String) {
        searchJob?.cancel()
        if (!SearchFilter.shouldSearch(value)) {
            searchState.value = searchState.value.copy(
                query = value,
                hits = emptyList(),
                rawHits = emptyList(),
                searching = false,
                message = if (value.isBlank()) "" else "Type ${SearchFilter.MIN_QUERY}+ characters",
            )
            return
        }
        searchState.value = searchState.value.copy(query = value, message = "")
        searchJob = viewModelScope.launch {
            delay(250)
            runSearch(value)
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            graph.status.update(
                IndexProgress(phase = "downloading", message = "Downloading the embedding model"),
            )
            try {
                graph.models.ensure { bytes, total ->
                    val message = if (total > 0) {
                        "Downloading model ${bytes / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                    } else {
                        "Downloading model ${bytes / (1024 * 1024)} MB"
                    }
                    graph.status.update(IndexProgress(phase = "downloading", message = message))
                }
                refresh()
                graph.status.update(IndexProgress(phase = "idle", message = "Model is in app storage"))
            } catch (error: Exception) {
                graph.status.update(
                    IndexProgress(phase = "error", message = error.message ?: "Download failed"),
                )
            }
        }
    }

    fun startIndex() {
        val request = OneTimeWorkRequestBuilder<IndexWorker>().build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            IndexWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private suspend fun runSearch(value: String) {
        val query = value.trim()
        if (query.isEmpty()) {
            searchState.value = searchState.value.copy(hits = emptyList(), searching = false, message = "")
            return
        }
        if (!graph.models.isReady()) {
            searchState.value = searchState.value.copy(
                hits = emptyList(),
                searching = false,
                message = "Download the embedding model before searching",
            )
            return
        }
        searchState.value = searchState.value.copy(searching = true, message = "")
        try {
            val selected = searchState.value.selectedTags
            val raw = graph.searcher.search(query, if (selected.isEmpty()) com.vaulthome.app.index.SearchConfig.TOP_K else 200)
            val hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SearchFilter.filter(raw, selected, { it.path }, ::tagsOf)
            }.take(com.vaulthome.app.index.SearchConfig.TOP_K)
            searchState.value = searchState.value.copy(
                hits = hits,
                rawHits = raw,
                searching = false,
                message = when {
                    hits.isNotEmpty() -> ""
                    raw.isNotEmpty() -> "No hits with tag " + selected.joinToString(" or ") { "#$it" }
                    else -> "No hits yet. Index the vault first."
                },
                modelReady = true,
            )
        } catch (error: Exception) {
            searchState.value = searchState.value.copy(
                searching = false,
                message = error.message ?: "Search failed",
            )
        }
    }
}
