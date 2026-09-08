package com.otakustream.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// Pull down to reload, in one place.
//
// This is a thin wrapper over PullToRefreshBox and exists for two reasons rather than for its own
// sake. The first is the tint: every other spinner in this app is `tertiary` — the accent the
// colour system picked — and the stock indicator is `primary` on `surfaceContainer`, so a
// hand-rolled PullToRefreshBox per screen would put the one spinner a user deliberately summons in
// a colour none of the automatic ones use. The second is the opt-in: PullToRefreshBox is still
// ExperimentalMaterial3Api in Material3 1.3.1, and spreading that annotation across three feature
// screens spreads the churn of whatever it stabilises into.
//
// `isRefreshing` must be a flag that is true for exactly as long as the pull-triggered reload runs.
// It is deliberately not any `isLoading` those screens already have: those are also true during the
// *first* load, so binding one here would pop this indicator on every cold start, on top of the
// in-content spinner the screen already shows for that case.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // Hoisted so the custom indicator below can be driven by the same state the gesture writes to.
    // Left to the default, PullToRefreshBox would create one state for the drag and the indicator
    // lambda would have no way to reach it.
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                // The default indicator aligns itself; supplying our own means aligning it too, and
                // without this it renders at the box's top-*start* corner.
                modifier = Modifier.align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
        content = content,
    )
}

// Content that is shorter than the screen, made pullable anyway.
//
// The pull gesture reaches RefreshableBox through nested scroll, which means something inside it
// has to be a scroll container — a lazy list or a `verticalScroll`. That is true of the grids and
// rails these screens normally show and false of every empty state, error message and placeholder
// they fall back to. Without this the gesture works while there is content and dies exactly where
// reloading is the only thing left to try: "No matches", an empty Stremio library, a source that
// returned nothing.
//
// A `verticalScroll` whose content fits has a scroll range of zero, so it consumes none of the drag
// and hands all of it back to the parent — which is precisely what makes the pull register. The
// centring is the same arrangement EmptyState uses internally; EmptyState is `fillMaxWidth`, not
// `fillMaxSize`, so its own `CenterVertically` does nothing unless something gives it the height.
@Composable
fun PullablePlaceholder(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
