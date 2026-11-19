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
}
