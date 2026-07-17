package com.otakustream.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Bolder, tighter-tracked headings evoke poster titling; body text is left at Material3
// defaults so reading-heavy content (descriptions, cast lists) stays maximally legible.
private val defaultTypography = Typography()

val OtakuTypography = defaultTypography.copy(
    displayLarge = defaultTypography.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    displayMedium = defaultTypography.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    displaySmall = defaultTypography.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineLarge = defaultTypography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp),
    headlineMedium = defaultTypography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp),
    headlineSmall = defaultTypography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp),
    titleLarge = defaultTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = defaultTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = defaultTypography.titleSmall.copy(fontWeight = FontWeight.Bold),
    labelLarge = defaultTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
    labelMedium = defaultTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
    labelSmall = defaultTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
)
