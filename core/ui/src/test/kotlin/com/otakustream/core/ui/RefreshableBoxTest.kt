package com.otakustream.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The pull gesture only reaches RefreshableBox through nested scroll, which means it works when the
// content is a scroll container and silently does nothing when it isn't. That failure is invisible:
// no crash, no warning, just a screen where dragging down does nothing — and the states where it
// would break are the empty ones, which are exactly the states where reloading is the only thing
// left to try.
//
// So both halves are tested: a lazy list, which every one of these screens shows when it has
// content, and PullablePlaceholder, which is the thing standing in for a scroll container when they
// don't.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RefreshableBoxTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `pulling a list down asks for a refresh`() {
        var refreshes = 0
        compose.setContent {
            RefreshableBox(
                isRefreshing = false,
                onRefresh = { refreshes++ },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag(CONTENT)) {
                    items(20) { Text("row $it", modifier = Modifier.height(48.dp)) }
                }
            }
        }

        compose.onNodeWithTag(CONTENT).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, refreshes)
    }

    // The one that regresses. An EmptyState is a Column of an icon and two lines of text: it does
    // not scroll, so without PullablePlaceholder's verticalScroll the drag is never offered to the
    // box above and onRefresh is never called.
    @Test
    fun `pulling an empty state down asks for a refresh`() {
        var refreshes = 0
        compose.setContent {
            RefreshableBox(
                isRefreshing = false,
                onRefresh = { refreshes++ },
                modifier = Modifier.fillMaxSize(),
            ) {
                PullablePlaceholder(modifier = Modifier.testTag(CONTENT)) {
                    EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = "No matches",
                        message = "Nothing here for that search.",
                    )
                }
            }
        }

        compose.onNodeWithTag(CONTENT).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, refreshes)
    }

    // The counterpart, and the reason the two tests above are not enough on their own: if a plain
    // Box were also enough to carry the gesture, PullablePlaceholder would be decoration and its
    // removal would break nothing visible. It isn't, and this pins that down — so a later change
    // that drops the wrapper from a screen fails here instead of shipping a dead gesture.
    @Test
    fun `pulling content that cannot scroll does nothing`() {
        var refreshes = 0
        compose.setContent {
            RefreshableBox(
                isRefreshing = false,
                onRefresh = { refreshes++ },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize().testTag(CONTENT)) {
                    Text("nothing to see")
                }
            }
        }

        compose.onNodeWithTag(CONTENT).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(0, refreshes)
    }

    private companion object {
        const val CONTENT = "refreshable-content"
    }
}
