package com.vaulthome.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryHistoryTest {
    private fun rec(list: List<HistoryEntry>, text: String, mode: QueryMode, at: Long) =
        QueryHistory.record(list, text, mode, at)

    @Test fun newestFirstAndDedupeSameTextAndMode() {
        var h = emptyList<HistoryEntry>()
        h = rec(h, "vault sync", QueryMode.Search, 1)
        h = rec(h, "what did I do yesterday?", QueryMode.Ask, 2)
        h = rec(h, "vault sync", QueryMode.Search, 3)
        assertEquals(listOf("vault sync", "what did I do yesterday?"), h.map { it.text })
        assertEquals(listOf(QueryMode.Search, QueryMode.Ask), h.map { it.mode })
        assertEquals(3L, h.first().atMs)
    }

    @Test fun sameTextDifferentModeAreKept() {
        var h = emptyList<HistoryEntry>()
        h = rec(h, "TowerX", QueryMode.Search, 1)
        h = rec(h, "TowerX", QueryMode.Ask, 2)
        assertEquals(2, h.size)
        assertEquals(QueryMode.Ask, h[0].mode)
        assertEquals(QueryMode.Search, h[1].mode)
    }

    @Test fun dedupeIsCaseInsensitiveButKeepsTheNewText() {
        var h = rec(emptyList(), "Nitro", QueryMode.Search, 1)
        h = rec(h, "nitro", QueryMode.Search, 2)
        assertEquals(listOf(HistoryEntry("nitro", QueryMode.Search, 2)), h)
    }

    @Test fun blankIsIgnored() {
        assertTrue(rec(emptyList(), "  ", QueryMode.Search, 1).isEmpty())
    }

    @Test fun capDropsTheOldest() {
        var h = emptyList<HistoryEntry>()
        repeat(QueryHistory.CAP + 5) { i -> h = rec(h, "q$i", QueryMode.Search, i.toLong()) }
        assertEquals(QueryHistory.CAP, h.size)
        assertEquals("q${QueryHistory.CAP + 4}", h.first().text)
        assertEquals("q5", h.last().text)
    }

    @Test fun jsonRoundTrip() {
        val h = listOf(
            HistoryEntry("Ask me", QueryMode.Ask, 42),
            HistoryEntry("search", QueryMode.Search, 7),
        )
        assertEquals(h, QueryHistory.decode(QueryHistory.encode(h)))
        assertTrue(QueryHistory.decode(null).isEmpty())
        assertTrue(QueryHistory.decode("not json").isEmpty())
    }
}
