package com.vaulthome.app.x4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class X4SettingsTest {
    @Test fun defaultsAreBleIdentity() {
        val s = X4Settings()
        assertEquals("VaultHome X4", s.deviceName)
        assertEquals("C2:45:6B:2D:4E:FC", s.addressHint)
        assertEquals("VaultHome X4 · C2:45:6B:2D:4E:FC", s.label)
    }

    @Test fun normalize() {
        assertEquals("VaultHome X4", X4Settings.normalizeName("  "))
        assertEquals("My X4", X4Settings.normalizeName(" My X4 "))
        assertEquals("C2:45:6B:2D:4E:FC", X4Settings.normalizeAddress(""))
        assertEquals("C2:45:6B:2D:4E:FC", X4Settings.normalizeAddress(" c2-45-6b-2d-4e-fc "))
        assertEquals("AA:BB:CC:DD:EE:FF", X4Settings.normalizeAddress("aa:bb:cc:dd:ee:ff"))
    }

    @Test fun addressProblems() {
        assertNull(X4Settings.addressProblem("C2:45:6B:2D:4E:FC"))
        assertNotNull(X4Settings.addressProblem("not-a-mac"))
        assertNotNull(X4Settings.addressProblem("80:45:6B"))
        assertTrue(X4Ble.isValidAddress("C2:45:6B:2D:4E:FC"))
        assertFalse(X4Ble.isValidAddress("VaultHome X4"))
    }
}
