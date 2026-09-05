package com.otakustream.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Crisper than Material3's 4/8/12/16/28, but no longer square.
//
// The previous 2/2/4/6/8 went for a comic-panel feel and mostly got away with it on cards, where
// a 4dp corner reads as deliberate. It did not get away with it on the largest slot: extraLarge is
// what ModalBottomSheet rounds its top corners by, so the three player sheets (tracks, subtitle
// style, equaliser) slid up as flat-topped slabs that read as a broken layout rather than a sheet.
// The step from 8 to 22 there is the point of this change; the rest move with it so the scale
// stays proportional.
val OtakuShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(22.dp),
)
