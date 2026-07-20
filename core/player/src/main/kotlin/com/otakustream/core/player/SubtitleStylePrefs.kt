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
) {
    // Adjustment ranges live on the model, not the persistence class, so the UI can bound its
    // controls without depending on SubtitleStylePrefs.
    companion object {
        const val MIN_TEXT_SCALE = 0.5f
        const val MAX_TEXT_SCALE = 2f
        const val MAX_BOTTOM_MARGIN = 0.3f
    }
}

// SharedPreferences keeps subtitle appearance out of the Room schema, same as
// PlayerOnboardingPrefs — a handful of scalars doesn't warrant a migration.
@Singleton
class SubtitleStylePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("subtitle_style", Context.MODE_PRIVATE)

    fun load(): SubtitleStyle {
        // Reference the data class's own defaults so there's a single source of truth.
        val default = SubtitleStyle()
        return SubtitleStyle(
            textScale = prefs.getFloat(KEY_TEXT_SCALE, default.textScale)
                .coerceIn(SubtitleStyle.MIN_TEXT_SCALE, SubtitleStyle.MAX_TEXT_SCALE),
            edgeStyle = enumFromPrefs(KEY_EDGE_STYLE, default.edgeStyle),
            textColor = enumFromPrefs(KEY_TEXT_COLOR, default.textColor),
            background = enumFromPrefs(KEY_BACKGROUND, default.background),
            bottomMarginFraction = prefs.getFloat(KEY_BOTTOM_MARGIN, default.bottomMarginFraction)
                .coerceIn(0f, SubtitleStyle.MAX_BOTTOM_MARGIN),
        )
    }

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

    private companion object {
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_EDGE_STYLE = "edge_style"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_BACKGROUND = "background"
        const val KEY_BOTTOM_MARGIN = "bottom_margin"
    }
}
