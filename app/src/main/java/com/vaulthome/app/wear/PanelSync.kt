package com.vaulthome.app.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.vaulthome.app.vault.VaultReader
import com.vaulthome.panel.PanelCodec
import com.vaulthome.panel.PanelContract
import com.vaulthome.panel.PanelDedupe
import com.vaulthome.panel.PanelHash
import com.vaulthome.panel.PanelParser
import com.vaulthome.panel.PanelPaths
import kotlinx.coroutines.tasks.await

/**
 * Reads today's `Panel/YYYY-MM-DD.md` (Europe/Zurich) through the read-only SAF grant and publishes
 * the parsed note to the paired watch over the Wearable Data Layer at [PanelContract.PATH_PANEL].
 * Never writes to the vault. Failures (no grant, no file yet, no watch, no Play services) are logged and skipped.
 */
class PanelSync(
    private val context: Context,
    private val reader: VaultReader,
) {
    private val prefs = context.getSharedPreferences("vaulthome_panel", Context.MODE_PRIVATE)

    enum class Result { PUBLISHED, UNCHANGED, NO_FILE, FAILED }

    suspend fun sync(force: Boolean = false): Result {
        val date = PanelPaths.today()
        val markdown = runCatching { reader.readFresh(PanelPaths.pathFor(date)) }.getOrNull()
            ?: return Result.NO_FILE
        val note = PanelParser.parse(date.toString(), markdown)
        val hash = PanelHash.of(note)
        if (!PanelDedupe.shouldPublish(prefs.getString(KEY_LAST_HASH, null), hash, force)) return Result.UNCHANGED
        return runCatching {
            val request = PutDataMapRequest.create(PanelContract.PATH_PANEL).apply {
                dataMap.putString(PanelContract.KEY_DATE, note.date)
                dataMap.putString(PanelContract.KEY_HASH, hash)
                dataMap.putString(PanelContract.KEY_PAYLOAD, PanelCodec.encode(note))
                dataMap.putInt(PanelContract.KEY_VERSION, PanelContract.VERSION)
            }.asPutDataRequest().setUrgent()
            // Same bytes as the stored DataItem → Play services sends no change, so a forced
            // republish on app start does not make the watch buzz again.
            Wearable.getDataClient(context).putDataItem(request).await()
            prefs.edit().putString(KEY_LAST_HASH, hash).apply()
            Result.PUBLISHED
        }.getOrElse { e ->
            Log.d(TAG, "panel publish skipped: ${e.message}")
            Result.FAILED
        }
    }

    private companion object {
        const val TAG = "PanelSync"
        const val KEY_LAST_HASH = "last_published_hash"
    }
}
