package com.vaulthome.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteStackTest {
    @Test fun pushPop() {
        var s = listOf("2026-10-12.md")
        s = NoteStack.push(s, "QiMap.md"); s = NoteStack.push(s, "QiMap.md"); s = NoteStack.push(s, "note4c.md")
        assertEquals(listOf("2026-10-12.md", "QiMap.md", "note4c.md"), s)
        s = NoteStack.pop(NoteStack.pop(s))
        assertEquals(listOf("2026-10-12.md"), s)
        assertEquals(s, NoteStack.pop(s))
    }
}
