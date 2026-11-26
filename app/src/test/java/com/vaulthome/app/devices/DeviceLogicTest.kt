package com.vaulthome.app.devices

import com.vaulthome.app.ask.HealthSample
import com.vaulthome.app.towerx.PowerAction
import com.vaulthome.app.towerx.TowerXState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLogicTest {
    private val now = 1_000_000L
    private val ok = HealthSample(true, "up", now - 5_000)
    private val failed = HealthSample(false, "refused", now - 5_000)
    private val stale = HealthSample(true, "up", now - DeviceLive.HEALTH_TTL_MS - 1)
    private val bundled = DeviceRegistryJson.bundled()
    private val towerx = bundled.byId("towerx")!!
    private val board = bundled.byId("towerx-power")!!
    private val power = towerx.power!!

    @Test fun tokenDecidesBeforeHealth() {
        assertEquals(Live.Online, DeviceLive.of(true, failed, now))
        assertEquals(Live.Offline, DeviceLive.of(false, ok, now))
    }

    @Test fun healthDecidesWithoutToken() {
        assertEquals(Live.Online, DeviceLive.of(null, ok, now))
        assertEquals(Live.Offline, DeviceLive.of(null, failed, now))
        assertEquals(Live.Unknown, DeviceLive.of(null, stale, now))
        assertEquals(Live.Unknown, DeviceLive.of(null, null, now))
    }

    @Test fun aliveSourcePerDevice() {
        val tx = TowerXState(espAlive = true, agentAlive = false)
        assertEquals(true, DeviceLive.alive(board, tx) { null })
        assertEquals(true, DeviceLive.alive(towerx, tx) { if (it == "towerx") true else null })
        assertNull(DeviceLive.alive(bundled.byId("nitro5")!!, tx) { null })
        assertNull(DeviceLive.alive(board, TowerXState()) { true })
        val other = board.copy(liveliness = "devices/other")
        assertNull(DeviceLive.alive(other, tx) { true })
    }

    @Test fun powerButtonsFollowTheDescriptor() {
        assertEquals(listOf(PowerAction.Status, PowerAction.On, PowerAction.Off), PowerUi.buttons(power))
        assertEquals(listOf(PowerAction.On), PowerUi.buttons(power.copy(actions = listOf("on"))))
        assertTrue(PowerUi.needsConfirm(power, PowerAction.Off))
        assertFalse(PowerUi.needsConfirm(power, PowerAction.On))
        assertFalse(PowerUi.needsConfirm(power, PowerAction.Status))
    }

    @Test fun powerGreyedUnlessBoardOnlineAndIdle() {
        assertTrue(PowerUi.enabled(power, TowerXState(espAlive = true)))
        assertFalse(PowerUi.enabled(power, TowerXState(espAlive = false)))
        assertFalse(PowerUi.enabled(power, TowerXState(espAlive = null)))
        assertFalse(PowerUi.enabled(power, TowerXState(espAlive = true, busy = true)))
        assertFalse(PowerUi.enabled(power.copy(key = "devices/unwatched"), TowerXState(espAlive = true)))
    }

    @Test fun dialogAndCooldownWording() {
        assertEquals("Force off TowerX?", PowerUi.confirmTitle("TowerX", PowerAction.Off))
        assertEquals("Holds the power button for 6 s. Unsaved work on TowerX is lost.", PowerUi.confirmText("TowerX", power, PowerAction.Off))
        assertEquals("After a pulse the board ignores another for 45 s.", PowerUi.cooldownNote(power))
        assertNull(PowerUi.cooldownNote(power.copy(cooldownS = 0)))
    }

    @Test fun lastSeenWording() {
        assertEquals("now", LastSeen.text(null, now, Live.Online))
        assertEquals("never seen", LastSeen.text(null, now, Live.Offline))
        assertEquals("just now", LastSeen.text(now - 30_000, now, Live.Offline))
        assertEquals("5 min ago", LastSeen.text(now - 300_000, now, Live.Unknown))
        assertEquals("3 h ago", LastSeen.text(now - 3 * 3_600_000, now, Live.Offline))
        assertEquals("2 d ago", LastSeen.text(now - 2 * 86_400_000, now, Live.Offline))
    }
}
