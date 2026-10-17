package com.vaulthome.wear.data

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.vaulthome.panel.PanelCodec
import com.vaulthome.panel.PanelContract
import com.vaulthome.panel.PanelNote
import kotlinx.coroutines.tasks.await

/** Latest panel received from the phone, kept on the watch so the tile and screen read it synchronously. */
data class StoredPanel(val note: PanelNote, val hash: String)

class PanelStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("panel", Context.MODE_PRIVATE)

    fun load(): StoredPanel? {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val note = PanelCodec.decode(payload) ?: return null
        return StoredPanel(note, prefs.getString(KEY_HASH, "").orEmpty())
    }

    fun save(panel: StoredPanel) {
        prefs.edit()
            .putString(KEY_PAYLOAD, PanelCodec.encode(panel.note))
            .putString(KEY_HASH, panel.hash)
            .apply()
    }

    var lastNotifiedHash: String?
        get() = prefs.getString(KEY_NOTIFIED, null)
        set(value) = prefs.edit().putString(KEY_NOTIFIED, value).apply()

    /** Pulls the current DataItem (e.g. it was published before this watch app was installed). */
    suspend fun fetchFromPhone(): StoredPanel? = runCatching {
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(PanelContract.PATH_PANEL).build()
        val buffer = Wearable.getDataClient(app).getDataItems(uri, DataClient.FILTER_LITERAL).await()
        try {
            buffer.mapNotNull { fromDataMap(DataMapItem.fromDataItem(it).dataMap) }.maxByOrNull { it.note.date }
        } finally {
            buffer.release()
        }
    }.getOrNull()?.also(::save)

    companion object {
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_HASH = "hash"
        private const val KEY_NOTIFIED = "notified_hash"

        fun fromDataMap(map: DataMap): StoredPanel? {
            if (map.getInt(PanelContract.KEY_VERSION, 0) != PanelContract.VERSION) return null
            val note = map.getString(PanelContract.KEY_PAYLOAD)?.let(PanelCodec::decode) ?: return null
            return StoredPanel(note, map.getString(PanelContract.KEY_HASH).orEmpty())
        }
    }
}
