package com.vaulthome.app.events

import com.vaulthome.events.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EventSyncSettingsTest {
    @Test
    fun defaultsAreClientToNote4AndOff() {
        val s = EventSyncSettings()
        assertEquals(false, s.enabled)
        assertEquals("s24", s.device)
        assertEquals(listOf("tcp/note4.tailf52603.ts.net:7447"), s.connect)
        assertEquals(LinkMode.CLIENT, s.mode)
        assertNull(s.problem())
        // Client mode never listens.
        assertEquals(emptyList<String>(), s.linkConfig().listen)
        assertEquals(listOf("mode" to "\"client\"", "connect/endpoints" to "[\"tcp/note4.tailf52603.ts.net:7447\"]", "scouting/multicast/enabled" to "false"), s.linkConfig().inserts())
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
