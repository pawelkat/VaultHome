package com.vaulthome.app.events

import com.vaulthome.events.EngineStatus
import com.vaulthome.events.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterListStatusTest {
    private val n910c = "tcp/n910c.tailf52603.ts.net:7447"
    private val nitro = "tcp/nitro5.tailf52603.ts.net:7447"

    @Test
    fun parseHostPortAndShortName() {
        val a = RouterListStatus.parse(n910c)!!
        assertEquals("tcp", a.proto)
        assertEquals("n910c.tailf52603.ts.net", a.host)
        assertEquals(7447, a.port)
        assertEquals("n910c", RouterListStatus.shortName(a.host))
        assertEquals("nitro5", RouterListStatus.shortName("nitro5.tailf52603.ts.net"))
        assertEquals("100.79.23.65", RouterListStatus.shortName("100.79.23.65"))
        val v6 = RouterListStatus.parse("tcp/[fd7a:115c::1]:7447")!!
        assertEquals("[fd7a:115c::1]", v6.host)
        assertEquals(7447, v6.port)
        assertNull(RouterListStatus.parse("note4:7447"))
        assertNull(RouterListStatus.parse("tcp/n910c.tailf52603.ts.net:99999"))
    }

    @Test
    fun rowLabel() {
        assertEquals("checking…", RouterListStatus.rowLabel(null))
        assertEquals("checking…", RouterListStatus.rowLabel(RouterListStatus.Reach.CHECKING))
        assertEquals("reachable", RouterListStatus.rowLabel(RouterListStatus.Reach.REACHABLE))
        assertEquals("unreachable", RouterListStatus.rowLabel(RouterListStatus.Reach.UNREACHABLE))
    }

    @Test
    fun linkedPhraseExplainsClientUsesOne() {
        assertEquals(
            "1 of 2 routers linked (client uses one at a time)",
            RouterListStatus.linkedPhrase(LinkMode.CLIENT, 1, 2),
        )
        assertEquals("1 of 1 router linked", RouterListStatus.linkedPhrase(LinkMode.CLIENT, 1, 1))
        assertEquals("2 of 2 routers linked", RouterListStatus.linkedPhrase(LinkMode.PEER, 2, 2))
        assertEquals("0 routers linked", RouterListStatus.linkedPhrase(LinkMode.CLIENT, 0, 0))
        assertEquals(
            "1 of 4 routers linked (2 from vault; client uses one at a time)",
            RouterListStatus.linkedPhrase(LinkMode.CLIENT, 1, 4, fromVault = 2),
        )
    }

    @Test
    fun statusLineUsesLinkedCountNotTheFullDialList() {
        val st = EngineStatus(
            phase = EngineStatus.Phase.CONNECTED,
            mode = LinkMode.CLIENT,
            routers = 1,
            peers = 0,
            tracked = 1044,
            connect = listOf(n910c, nitro),
        )
        val line = RouterListStatus.statusLine(true, EventSyncState(running = true, status = st, endpoint = "$n910c, $nitro"))
        assertTrue(line, line.startsWith("Status: connected · 1 of 2 routers linked (client uses one at a time)"))
        assertTrue(line, line.contains("1044 notes tracked"))
        // The old line dumped every configured locator in parentheses, which looked like
        // both were the session router and hid the "1 routers" count.
        assertFalse(line, line.contains("n910c.tailf52603.ts.net"))
        assertFalse(line, line.contains("(") && line.contains("tcp/"))
        assertEquals("Status: off", RouterListStatus.statusLine(false, EventSyncState()))
        assertEquals("Status: not running", RouterListStatus.statusLine(true, EventSyncState(running = false)))
        val withVault = st.copy(connect = listOf(n910c, nitro, "tcp/pawelk-latitude-5300.tailf52603.ts.net:7447", "tcp/desktop-sdf3lgo.tailf52603.ts.net:7447"))
        val line4 = RouterListStatus.statusLine(true, EventSyncState(running = true, status = withVault, fromVault = 2))
        assertTrue(line4, line4.contains("1 of 4 routers linked (2 from vault; client uses one at a time)"))
    }

    @Test
    fun notificationNamesHowManyAreLinked() {
        val st = EngineStatus(
            phase = EngineStatus.Phase.CONNECTED,
            mode = LinkMode.CLIENT,
            routers = 1,
            tracked = 1044,
            connect = listOf(n910c, nitro),
        )
        assertEquals(
            "Client · 1 of 2 routers linked (client uses one at a time), 0 peers · 1044 notes",
            RouterListStatus.notificationSummary(LinkMode.CLIENT, st),
        )
    }
}
