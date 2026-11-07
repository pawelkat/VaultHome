package com.vaulthome.app.x4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class X4PayloadTest {
    @Test fun noteIsPassedUnchanged() {
        val md = "---\ntags: [a]\n---\n# H\n\n- b\n"
        assertEquals(X4Payload.Check.Ok(md), X4Payload.forNote("Notes/H.md", md))
    }

    @Test fun vaultKeepPathsAreBlocked() {
        assertTrue(X4Payload.isVaultKeep("VaultKeep/secret.md"))
        assertTrue(X4Payload.isVaultKeep("/Archive/vaultkeep/x.md"))
        assertFalse(X4Payload.isVaultKeep("Notes/VaultKeeping.md"))
        assertTrue(X4Payload.forNote("VaultKeep/a.md", "# a") is X4Payload.Check.Refused)
    }

    @Test fun answersCitingVaultKeepAreBlocked() {
        assertTrue(X4Payload.mentionsVaultKeep("- [[VaultKeep/Bank]]"))
        assertTrue(X4Payload.mentionsVaultKeep("see [[VaultKeep]]"))
        assertFalse(X4Payload.mentionsVaultKeep("VaultKeeping notes"))
        assertTrue(X4Payload.forAnswer("**Q**\n\n- [[VaultKeep/x|x]]") is X4Payload.Check.Refused)
        assertTrue(X4Payload.forAnswer("**Q**\n\nAnswer") is X4Payload.Check.Ok)
    }

    @Test fun lengthLimitCountsCodePointsAndDoesNotTruncate() {
        val ok = "a".repeat(32_000)
        assertEquals(X4Payload.Check.Ok(ok), X4Payload.forNote("n.md", ok))
        val r = X4Payload.forNote("n.md", "a".repeat(32_001))
        assertTrue(r is X4Payload.Check.Refused && r.reason.contains("32,001"))
        // 32 000 emoji are 64 000 UTF-16 units but 32 000 characters on the device.
        assertTrue(X4Payload.forAnswer("😀".repeat(32_000)) is X4Payload.Check.Ok)
        assertTrue(X4Payload.forNote("n.md", "  \n") is X4Payload.Check.Refused)
    }

    @Test fun feedbackText() {
        assertEquals("Sent to X4: 1 page. The glass refreshes in a few seconds.", X4Text.sent(X4ShowResult("Queued 1 page(s)", 1, false, null)))
        assertTrue(X4Text.detect(X4Detect.NotFound("VaultHome X4", "not advertising")).startsWith("X4 not found"))
    }
}
