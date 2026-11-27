package com.vaulthome.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vaulthome.app.ask.CapsEvidence
import com.vaulthome.app.ask.CapsSource
import com.vaulthome.app.ask.CapsViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProvidersScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun advertisedRowShowsCapsIconAndClickOpens() {
        var clicks = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    ProviderCapsStatusRow(
                        detail = "available · skills VaultSkill · nCtx 16384 · caps Zenoh · just now",
                        advertised = true,
                        statusColor = Color(0xFF5CC489),
                        onShowCaps = { clicks++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Show caps JSON").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test fun nonAdvertisedRowHasNoCapsIcon() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    ProviderCapsStatusRow(
                        detail = "unknown · skills VaultSkill · caps devices.json",
                        advertised = false,
                        statusColor = Color(0xFF8A8A8A),
                        onShowCaps = { error("must not be called") },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Show caps JSON").assertDoesNotExist()
        compose.onNodeWithText("devices.json", substring = true).assertIsDisplayed()
    }

    @Test fun capsBodyShowsTheExactRawPayload() {
        val raw = """{"schemaVersion":2,"@graph":[{"@id":"towerx-qwen","hostedOn":{"deviceId":"towerx"}}]}"""
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    CapsJsonBody(
                        CapsEvidence(
                            device = "towerx",
                            rawJson = raw,
                            source = CapsSource.Zenoh,
                            atMs = 1_000,
                            zenohKey = "vault/caps/**",
                        ),
                        nowMs = 2_000,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("caps JSON").assertIsDisplayed()
        compose.onNodeWithText("schemaVersion", substring = true).assertIsDisplayed()
        compose.onNodeWithText("towerx-qwen", substring = true).assertIsDisplayed()
        compose.onNodeWithText("live Zenoh key vault/caps/**", substring = true).assertIsDisplayed()
    }
}

class CapsAdvertisementTest {
    @Test fun onlyZenohAndHttpCapsCountAsAdvertised() {
        assertTrue(CapsSource.Zenoh.isAdvertised)
        assertTrue(CapsSource.HttpCaps.isAdvertised)
        assertFalse(CapsSource.HealthProps.isAdvertised)
        assertFalse(CapsSource.Registry.isAdvertised)
    }

    @Test fun capsViewerShowOfTheSameDeviceIsANewRequest() {
        CapsViewer.hide()
        CapsViewer.show("towerx")
        val first = CapsViewer.open.value
        CapsViewer.show("towerx")
        val second = CapsViewer.open.value
        assertEquals("towerx", first?.device)
        assertEquals("towerx", second?.device)
        assertNotEquals(first?.id, second?.id)
        CapsViewer.hide()
        assertNull(CapsViewer.open.value)
    }
}
