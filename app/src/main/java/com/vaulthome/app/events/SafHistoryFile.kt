package com.vaulthome.app.events

import android.content.Context
import com.vaulthome.app.search.HistoryFile
import com.vaulthome.app.search.QueryHistory
import com.vaulthome.app.vault.VaultStore
import com.vaulthome.events.Disk
import java.io.IOException

/** [HistoryFile] on `app_settings/VaultHome/history.json` through the vault's SAF grant (write access needed to save). */
class SafHistoryFile(private val context: Context, private val store: VaultStore) : HistoryFile {
    private fun tree() = SafVaultTree(context, store.treeUri() ?: throw IOException("vault folder not granted"))

    override fun read(): ByteArray? {
        if (store.treeUri() == null) return null
        return when (val d = tree().read(QueryHistory.VAULT_PATH)) {
            is Disk.Present -> d.bytes
            Disk.Absent -> null
            is Disk.Skipped -> throw IOException(d.why)
        }
    }

    override fun write(bytes: ByteArray) {
        if (!store.hasWriteAccess()) throw IOException("grant write access to the vault (Settings → Event sync)")
        tree().writeAtomic(QueryHistory.VAULT_PATH, bytes)
    }
}
