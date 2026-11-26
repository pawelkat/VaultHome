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
        assertTrue(cfg.routers.none { it.id == "latitude" || it.id == "desktop-sdf3lgo" })
        assertEquals(listOf("note4c", "nitro5", "box"), cfg.routers.map { it.id })
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
                "tcp/n910c.tailf52603.ts.net:7447",
                "tcp/nitro5.tailf52603.ts.net:7447",
                "tcp/192.168.1.10:7447",
            ),
            list,
        )
        assertTrue(list.none { it.contains("grokbot") || it.contains("pawels-s24-ultra") || it.contains("should-not-dial") })
        assertEquals(listOf("tcp/a:1"), SharedEndpoints.connectList(listOf("tcp/a:1"), null, s24))
    }

    @Test
    fun connectListDedupesVaultExtrasAndAliases() {
        val self = DeviceEntry("s24", "VaultHome", LinkMode.CLIENT, tailnetName = "pawels-s24-ultra.tailf52603.ts.net")
        val json = """
            {"schemaVersion":1,
             "routers":[
               {"id":"note4c","endpoint":"tcp/n910c.tailf52603.ts.net:7447","tailnetName":"n910c.tailf52603.ts.net","dialable":true,
                "note":"Always-on Zenoh router on note4c. MagicDNS is n910c. IPv4 100.79.23.65."},
               {"id":"nitro5","endpoint":"tcp/nitro5.tailf52603.ts.net:7447","tailnetName":"nitro5.tailf52603.ts.net","dialable":true,
                "note":"Nitro WSL. IPv4 100.79.193.105."},
               {"id":"latitude","endpoint":"tcp/pawelk-latitude-5300.tailf52603.ts.net:7447","tailnetName":"pawelk-latitude-5300.tailf52603.ts.net","dialable":true},
               {"id":"desktop-sdf3lgo","endpoint":"tcp/desktop-sdf3lgo.tailf52603.ts.net:7447","tailnetName":"desktop-sdf3lgo.tailf52603.ts.net","dialable":true},
               {"id":"box","tailnetName":"grokbot-user-155710816.tailf52603.ts.net","dialable":false}
             ],
             "devices":{"s24":{"app":"VaultHome","mode":"client","listen":[],"tailnetName":"pawels-s24-ultra.tailf52603.ts.net"}}}
        """.trimIndent().toByteArray()
        val cfg = SharedEndpoints.parse(json)!!
        val local = listOf("tcp/n910c.tailf52603.ts.net:7447", "tcp/nitro5.tailf52603.ts.net:7447")
        val targets = SharedEndpoints.dialTargets(local, cfg, self)
        assertEquals(
            listOf(
                "tcp/n910c.tailf52603.ts.net:7447",
                "tcp/nitro5.tailf52603.ts.net:7447",
                "tcp/pawelk-latitude-5300.tailf52603.ts.net:7447",
                "tcp/desktop-sdf3lgo.tailf52603.ts.net:7447",
            ),
            targets.map { it.endpoint },
        )
        assertEquals(
            listOf(SharedEndpoints.DialSource.SETTINGS, SharedEndpoints.DialSource.SETTINGS, SharedEndpoints.DialSource.VAULT, SharedEndpoints.DialSource.VAULT),
            targets.map { it.source },
        )
        assertEquals(listOf(null, null, "latitude", "desktop-sdf3lgo"), targets.map { it.id })
        // Same two Settings locators under another scheme/host spelling, plus the tailnet IPv4 of n910c.
        val aliases = SharedEndpoints.dialTargets(
            listOf("TCP/N910C.tailf52603.ts.net.:7447", "tcp/100.79.23.65:7447", "tcp/nitro5.tailf52603.ts.net:7447"),
            cfg,
            self,
        )
        assertEquals(
            listOf(
                "tcp/n910c.tailf52603.ts.net:7447",
                "tcp/nitro5.tailf52603.ts.net:7447",
                "tcp/pawelk-latitude-5300.tailf52603.ts.net:7447",
                "tcp/desktop-sdf3lgo.tailf52603.ts.net:7447",
            ),
            aliases.map { it.endpoint },
        )
        assertEquals(2, aliases.count { it.source == SharedEndpoints.DialSource.VAULT })
        assertEquals(local, SharedEndpoints.connectList(local, cfg, self).take(2))
        assertTrue(SharedEndpoints.connectList(local, cfg, self).none { it.contains("grokbot") || it.contains("100.79.") })
    }

    @Test
    fun normalizeEndpointStripsCaseDotAndParams() {
        assertEquals(
            "tcp/n910c.tailf52603.ts.net:7447",
            SharedEndpoints.normalizeEndpoint(" TCP/N910C.tailf52603.ts.net.:7447#iface=tailscale0 "),
        )
        assertTrue(SharedEndpoints.isEndpoint("tcp/n910c.tailf52603.ts.net:7447"))
        assertTrue(SharedEndpoints.isEndpoint("TCP/n910c.tailf52603.ts.net:7447"))
    }
}
