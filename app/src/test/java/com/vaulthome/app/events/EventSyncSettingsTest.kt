package com.vaulthome.app.events

import com.vaulthome.events.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSyncSettingsTest {
    @Test
    fun defaultsAreClientToKnownTailnetHostsAndOff() {
        val s = EventSyncSettings()
        assertEquals(false, s.enabled)
        assertEquals("s24", s.device)
        assertEquals(
            listOf(
                "tcp/note4.tailf52603.ts.net:7447",
                "tcp/nitro5.tailf52603.ts.net:7447",
                "tcp/pawelk-latitude-5300.tailf52603.ts.net:7447",
                "tcp/desktop-sdf3lgo.tailf52603.ts.net:7447",
            ),
            s.connect,
        )
        // The box is outbound-only: never a connect endpoint.
        assertTrue(s.connect.none { it.contains("grokbot") })
        assertEquals(LinkMode.CLIENT, s.mode)
        assertTrue(s.notifySynced)
        assertTrue(s.useSharedEndpoints)
        assertEquals("pawels-s24-ultra.tailf52603.ts.net", s.tailnetName)
        assertNull(s.problem())
        // Client mode never listens and advertises no endpoint.
        assertEquals(emptyList<String>(), s.linkConfig().listen)
        assertNull(s.selfEntry().endpoint)
        assertEquals("\"client\"", s.linkConfig().inserts().toMap()["mode"])
        assertEquals(null, s.linkConfig().inserts().toMap()["listen/endpoints"])
    }

    @Test
    fun routerAdvertisesTailnetEndpointAndMergesShared() {
        val s = EventSyncSettings(mode = LinkMode.ROUTER, connect = listOf("tcp/note4.tailf52603.ts.net:7447"), listen = listOf("tcp/0.0.0.0:7448"))
        assertEquals("tcp/pawels-s24-ultra.tailf52603.ts.net:7448", s.selfEntry().endpoint)
        val shared = com.vaulthome.events.SharedEndpoints.parse(
            com.vaulthome.events.SharedEndpoints.mergeSelf(null, s.selfEntry(), 0L)!!,
        )
        val merged = s.linkConfig(shared).connect
        assertEquals("tcp/note4.tailf52603.ts.net:7447", merged.first())
        assertTrue(merged.contains("tcp/nitro5.tailf52603.ts.net:7447"))
        assertTrue(merged.none { it.contains("pawels-s24-ultra") || it.contains("grokbot") })
        assertEquals(listOf("tcp/note4.tailf52603.ts.net:7447"), s.copy(useSharedEndpoints = false).linkConfig(shared).connect)
        assertNotNull(s.copy(tailnetName = "bad name").problem())
    }

    @Test
    fun routerListensAndMayHaveNoConnect() {
        val s = EventSyncSettings(mode = LinkMode.ROUTER, connect = emptyList())
        assertNull(s.problem())
        assertEquals(listOf("tcp/0.0.0.0:7447"), s.linkConfig().listen)
        assertEquals("[\"tcp/0.0.0.0:7447\"]", s.linkConfig().inserts().toMap()["listen/endpoints"])
        assertEquals("\"router\"", s.linkConfig().inserts().toMap()["mode"])
    }

    @Test
    fun validation() {
        assertNotNull(EventSyncSettings(connect = emptyList()).problem())
        assertNotNull(EventSyncSettings(device = "my phone").problem())
        assertNotNull(EventSyncSettings(connect = listOf("note4:7447")).problem())
        assertNotNull(EventSyncSettings(connect = listOf("tcp/note4:99999")).problem())
        assertNull(EventSyncSettings(connect = listOf("tcp/100.101.102.103:7447", "tcp/[fd7a:115c::1]:7447")).problem())
        assertEquals(listOf("tcp/a:1", "tcp/b:2"), EventSyncSettings.parseList(" tcp/a:1, tcp/b:2\ntcp/a:1 "))
    }

    @Test
    fun parseListAcceptsCommaSpaceNewlineAndSemicolonAndDedupes() {
        val n910c = "tcp/n910c.tailf52603.ts.net:7447"
        val nitro = "tcp/nitro5.tailf52603.ts.net:7447"
        // The string Pawel tried in Settings (no space after the comma).
        assertEquals(listOf(n910c, nitro), EventSyncSettings.parseList("$n910c,$nitro"))
        assertEquals(listOf(n910c, nitro), EventSyncSettings.parseList("$n910c, $nitro"))
        assertEquals(listOf(n910c, nitro), EventSyncSettings.parseList("$n910c\n$nitro"))
        assertEquals(listOf(n910c, nitro), EventSyncSettings.parseList("$n910c; $nitro"))
        assertEquals(listOf(n910c, nitro), EventSyncSettings.parseList("  $n910c  $nitro  $n910c "))
        assertEquals(emptyList<String>(), EventSyncSettings.parseList("  , ; \n "))
    }

    @Test
    fun loadConnectMigratesASingleStoredValueAndASeparatedList() {
        val one = "tcp/note4.tailf52603.ts.net:7447"
        assertEquals(EventSyncSettings.DEFAULT_CONNECT_LIST, EventSyncSettings.loadConnect(null))
        assertEquals(listOf(one), EventSyncSettings.loadConnect(one))
        assertEquals(
            listOf("tcp/n910c.tailf52603.ts.net:7447", "tcp/nitro5.tailf52603.ts.net:7447"),
            EventSyncSettings.loadConnect("tcp/n910c.tailf52603.ts.net:7447,tcp/nitro5.tailf52603.ts.net:7447"),
        )
        assertEquals(emptyList<String>(), EventSyncSettings.loadConnect(""))
        assertEquals("tcp/a:1,tcp/b:2", EventSyncSettings.encodeList(listOf("tcp/a:1", "tcp/b:2")))
        assertEquals(listOf("tcp/a:1", "tcp/b:2"), EventSyncSettings.parseList(EventSyncSettings.encodeList(listOf("tcp/a:1", "tcp/b:2"))))
    }

    @Test
    fun perEntryValidationAndProblem() {
        val good = listOf("tcp/n910c.tailf52603.ts.net:7447", "tcp/nitro5.tailf52603.ts.net:7447")
        assertEquals(emptyList<Pair<Int, String>>(), EventSyncSettings.entryProblems(good))
        assertNull(EventSyncSettings(connect = good).problem())
        val mixed = listOf("tcp/n910c.tailf52603.ts.net:7447", "note4:7447", "tcp/nitro5:99999")
        val problems = EventSyncSettings.entryProblems(mixed)
        assertEquals(listOf(1, 2), problems.map { it.first })
        assertTrue(problems[0].second.contains("note4:7447"))
        assertTrue(problems[1].second.contains("65535"))
        assertNotNull(EventSyncSettings(connect = mixed).problem())
        assertNull(EventSyncSettings.endpointProblem("tls/box.tailf52603.ts.net:7447"))
        assertNull(EventSyncSettings.endpointProblem("udp/192.168.1.29:7447"))
        assertNull(EventSyncSettings.endpointProblem("quic/n910c.tailf52603.ts.net:7447"))
    }

    @Test
    fun zenohConfigContainsEveryRouterInOrder() {
        val routers = listOf("tcp/n910c.tailf52603.ts.net:7447", "tcp/nitro5.tailf52603.ts.net:7447")
        val inserts = EventSyncSettings(connect = routers).linkConfig().inserts().toMap()
        assertEquals("[\"tcp/n910c.tailf52603.ts.net:7447\",\"tcp/nitro5.tailf52603.ts.net:7447\"]", inserts["connect/endpoints"])
        // Shared endpoints.json is appended after the Settings list, and does not drop what the user typed.
        val shared = com.vaulthome.events.SharedEndpoints.parse(
            com.vaulthome.events.SharedEndpoints.mergeSelf(null, EventSyncSettings().selfEntry(), 0L)!!,
        )
        val merged = EventSyncSettings(connect = routers).linkConfig(shared).connect
        assertEquals(routers, merged.take(2))
        assertTrue(merged.contains("tcp/note4.tailf52603.ts.net:7447"))
        assertEquals(routers, EventSyncSettings(connect = routers, useSharedEndpoints = false).linkConfig(shared).connect)
    }
}
