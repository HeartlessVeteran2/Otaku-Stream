package com.otakustream.core.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// A tiny SharedPreferences-backed flag for one-time player onboarding (the gesture coach-mark).
// SharedPreferences keeps this out of the Room schema — no DB migration for a single boolean.
@Singleton
class PlayerOnboardingPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("player_onboarding", Context.MODE_PRIVATE)

    var hasSeenGestureCoach: Boolean
        get() = prefs.getBoolean(KEY_SEEN_GESTURE_COACH, false)
        set(value) { prefs.edit().putBoolean(KEY_SEEN_GESTURE_COACH, value).apply() }

    private companion object {
        const val KEY_SEEN_GESTURE_COACH = "seen_gesture_coach"
    }
}
