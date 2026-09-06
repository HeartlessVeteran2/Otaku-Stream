package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

// How a horizontal rail of poster tiles is laid out, in one place.
//
// One place because the alternative caused a real bug: the tile used to carry its own start
// padding, so a rail that also set contentPadding double-counted it — first tile 32dp in, every gap
// 16dp. Spacing belongs to the row, which is the only thing that knows whether it has edges to
// inset from; these are the numbers every such row uses.
//
// In their own file rather than beside AniListPosterTile, where they started: Home's generic
// catalog and continue-watching rails use them too, and constants shared across the module do not
// belong in a file named for one of the callers.

// A poster is 2:3, so the height follows. 120dp puts three and a bit on a phone, which is the width
// that reads as "scroll me" rather than "this is the whole row".
internal val RailTileWidth = 120.dp

// Insets the row's ends so the first and last tile sit on the same margin as the headings above.
internal val RailPadding = PaddingValues(horizontal = 16.dp)

internal val RailSpacing = Arrangement.spacedBy(12.dp)
