package com.vaulthome.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Composes the rendered note view (as NoteScreen does) for vault-like notes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RenderedNoteTest {
    @get:Rule val compose = createComposeRule()

    private val samples = listOf(
        "---\ntitle: VaultHome\ntags:\n  - idea\n  - android\n---\n\n# VaultHome\n\n#idea #project\n\nCompanion for [[S24 Ultra]], see [[VaultHome provisioning|provisioning]].\n\n## Locked\n\n- **Client**: Compose\n- Model: `embeddinggemma`\n\n![[diagram.png]]\n\n> quote\n\n[URI](https://obsidian.md/help/uri)\n\n```\nobsidian://open?vault=Vault\n```\n",
        "# Journal\n\n1. first\n2. second\n\n---\n\n- [ ] open loop [[Ania]]\n",
        "---\ntags: [a]\nunterminated? no\n---\nplain",
        "",
    )

    @Test fun composesSamplesWithoutCrashingAndHidesFrontmatter() {
        val current = mutableStateOf(samples.first())
        val wikiTaps = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { RenderedNote(current.value, onLink = {}, onWikiLink = { wikiTaps += it }) }
            }
        }
        compose.waitForIdle()
        fun has(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue(has("VaultHome")); assertTrue(has("#android")); assertTrue(has("S24 Ultra")); assertTrue(has("provisioning"))
        assertTrue("frontmatter hidden", !has("title: VaultHome"))
        assertTrue("wikilink brackets hidden", !has("[["))
        for (s in samples.drop(1)) {
            current.value = s
            compose.waitForIdle()
            if (s.endsWith("plain")) assertTrue(has("plain") && !has("tags:"))
        }
        assertEquals(emptyList<String>(), wikiTaps)
    }
}
