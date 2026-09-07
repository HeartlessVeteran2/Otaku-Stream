package com.otakustream.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The first Compose tests in this project, on the component four screens depend on for their
// first-run flow.
//
// EmptyState is where someone lands when the app has nothing to show them, so its buttons are the
// only way out of a dead end — and a button that silently stops being drawn looks exactly like a
// screen that is supposed to be empty. That is the shape of the bug this component's newest
// parameter is here to fix, so it is worth a test that actually renders it.
//
// Robolectric rather than an emulator: CI here is JVM-only, and a Compose test that needed a device
// would be a test that never ran.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmptyStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the message and title are shown`() {
        compose.setContent {
            EmptyState(
                icon = Icons.Filled.Extension,
                title = "No sources yet",
                message = "Install an add-on or an extension.",
            )
        }

        compose.onNodeWithText("No sources yet").assertIsDisplayed()
        compose.onNodeWithText("Install an add-on or an extension.").assertIsDisplayed()
    }

    @Test
    fun `both actions render and each calls its own handler`() {
        var primary = 0
        var secondary = 0
        compose.setContent {
            EmptyState(
                icon = Icons.Filled.Extension,
                title = "No sources yet",
                message = "Two ecosystems, two directories.",
                actionLabel = "Browse add-ons",
                onAction = { primary++ },
                secondaryActionLabel = "Browse extensions",
                onSecondaryAction = { secondary++ },
            )
        }

        compose.onNodeWithText("Browse add-ons").performClick()
        compose.onNodeWithText("Browse extensions").performClick()

        // Crossed handlers would be invisible on screen and send the user to the wrong directory —
        // which, given the whole point is that the two ecosystems are easy to confuse, is the exact
        // mistake worth pinning.
        assertEquals(1, primary)
        assertEquals(1, secondary)
    }

    @Test
    fun `a label with no handler draws no button`() {
        compose.setContent {
            EmptyState(
                icon = Icons.Filled.Extension,
                title = "Nothing here",
                message = "And nowhere to go.",
                actionLabel = "Browse add-ons",
                secondaryActionLabel = "Browse extensions",
            )
        }

        // A button that cannot do anything is worse than no button: it reads as an offer and
        // answers a tap with silence.
        compose.onNodeWithText("Browse add-ons").assertDoesNotExist()
        compose.onNodeWithText("Browse extensions").assertDoesNotExist()
    }

    @Test
    fun `the secondary action is optional`() {
        var primary = 0
        compose.setContent {
            EmptyState(
                icon = Icons.Filled.Extension,
                title = "No matches",
                message = "Try a different title.",
                actionLabel = "Clear filters",
                onAction = { primary++ },
            )
        }

        compose.onNodeWithText("Clear filters").performClick()
        assertEquals(1, primary)
        // Adding the second slot must not have changed what the existing single-action callers get.
        compose.onNodeWithText("Browse extensions").assertDoesNotExist()
    }
}
