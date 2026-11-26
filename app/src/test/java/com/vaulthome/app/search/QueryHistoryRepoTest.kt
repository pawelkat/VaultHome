package com.vaulthome.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class QueryHistoryRepoTest {
    private class MemCache(var raw: String? = null) : HistoryCache {
        override fun load() = raw
        override fun save(raw: String) { this.raw = raw }
    }

    private class MemFile(var bytes: ByteArray? = null, var failWrites: Boolean = false) : HistoryFile {
        var writes = 0
        override fun read() = bytes
        override fun write(bytes: ByteArray) {
            if (failWrites) throw IOException("no write access")
            writes++
            this.bytes = bytes
        }
        fun doc() = QueryHistory.decodeDoc(bytes?.toString(Charsets.UTF_8))
    }

    private class Clock(var t: Long) : () -> Long { override fun invoke() = t }

    private fun e(text: String, mode: QueryMode, at: Long) = HistoryEntry(text, mode, at)

    @Test fun pathAndFormat() {
        assertEquals("app_settings/VaultHome/history.json", QueryHistory.VAULT_PATH)
        val json = QueryHistory.encode(HistoryDoc(listOf(e("a b", QueryMode.Ask, 5)), clearedAtMs = 3))
        val o = org.json.JSONObject(json)
        assertEquals(1, o.getInt("version"))
        assertEquals(3L, o.getLong("cleared_at"))
        assertEquals("a b", o.getJSONArray("entries").getJSONObject(0).getString("q"))
        assertEquals("Ask", o.getJSONArray("entries").getJSONObject(0).getString("mode"))
    }

    @Test fun firstSyncMigratesTheOldPrefsArrayIntoTheVaultFile() {
        // Pre-vault builds kept a plain JSON array in SharedPreferences.
        val old = QueryHistory.encode(listOf(e("old ask", QueryMode.Ask, 10), e("old search", QueryMode.Search, 5)))
            .let { org.json.JSONObject(it).getJSONArray("entries").toString() }
        val cache = MemCache(old)
        val file = MemFile()
        val repo = QueryHistoryRepo(cache, file) { 100 }
        assertEquals(listOf("old ask", "old search"), repo.current().entries.map { it.text })
        repo.sync()
        assertEquals(listOf("old ask", "old search"), file.doc().entries.map { it.text })
        assertEquals(1, file.writes)
        repo.sync()
        assertEquals("a second sync with nothing new does not rewrite the file", 1, file.writes)
    }

    @Test fun twoDevicesEditingConcurrentlyConverge() {
        val file = MemFile()
        val ca = Clock(100); val cb = Clock(100)
        val a = QueryHistoryRepo(MemCache(), file, ca)
        val b = QueryHistoryRepo(MemCache(), file, cb)
        a.record("from phone", QueryMode.Search)
        cb.t = 102
        b.record("from desktop", QueryMode.Ask)
        a.sync()   // file: phone
        b.sync()   // merges phone into desktop, file: both
        a.sync()   // phone picks up desktop
        val expect = listOf("from desktop", "from phone")
        assertEquals(expect, a.current().entries.map { it.text })
        assertEquals(expect, b.current().entries.map { it.text })
        assertEquals(expect, file.doc().entries.map { it.text })
    }

    @Test fun sameQueryOnTwoDevicesIsDeduplicatedKeepingTheLatest() {
        val file = MemFile()
        val a = QueryHistoryRepo(MemCache(), file) { 10 }
        val b = QueryHistoryRepo(MemCache(), file) { 20 }
        a.record("TowerX", QueryMode.Search); a.sync()
        b.record("towerx", QueryMode.Search); b.sync()
        assertEquals(listOf(e("towerx", QueryMode.Search, 20)), file.doc().entries)
    }

    @Test fun mergeIsCappedAndNewestFirst() {
        val a = HistoryDoc((0 until 40).map { e("a$it", QueryMode.Search, it.toLong()) }.reversed())
        val b = HistoryDoc((0 until 40).map { e("b$it", QueryMode.Ask, 100L + it) }.reversed())
        val m = QueryHistory.merge(a, b)
        assertEquals(QueryHistory.CAP, m.entries.size)
        assertEquals("b39", m.entries.first().text)
        assertTrue(m.entries.zipWithNext().all { (x, y) -> x.atMs >= y.atMs })
        assertEquals(m, QueryHistory.merge(b, a))
    }

    @Test fun clearOnOneDeviceIsNotUndoneByAnOlderCopy() {
        val file = MemFile()
        val ca = Clock(100); val cb = Clock(100)
        val a = QueryHistoryRepo(MemCache(), file, ca)
        val b = QueryHistoryRepo(MemCache(), file, cb)
        a.record("secret", QueryMode.Ask); a.sync(); b.sync()
        ca.t = 200; a.clear(); a.sync()
        cb.t = 150; b.record("kept?", QueryMode.Search) // recorded before the clear on the other device
        b.sync()
        assertTrue(b.current().entries.isEmpty())
        assertTrue(file.doc().entries.isEmpty())
        cb.t = 300; b.record("after clear", QueryMode.Search); b.sync(); a.sync()
        assertEquals(listOf("after clear"), a.current().entries.map { it.text })
    }

    @Test fun unwritableVaultKeepsTheLocalCopyAndReportsIt() {
        val file = MemFile(failWrites = true)
        val repo = QueryHistoryRepo(MemCache(), file) { 5 }
        repo.record("offline", QueryMode.Search)
        repo.sync()
        assertEquals(listOf("offline"), repo.current().entries.map { it.text })
        assertNotNull(repo.lastError)
        file.failWrites = false
        repo.sync()
        assertNull(repo.lastError)
        assertEquals(listOf("offline"), file.doc().entries.map { it.text })
    }

    @Test fun garbageFileIsReplacedByTheMergedHistory() {
        val file = MemFile("not json".toByteArray())
        val repo = QueryHistoryRepo(MemCache(), file) { 5 }
        repo.record("x", QueryMode.Ask)
        repo.sync()
        assertEquals(listOf("x"), file.doc().entries.map { it.text })
    }

    @Test fun noVaultFileMeansCacheOnly() {
        val cache = MemCache()
        val repo = QueryHistoryRepo(cache, null) { 5 }
        repo.record("local", QueryMode.Search)
        repo.sync()
        assertEquals(listOf("local"), QueryHistory.decode(cache.raw).map { it.text })
    }
}
