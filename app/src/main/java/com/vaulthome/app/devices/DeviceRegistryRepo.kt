package com.vaulthome.app.devices

/** The vault file `devices.json`. [read] returns null when absent and throws when it cannot be read; [write] throws without write access. */
interface RegistryFile {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/** Last good copy of the registry on the phone. */
interface RegistryCache {
    fun load(): String?
    fun save(raw: String)
}

/**
 * Registry with three copies: the vault file (shared, wins), the last good local copy, and the built-in defaults.
 * [reload] reads the vault file; a missing file is seeded with the defaults, an existing one is never overwritten,
 * and a broken one falls back to the cache and then to the defaults. Blocking and synchronized: call off the main thread.
 */
class DeviceRegistryRepo(
    private val file: RegistryFile?,
    private val cache: RegistryCache,
    private val bundled: DeviceRegistry = DeviceRegistryJson.bundled(),
) {
    private var current: LoadedRegistry = fallback(null)

    /** Cache first, then built-in defaults; never touches the vault. */
    fun initial(): LoadedRegistry = synchronized(this) { fallback(null).also { current = it } }

    @Synchronized fun current(): LoadedRegistry = current

    @Synchronized fun reload(): LoadedRegistry {
        current = resolve(file, cache, bundled)
        return current
    }

    private fun fallback(problem: String?): LoadedRegistry = Companion.fallback(cache, bundled, problem)

    companion object {
        private fun fallback(cache: RegistryCache, bundled: DeviceRegistry, problem: String?): LoadedRegistry {
            cache.load()?.let { raw ->
                DeviceRegistryJson.parse(raw).getOrNull()?.let { return LoadedRegistry(it, RegistrySource.Cache, problem) }
            }
            return LoadedRegistry(bundled, RegistrySource.Bundled, problem)
        }

        /** Pure order of preference (unit-tested): vault file, last good cache, built-in defaults. */
        fun resolve(file: RegistryFile?, cache: RegistryCache, bundled: DeviceRegistry): LoadedRegistry {
            if (file == null) return fallback(cache, bundled, null)
            val bytes = try {
                file.read()
            } catch (e: Exception) {
                return fallback(cache, bundled, "devices.json unreadable: ${e.message}")
            }
            if (bytes == null) {
                // Absent: seed once with today's values; never overwrite an existing file.
                val seeded = DeviceRegistryJson.encode(bundled)
                val problem = try {
                    file.write(seeded.toByteArray(Charsets.UTF_8)); null
                } catch (e: Exception) {
                    "devices.json not seeded: ${e.message}"
                }
                if (problem == null) cache.save(seeded)
                return if (problem == null) LoadedRegistry(bundled, RegistrySource.Vault)
                else fallback(cache, bundled, problem)
            }
            val text = bytes.toString(Charsets.UTF_8)
            val parsed = DeviceRegistryJson.parse(text)
            parsed.getOrNull()?.let {
                cache.save(text)
                return LoadedRegistry(it, RegistrySource.Vault)
            }
            return fallback(cache, bundled, "devices.json ignored: ${parsed.exceptionOrNull()?.message}")
        }
    }
}
