package com.vaulthome.app.index

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class IndexProgress(
    val phase: String = "idle",
    val message: String = "Index is empty",
    val done: Int = 0,
    val total: Int = 0,
)

class IndexStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences("vaulthome", Context.MODE_PRIVATE)
    private val stateFlow = MutableStateFlow(load())

    val state: StateFlow<IndexProgress> = stateFlow

    fun update(progress: IndexProgress) {
        stateFlow.value = progress
        prefs.edit()
            .putString(KEY_PHASE, progress.phase)
            .putString(KEY_MESSAGE, progress.message)
            .putInt(KEY_DONE, progress.done)
            .putInt(KEY_TOTAL, progress.total)
            .apply()
    }

    private fun load(): IndexProgress {
        return IndexProgress(
            phase = prefs.getString(KEY_PHASE, "idle") ?: "idle",
            message = prefs.getString(KEY_MESSAGE, "Index is empty") ?: "Index is empty",
            done = prefs.getInt(KEY_DONE, 0),
            total = prefs.getInt(KEY_TOTAL, 0),
        )
    }

    private companion object {
        const val KEY_PHASE = "index_phase"
        const val KEY_MESSAGE = "index_message"
        const val KEY_DONE = "index_done"
        const val KEY_TOTAL = "index_total"
    }
}
