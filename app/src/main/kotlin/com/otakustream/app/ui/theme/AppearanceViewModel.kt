package com.otakustream.app.ui.theme

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// A window onto the one value in AppearancePrefs, so the settings screen can read and write it
// without AppNavHost having to thread the mode down from the activity alongside its ten other
// parameters. There is deliberately no state of its own here — the singleton's StateFlow is the
// only copy, which is what lets the activity and the settings screen stay in step.
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val prefs: AppearancePrefs,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = prefs.themeMode

    fun setThemeMode(mode: ThemeMode) = prefs.setThemeMode(mode)
}
