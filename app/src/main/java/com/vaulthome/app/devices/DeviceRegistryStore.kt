package com.vaulthome.app.devices

import android.content.Context
import com.vaulthome.app.vault.VaultStore
import com.vaulthome.events.Disk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** [RegistryFile] on `app_settings/VaultEvents/devices.json` through the vault's SAF grant (write access needed to seed). */
class SafRegistryFile(private val context: Context, private val store: VaultStore) : RegistryFile {
    private fun tree() = com.vaulthome.app.events.SafVaultTree(context, store.treeUri() ?: throw IOException("vault folder not granted"))

    override fun read(): ByteArray? {
        if (store.treeUri() == null) throw IOException("vault folder not granted")
        return when (val d = tree().read(DeviceRegistryJson.VAULT_PATH)) {
            is Disk.Present -> d.bytes
            Disk.Absent -> null
            is Disk.Skipped -> throw IOException(d.why)
        }
    }

    override fun write(bytes: ByteArray) {
        if (!store.hasWriteAccess()) throw IOException("grant write access to the vault (Settings → Event sync)")
        tree().writeAtomic(DeviceRegistryJson.VAULT_PATH, bytes)
    }
}

/** Last good copy in app storage. */
class FileRegistryCache(private val file: File) : RegistryCache {
    override fun load(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
    override fun save(raw: String) {
        runCatching { file.parentFile?.mkdirs(); file.writeText(raw) }
    }
}

/** Android wiring: the registry as a flow, loaded from cache/defaults at once and from the vault in the background. */
class DeviceRegistryStore(context: Context, vaultStore: VaultStore) {
    private val repo = DeviceRegistryRepo(
        SafRegistryFile(context, vaultStore),
        FileRegistryCache(File(context.filesDir, "devices-registry.json")),
    )
    private val flow = MutableStateFlow(repo.initial())
    val state: StateFlow<LoadedRegistry> = flow
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { reload() }

    /** At start, on the Devices page, after a session's first catch-up, and when a new `devices.json` is applied by sync. */
    fun reload() {
        scope.launch { flow.value = repo.reload() }
    }
}
