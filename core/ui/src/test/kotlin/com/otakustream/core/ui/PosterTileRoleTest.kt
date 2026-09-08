package com.otakustream.core.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// PosterTile is the most-repeated tappable thing in the app — every rail, every grid — so what it
// announces to a screen reader it announces hundreds of times.
//
// Role is the part that has no visible symptom. A tile without one is read as a plain container:
// TalkBack says the title and stops, giving no indication the thing can be activated at all, and
// nothing on screen looks any different. That is exactly the kind of regression that survives a
// code review and a manual pass, which is why it is asserted here.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PosterTileRoleTest {

    @get:Rule
    val compose = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun `a tappable tile is announced as a button`() {
        compose.setContent {
            PosterTile(title = "Frieren", coverUrl = null, onClick = {})
        }

        compose.onNodeWithText("Frieren").assert(hasRole(Role.Button))
    }

    // The other half of the same contract, and the reason onClick is nullable rather than defaulting
    // to an empty lambda: a read-only tile must not be announced as something that can be activated.
    // The Stremio account library is read-only by design and used to claim otherwise.
    @Test
    fun `a tile with no click handler is not announced as a button`() {
        compose.setContent {
            PosterTile(title = "Frieren", coverUrl = null, onClick = null)
        }

        compose.onNodeWithText("Frieren").assert(
            SemanticsMatcher("has no Role") { node ->
                SemanticsProperties.Role !in node.config
            },
        )
    }

    @Test
    fun `tapping a tile calls its handler once`() {
        var taps = 0
        compose.setContent {
            PosterTile(title = "Frieren", coverUrl = null, onClick = { taps++ })
        }

        compose.onNodeWithText("Frieren").performClick()

        assertEquals(1, taps)
    }

    // The tile is one node, not three. Without mergeDescendants a rail entry is the image, the title
    // and the subtitle separately, so TalkBack reads the title twice and then reads "Ep 5/12" as an
    // item with nothing saying which show it belongs to.
    //
    // Asserted through the role: on the merged tree the subtitle's text belongs to the tile's own
    // node, which carries Role.Button. Lose the merge and the subtitle becomes a bare Text node
    // with no role, and this fails.
    @Test
    fun `the subtitle belongs to the tile's node, not one of its own`() {
        compose.setContent {
            PosterTile(title = "Frieren", coverUrl = null, subtitle = "Ep 5/12", onClick = {})
        }

        compose.onNodeWithText("Ep 5/12").assert(hasRole(Role.Button))
    }
}
