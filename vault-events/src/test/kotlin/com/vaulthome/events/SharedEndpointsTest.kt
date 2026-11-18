package com.vaulthome.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SharedEndpointsTest {
    private val s24 = DeviceEntry(
        "s24", "VaultHome", LinkMode.ROUTER, listOf("tcp/0.0.0.0:7447"),
        endpoint = "tcp/pawels-s24-ultra.tailf52603.ts.net:7447", tailnetName = "pawels-s24-ultra.tailf52603.ts.net",
    )
    private val t0 = Instant.parse("2026-11-18T17:00:00Z").toEpochMilli()

    @Test
    fun seedsWhenMissing() {
        val out = SharedEndpoints.mergeSelf(null, s24, t0)!!
        val cfg = SharedEndpoints.parse(out)!!
        assertEquals(SharedEndpoints.DEFAULT_ROUTERS, cfg.routers)
        assertEquals(s24.copy(lastSeen = "2026-11-18T17:00:00Z"), cfg.devices["s24"])
        val box = cfg.routers.single { it.id == "box" }
        assertEquals(false, box.dialable)
        assertNull(box.endpoint)
        assertTrue(String(out).contains("\"schemaVersion\": 1"))
    }

    @Test
    fun mergeKeepsOtherDevicesRoutersAndUnknownKeys() {
        val existing = """
            {"schemaVersion":1,"custom":{"keep":true},
             "routers":[{"id":"note4","endpoint":"tcp/note4.tailf52603.ts.net:7447","dialable":true}],
             "devices":{"latitude":{"app":"vault-events","mode":"router","listen":["tcp/0.0.0.0:7447"],"endpoint":"tcp/pawelk-latitude-5300.tailf52603.ts.net:7447","lastSeen":"2026-11-17T08:00:00Z","extra":1},
                        "s24":{"app":"VaultHome","mode":"client","listen":[],"lastSeen":"2026-11-10T00:00:00Z"}}}
        """.trimIndent().toByteArray()
        val out = String(SharedEndpoints.mergeSelf(existing, s24, t0)!!)
        val cfg = SharedEndpoints.parse(out.toByteArray())!!
        assertEquals(1, cfg.routers.size)
        assertEquals("2026-11-17T08:00:00Z", cfg.devices["latitude"]!!.lastSeen)
        assertEquals(LinkMode.ROUTER, cfg.devices["s24"]!!.mode)
        assertTrue(out.contains("\"extra\": 1") && out.contains("\"keep\": true"))
    }

    @Test
    fun noRewriteWhenUnchangedAndRecent() {
        val first = SharedEndpoints.mergeSelf(null, s24, t0)!!
        assertNull(SharedEndpoints.mergeSelf(first, s24, t0 + 60_000))
        // lastSeen refresh after the window.
        assertNotNull(SharedEndpoints.mergeSelf(first, s24, t0 + SharedEndpoints.LAST_SEEN_REFRESH_MS))
        // Any field change writes immediately.
        assertNotNull(SharedEndpoints.mergeSelf(first, s24.copy(mode = LinkMode.PEER), t0 + 60_000))
        // Broken JSON is never clobbered.
        assertNull(SharedEndpoints.mergeSelf("{not json".toByteArray(), s24, t0))
        assertNull(SharedEndpoints.parse("[1,2]".toByteArray()))
    }

    @Test
    fun connectListMergesLocalRoutersAndOtherDevicesButNotSelfOrBox() {
        val seeded = SharedEndpoints.mergeSelf(null, s24, t0)!!
        val withLat = SharedEndpoints.mergeSelf(
            seeded,
            DeviceEntry("latitude", "vault-events", LinkMode.ROUTER, listOf("tcp/0.0.0.0:7447"), endpoint = "tcp/192.168.1.10:7447"),
            t0,
        )!!
        val withClient = SharedEndpoints.mergeSelf(withLat, DeviceEntry("nitro-cli", "vault-events", LinkMode.CLIENT, endpoint = "tcp/should-not-dial:7447"), t0)!!
        val cfg = SharedEndpoints.parse(withClient)!!
        val list = SharedEndpoints.connectList(listOf("tcp/note4.tailf52603.ts.net:7447", "bogus"), cfg, s24)
        assertEquals(
            listOf(
                "tcp/note4.tailf52603.ts.net:7447",
                "tcp/nitro5.tailf52603.ts.net:7447",
                "tcp/pawelk-latitude-5300.tailf52603.ts.net:7447",
                "tcp/desktop-sdf3lgo.tailf52603.ts.net:7447",
                "tcp/192.168.1.10:7447",
            ),
            list,
        )
        assertTrue(list.none { it.contains("grokbot") || it.contains("pawels-s24-ultra") || it.contains("should-not-dial") })
        assertEquals(listOf("tcp/a:1"), SharedEndpoints.connectList(listOf("tcp/a:1"), null, s24))
    }
}
