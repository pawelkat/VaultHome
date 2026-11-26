package com.vaulthome.app.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskCacheTest {
    private val cites = listOf(Citation("Daily/2026-10-31.md", "", "walked"))
    private val answered = AskUiState(
        question = "what I did yesterday?", asked = "what I did yesterday?",
        outcome = AskOutcome.Nitro("You walked.", "qwen3", cites, null),
    )

    @Test fun openSourceHidesButKeepsAnswerAndBackRestoresIt() {
        assertTrue(AskCache.visible(answered, askMode = true))
        val opened = AskCache.openSource(answered, "Daily/2026-10-31.md")
        assertFalse(AskCache.visible(opened, askMode = true))
        assertEquals(answered.outcome, opened.outcome)
        assertTrue(AskCache.canReturnFrom(opened, "Daily/2026-10-31.md"))

        val (back, restored) = AskCache.returnFrom(opened, "Daily/2026-10-31.md")
        assertTrue(restored)
        assertTrue(AskCache.visible(back, askMode = true))
        assertEquals(answered.asked, back.asked)
        assertEquals(answered.question, back.question)
        assertEquals(answered.outcome, back.outcome)
        assertNull(back.returnTo)
    }

    @Test fun backFromAWikilinkInsideTheSourceDoesNotRestoreYet() {
        val opened = AskCache.openSource(answered, "Daily/2026-10-31.md")
        val (still, restored) = AskCache.returnFrom(opened, "People/Anna.md")
        assertFalse(restored)
        assertFalse(AskCache.visible(still, askMode = true))
        assertTrue(AskCache.returnFrom(still, "Daily/2026-10-31.md").second)
    }

    @Test fun noAnswerNothingToRestore() {
        val empty = AskUiState()
        assertEquals(empty, AskCache.openSource(empty, "x.md"))
        assertFalse(AskCache.returnFrom(empty, "x.md").second)
    }

    @Test fun modeSwitchShowsCachedAnswerAgainInAsk() {
        val opened = AskCache.openSource(answered, "Daily/2026-10-31.md")
        val switched = AskCache.modeChanged(opened)
        assertFalse(AskCache.visible(switched, askMode = false))
        assertTrue(AskCache.visible(switched, askMode = true))
    }
}
