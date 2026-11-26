package com.vaulthome.app.x4

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class X4BleTest {
    @Test fun uuidsMatchTheNote() {
        assertEquals(UUID.fromString("8a1f0001-4b6a-4c1e-9c3a-6d5e7f8091a2"), X4Ble.SERVICE)
        assertEquals(UUID.fromString("8a1f0002-4b6a-4c1e-9c3a-6d5e7f8091a2"), X4Ble.TEXT)
        assertEquals(UUID.fromString("8a1f0003-4b6a-4c1e-9c3a-6d5e7f8091a2"), X4Ble.COMMIT)
        assertEquals(UUID.fromString("8a1f0004-4b6a-4c1e-9c3a-6d5e7f8091a2"), X4Ble.STATUS)
        assertEquals(0x00.toByte(), X4Ble.COMMIT_CLEAR)
        assertEquals(0x01.toByte(), X4Ble.COMMIT_SHOW)
    }

    @Test fun payloadSizeUsesMtuMinusThreeAndFallsBackTo20() {
        assertEquals(20, X4Ble.payloadSize(23))
        assertEquals(20, X4Ble.payloadSize(0))
        assertEquals(514, X4Ble.payloadSize(517))
    }

    @Test fun chunksSplitAndPreserveBytes() {
        assertTrue(X4Ble.chunks(ByteArray(0), 20).isEmpty())
        val data = ByteArray(45) { it.toByte() }
        val parts = X4Ble.chunks(data, 20)
        assertEquals(3, parts.size)
        assertEquals(20, parts[0].size)
        assertEquals(20, parts[1].size)
        assertEquals(5, parts[2].size)
        assertArrayEquals(data, parts[0] + parts[1] + parts[2])
        assertEquals(1, X4Ble.chunks("hello".toByteArray(), 20).size)
    }

    @Test fun parseNotifyRequiresPages() {
        assertEquals(X4Ble.Notify.Pages(1), X4Ble.parseNotify("pages:1"))
        assertEquals(X4Ble.Notify.Pages(12), X4Ble.parseNotify(" pages: 12 \n"))
        assertEquals(X4Ble.Notify.Error("buffer overflow"), X4Ble.parseNotify("buffer overflow"))
        assertEquals(X4Ble.Notify.Error("empty status"), X4Ble.parseNotify("  "))
        assertEquals(1, X4Status("pages:1").pages)
    }

    @Test fun matchesNameOrHintAddress() {
        val s = X4Settings()
        assertTrue(X4Ble.matchesAdvert("VaultHome X4", "AA:BB:CC:DD:EE:FF", s))
        assertTrue(X4Ble.matchesAdvert(null, "C2:45:6B:2D:4E:FC", s))
        assertTrue(X4Ble.matchesAdvert("vaulthome x4", "00:00:00:00:00:00", s))
        assertFalse(X4Ble.matchesAdvert("Other", "80:45:6B:2D:4E:FC", s))
        assertFalse(X4Ble.matchesAdvert(null, "80:45:6B:2D:4E:FC", s))
    }
}
