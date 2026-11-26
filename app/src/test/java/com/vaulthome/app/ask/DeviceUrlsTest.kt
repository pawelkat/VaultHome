package com.vaulthome.app.ask

import com.vaulthome.app.devices.PowerDescriptor
import com.vaulthome.app.devices.RegDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUrlsTest {
    private val suffix = TailnetUrls.FALLBACK_SUFFIX
    private fun pc(id: String, endpoints: Map<String, String> = emptyMap(), chatProxy: String? = null) =
        RegDevice(id, id, RegDevice.KIND_PC, endpoints = endpoints, chatProxy = chatProxy)

    @Test fun presenceNameBecomesHttpsDeviceDotSuffix() {
        assertEquals("https://nitro5.tailf52603.ts.net", TailnetUrls.derived("nitro5", suffix))
        assertEquals("https://towerx.tailf52603.ts.net", TailnetUrls.derived("towerx", suffix))
        assertEquals("https://nitro5.tailf52603.ts.net", DeviceUrls.resolve("nitro5", suffix).primary)
        assertEquals("https://towerx.tailf52603.ts.net", DeviceUrls.resolve("towerx", suffix).primary)
        assertEquals(UrlSource.Derived, DeviceUrls.resolve("nitro5", suffix).source)
        assertNull(DeviceUrls.resolve("nitro5", suffix).lan)
    }

    @Test fun suffixFromRouterHost() {
        assertEquals("tailf52603.ts.net", TailnetUrls.suffix(listOf("tcp/n910c.tailf52603.ts.net:7447")))
        assertEquals("tailf52603.ts.net", TailnetUrls.suffix(listOf("tcp/note4.tailf52603.ts.net:7447")))
        assertEquals("tailf52603.ts.net", TailnetUrls.hostOf("tcp/n910c.tailf52603.ts.net:7447")?.let { TailnetUrls.suffixOfHost(it) })
        assertEquals("other.ts.net", TailnetUrls.suffix(listOf("tls/box.other.ts.net:7447")))
        assertEquals("n910c.tailf52603.ts.net", TailnetUrls.hostOf("tcp/n910c.tailf52603.ts.net:7447"))
    }

    @Test fun suffixFallsBackWhenRouterIsNotTsNet() {
        assertEquals(TailnetUrls.FALLBACK_SUFFIX, TailnetUrls.suffix(emptyList()))
        assertEquals(TailnetUrls.FALLBACK_SUFFIX, TailnetUrls.suffix(listOf("tcp/192.168.1.29:7447")))
        assertEquals(TailnetUrls.FALLBACK_SUFFIX, TailnetUrls.suffix(listOf("tcp/[::1]:7447", "not-an-endpoint")))
        assertEquals("tailf52603.ts.net", TailnetUrls.suffix(listOf("tcp/192.168.1.29:7447", "tcp/n910c.tailf52603.ts.net:7447")))
        assertNull(TailnetUrls.suffixOfHost("example.com"))
        assertNull(TailnetUrls.suffixOfHost("ts.net"))
    }

    @Test fun overrideOrderManifestThenRegistryThenDerived() {
        val listed = pc("nitro5", endpoints = mapOf("tailnet" to "https://custom.example", "lan" to "http://192.168.1.18:8082"), chatProxy = "https://proxy.example")
        val fromRegistry = DeviceUrls.resolve("nitro5", suffix, listed)
        assertEquals("https://proxy.example", fromRegistry.primary)
        assertEquals(UrlSource.Registry, fromRegistry.source)
        assertEquals("http://192.168.1.18:8082", fromRegistry.lan)
        val tailnetOnly = DeviceUrls.resolve("nitro5", suffix, pc("nitro5", endpoints = mapOf("tailnet" to "https://tail.example/v1")))
        assertEquals("https://tail.example", tailnetOnly.primary)
        assertEquals(UrlSource.Registry, tailnetOnly.source)
        val fromManifest = DeviceUrls.resolve("nitro5", suffix, listed, manifestEndpoint = "https://caps.example/v1/chat/completions")
        assertEquals("https://caps.example", fromManifest.primary)
        assertEquals(UrlSource.Manifest, fromManifest.source)
        assertNull(fromManifest.lan) // caps overrides both; lan is not mixed in
        val derived = DeviceUrls.resolve("nitro5", suffix, pc("nitro5"))
        assertEquals("https://nitro5.tailf52603.ts.net", derived.primary)
        assertEquals(UrlSource.Derived, derived.source)
        assertNull(derived.lan)
    }

    @Test fun lanIsTriedOnlyWhenListedAndAfterPrimary() {
        val withLan = DeviceUrls.resolve("nitro5", suffix, pc("nitro5", endpoints = mapOf("lan" to "http://192.168.1.18:8082")))
        assertEquals(listOf("https://nitro5.tailf52603.ts.net", "http://192.168.1.18:8082"), withLan.urls())
        val without = DeviceUrls.resolve("nitro5", suffix, pc("nitro5"))
        assertEquals(listOf("https://nitro5.tailf52603.ts.net"), without.urls())
        val none = DeviceUrls.resolve("nitro5", suffix)
        assertEquals(listOf("https://nitro5.tailf52603.ts.net"), none.urls())
    }

    @Test fun powerBoardIsNotAChatHost() {
        val board = RegDevice(
            "towerx-power", "TowerX power board", RegDevice.KIND_POWER_BOARD,
            liveliness = "devices/towerx-power",
            power = PowerDescriptor("towerx-power", "devices/towerx-power", listOf("status"), 0, emptyList(), 0),
        )
        assertFalse(DeviceUrls.isChatHost(board))
        assertTrue(DeviceUrls.isChatHost(pc("nitro5")))
        assertTrue(DeviceUrls.isChatHost(RegDevice("mac", "Mac", "unknown", endpoints = mapOf("tailnet" to "https://mac.example"))))
    }
}
