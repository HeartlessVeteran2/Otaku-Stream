package com.otakustream.feature.sources.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

// A wall-clock reading that advances while the screen is open.
//
// Countdowns need two things that pull against each other. Every row on screen must be measured
// from the same instant — reading the clock per row lets the top and bottom of a list disagree by a
// minute, which looks like a rendering bug — and the reading must not be frozen at first
// composition, or "in 1m" is still on screen a quarter of an hour later.
//
// One value, shared by every row, replaced on a tick. Thirty seconds because the finest unit any
// countdown renders is a minute, so anything faster would recompose the list to produce identical
// text.
@Composable
fun rememberTickingNow(intervalMs: Long = 30_000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
