package com.vaulthome.app.towerx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TowerXRulesTest {
    @Test fun availability() {
        assertTrue(TowerXRules.askAvailable(true, true))
        assertTrue(TowerXRules.askAvailable(null, true))
        assertFalse(TowerXRules.askAvailable(false, true))
        assertFalse(TowerXRules.askAvailable(true, false))
        assertFalse(TowerXRules.askAvailable(true, null))
    }

    @Test fun keys() {
        assertEquals("devices/towerx-power/call/power_on", TowerXKeys.call(PowerAction.On.tool))
        assertEquals("devices/towerx-power/call/power_off", TowerXKeys.call(PowerAction.Off.tool))
        assertEquals(listOf("devices/towerx-power", "vault/presence/towerx"), TowerXKeys.WATCH)
    }

    @Test fun refuse() {
        assertNotNull(TowerXRules.refuse(PowerAction.On, true, connected = false, confirmed = false))
        assertNotNull(TowerXRules.refuse(PowerAction.On, null, connected = true, confirmed = false))
        assertNotNull(TowerXRules.refuse(PowerAction.Off, true, connected = true, confirmed = false))
        assertNull(TowerXRules.refuse(PowerAction.Off, true, connected = true, confirmed = true))
        assertNull(TowerXRules.refuse(PowerAction.On, true, connected = true, confirmed = false))
    }

    @Test fun replies() {
        assertEquals(
            "power GPIO27 HIGH, reset GPIO26 HIGH, cooldown 0 ms, ip 192.168.1.30",
            TowerXRules.parseReply("""{"ok":true,"result":{"text":"power GPIO27 HIGH, reset GPIO26 HIGH, cooldown 0 ms, ip 192.168.1.30"}}""").getOrThrow(),
        )
        assertEquals("cooldown", TowerXRules.parseReply("""{"ok":false,"error":"cooldown"}""").exceptionOrNull()?.message)
        assertTrue(TowerXRules.parseReply(null).isFailure)
        assertTrue(TowerXRules.parseReply("nope").isFailure)
    }
}
