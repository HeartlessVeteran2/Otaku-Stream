package com.otakustream.core.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class SubtitleEdgeStyle(val label: String) {
    NONE("None"),
    OUTLINE("Outline"),
    DROP_SHADOW("Shadow"),
    RAISED("Raised"),
}

enum class SubtitleTextColor(val label: String, val argb: Int) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    YELLOW("Yellow", 0xFFFFEB3B.toInt()),
    CYAN("Cyan", 0xFF4DD0E1.toInt()),
    GREEN("Green", 0xFF81C784.toInt()),
}

enum class SubtitleBackground(val label: String, val argb: Int) {
    TRANSPARENT("None", 0x00000000),
    SEMI("Dim", 0x80000000.toInt()),
    SOLID("Black", 0xFF000000.toInt()),
}

data class SubtitleStyle(
    val textScale: Float = 1f,
    val edgeStyle: SubtitleEdgeStyle = SubtitleEdgeStyle.OUTLINE,
    val textColor: SubtitleTextColor = SubtitleTextColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.TRANSPARENT,
    // 0.08 is Media3 SubtitleView's own default bottom padding.
    val bottomMarginFraction: Float = 0.08f,
)

// SharedPreferences keeps subtitle appearance out of the Room schema, same as
// PlayerOnboardingPrefs — a handful of scalars doesn't warrant a migration.
@Singleton
class SubtitleStylePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("subtitle_style", Context.MODE_PRIVATE)

    fun load(): SubtitleStyle = SubtitleStyle(
        textScale = prefs.getFloat(KEY_TEXT_SCALE, 1f).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
        edgeStyle = enumFromPrefs(KEY_EDGE_STYLE, SubtitleEdgeStyle.OUTLINE),
        textColor = enumFromPrefs(KEY_TEXT_COLOR, SubtitleTextColor.WHITE),
        background = enumFromPrefs(KEY_BACKGROUND, SubtitleBackground.TRANSPARENT),
        bottomMarginFraction = prefs.getFloat(KEY_BOTTOM_MARGIN, 0.08f).coerceIn(0f, MAX_BOTTOM_MARGIN),
    )

    fun save(style: SubtitleStyle) {
        prefs.edit()
            .putFloat(KEY_TEXT_SCALE, style.textScale)
            .putString(KEY_EDGE_STYLE, style.edgeStyle.name)
            .putString(KEY_TEXT_COLOR, style.textColor.name)
            .putString(KEY_BACKGROUND, style.background.name)
            .putFloat(KEY_BOTTOM_MARGIN, style.bottomMarginFraction)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumFromPrefs(key: String, default: T): T {
        val stored = prefs.getString(key, null) ?: return default
        return runCatching { enumValueOf<T>(stored) }.getOrDefault(default)
    }

    companion object {
        const val MIN_TEXT_SCALE = 0.5f
        const val MAX_TEXT_SCALE = 2f
        const val MAX_BOTTOM_MARGIN = 0.3f

        private const val KEY_TEXT_SCALE = "text_scale"
        private const val KEY_EDGE_STYLE = "edge_style"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_BACKGROUND = "background"
        private const val KEY_BOTTOM_MARGIN = "bottom_margin"
    }
}
