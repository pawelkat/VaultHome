package com.vaulthome.app.devices

import com.vaulthome.app.ask.AskSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegistryTest {
    private class Cache(var raw: String? = null) : RegistryCache {
        var saves = 0
        override fun load() = raw
        override fun save(raw: String) { this.raw = raw; saves++ }
    }

    private class File(var bytes: ByteArray?, val failRead: Boolean = false, val failWrite: Boolean = false) : RegistryFile {
        var writes = 0
        override fun read(): ByteArray? { if (failRead) error("io"); return bytes }
        override fun write(bytes: ByteArray) { if (failWrite) error("read-only"); writes++; this.bytes = bytes }
    }

    private val bundled = DeviceRegistryJson.bundled()
    private fun json(vararg devices: String) = """{"version":1,"devices":[${devices.joinToString(",")}]}"""

    // ---- parsing

    @Test fun parsesSchemaV1WithPowerDescriptor() {
        val r = DeviceRegistryJson.parse(
            json(
                """{"id":"towerx","name":"TowerX","kind":"pc","endpoints":{"lan":"http://a","tailnet":"https://b"},"chat_proxy":"https://b/",
                   "power":{"controller":"towerx-power","key":"devices/towerx-power","actions":["on","off","status"],"cooldown_s":45,"confirm":["off"],"force_off_hold_s":6}}""",
            ),
        ).getOrThrow()
        val d = r.byId("towerx")!!
        assertEquals("TowerX", d.name)
        assertEquals(mapOf("lan" to "http://a", "tailnet" to "https://b"), d.endpoints)
        assertEquals("https://b", d.chatProxy)
        val p = d.power!!
        assertEquals("towerx-power", p.controller)
        assertEquals("devices/towerx-power", p.key)
        assertEquals(listOf("on", "off", "status"), p.actions)
        assertEquals(45, p.cooldownS)
        assertEquals(listOf("off"), p.confirm)
        assertEquals(6, p.forceOffHoldS)
    }

    @Test fun unknownFieldsAreIgnored() {
        val r = DeviceRegistryJson.parse(
            """{"version":1,"future":{"x":1},"devices":[{"id":"a","name":"A","kind":"pc","color":"red","endpoints":{"lan":"http://a","mesh":"m"},
                "power":{"controller":"c","key":"k","actions":["on","launch-missiles"],"extra":true}}]}""",
        ).getOrThrow()
        assertEquals(1, r.devices.size)
        assertEquals("m", r.devices[0].endpoints["mesh"])
        assertEquals(listOf("on"), r.devices[0].power!!.actions)
    }

    @Test fun defaultsAndSkippedEntries() {
        val r = DeviceRegistryJson.parse(json("""{"id":"x"}""", """{"name":"no id"}""", "42")).getOrThrow()
        assertEquals(1, r.devices.size)
        assertEquals("x", r.devices[0].name)
        assertEquals("unknown", r.devices[0].kind)
        assertEquals("x", r.devices[0].presenceId)
        assertNull(r.devices[0].power)
    }

    @Test fun rejectsBrokenOrWrongVersionOrDuplicateIds() {
        assertTrue(DeviceRegistryJson.parse("not json").isFailure)
        assertTrue(DeviceRegistryJson.parse("""{"devices":[]}""").isFailure)
        assertTrue(DeviceRegistryJson.parse("""{"version":2,"devices":[]}""").isFailure)
        assertTrue(DeviceRegistryJson.parse(json("""{"id":"a"}""", """{"id":"a"}""")).isFailure)
    }

    @Test fun encodeRoundTrips() {
        assertEquals(bundled, DeviceRegistryJson.parse(DeviceRegistryJson.encode(bundled)).getOrThrow())
    }

    @Test fun bundledHasTodaysHardcodedValues() {
        val nitro = bundled.byId("nitro5")!!
        assertEquals(AskSettings.DEFAULT_NITRO_URL, nitro.endpoints["lan"])
        assertEquals("http://192.168.1.18:8082", nitro.endpoints["lan"])
        assertEquals("https://nitro5.tailf52603.ts.net", nitro.endpoints["tailnet"])
        val tx = bundled.byId("towerx")!!
        assertEquals(AskSettings.DEFAULT_TOWERX_URL, tx.chatProxy)
        val p = tx.power!!
        assertEquals("devices/towerx-power", p.key)
        assertEquals(45, p.cooldownS)
        assertEquals(listOf("off"), p.confirm)
        assertEquals(6, p.forceOffHoldS)
        val board = bundled.byId(p.controller)!!
        assertTrue(board.isPowerBoard)
        assertEquals("devices/towerx-power", board.liveliness)
        assertEquals("note4c", board.via)
    }

    // ---- fallback order: vault file, last good cache, built-in defaults

    private val custom = json("""{"id":"mac","name":"Mac mini","kind":"pc"}""")

    @Test fun vaultFileWinsAndRefreshesCache() {
        val cache = Cache("""{"version":1,"devices":[{"id":"old"}]}""")
        val l = DeviceRegistryRepo.resolve(File(custom.toByteArray()), cache, bundled)
        assertEquals(RegistrySource.Vault, l.source)
        assertEquals(listOf("mac"), l.registry.devices.map { it.id })
        assertEquals(custom, cache.raw)
    }

    @Test fun brokenVaultFileFallsBackToCacheAndKeepsIt() {
        val good = json("""{"id":"cached"}""")
        val cache = Cache(good)
        val l = DeviceRegistryRepo.resolve(File("{oops".toByteArray()), cache, bundled)
        assertEquals(RegistrySource.Cache, l.source)
        assertEquals(listOf("cached"), l.registry.devices.map { it.id })
        assertTrue(l.problem!!.contains("ignored"))
        assertEquals(good, cache.raw)
        assertEquals(0, cache.saves)
    }

    @Test fun noVaultFileNoCacheUsesBundled() {
        val l = DeviceRegistryRepo.resolve(File("{oops".toByteArray()), Cache(), bundled)
        assertEquals(RegistrySource.Bundled, l.source)
        assertEquals(bundled, l.registry)
    }

    @Test fun unreadableVaultFallsBackCacheThenBundled() {
        val withCache = DeviceRegistryRepo.resolve(File(null, failRead = true), Cache(custom), bundled)
        assertEquals(RegistrySource.Cache, withCache.source)
        assertEquals(RegistrySource.Bundled, DeviceRegistryRepo.resolve(File(null, failRead = true), Cache(), bundled).source)
        assertEquals(RegistrySource.Bundled, DeviceRegistryRepo.resolve(File(null, failRead = true), Cache("garbage"), bundled).source)
    }

    @Test fun noVaultAtAllUsesCacheThenBundled() {
        assertEquals(RegistrySource.Cache, DeviceRegistryRepo.resolve(null, Cache(custom), bundled).source)
        assertEquals(RegistrySource.Bundled, DeviceRegistryRepo.resolve(null, Cache(), bundled).source)
    }

    // ---- seeding

    @Test fun missingFileIsSeededOnceWithBundledValues() {
        val file = File(null)
        val cache = Cache()
        val l = DeviceRegistryRepo.resolve(file, cache, bundled)
        assertEquals(1, file.writes)
        assertEquals(RegistrySource.Vault, l.source)
        assertEquals(bundled, DeviceRegistryJson.parse(file.bytes!!.toString(Charsets.UTF_8)).getOrThrow())
        assertEquals(cache.raw, file.bytes!!.toString(Charsets.UTF_8))
        DeviceRegistryRepo.resolve(file, cache, bundled)
        assertEquals(1, file.writes)
    }

    @Test fun existingFileIsNeverOverwritten() {
        val file = File(custom.toByteArray())
        DeviceRegistryRepo.resolve(file, Cache(), bundled)
        assertEquals(0, file.writes)
        val broken = File("{oops".toByteArray())
        DeviceRegistryRepo.resolve(broken, Cache(), bundled)
        assertEquals(0, broken.writes)
        assertEquals("{oops", broken.bytes!!.toString(Charsets.UTF_8))
    }

    @Test fun failedSeedFallsBackWithoutCachingTheSeed() {
        val cache = Cache()
        val l = DeviceRegistryRepo.resolve(File(null, failWrite = true), cache, bundled)
        assertEquals(RegistrySource.Bundled, l.source)
        assertTrue(l.problem!!.contains("not seeded"))
        assertNull(cache.raw)
    }

    @Test fun repoReloadAndInitial() {
        val file = File(custom.toByteArray())
        val repo = DeviceRegistryRepo(file, Cache(), bundled)
        assertEquals(RegistrySource.Bundled, repo.initial().source)
        assertEquals(RegistrySource.Vault, repo.reload().source)
        file.bytes = json("""{"id":"new"}""").toByteArray()
        assertEquals(listOf("new"), repo.reload().registry.devices.map { it.id })
        assertFalse(repo.current().registry.devices.any { it.id == "mac" })
    }
}
