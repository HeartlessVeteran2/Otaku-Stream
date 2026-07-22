package com.otakustream.core.sources.scripting.net

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// User toggle for the WebView-based Cloudflare bypass. On by default — it's a no-op unless a
// request actually hits a challenge — but exposed so it can be turned off if the hidden WebView
// solve ever misbehaves on a device. Backed by plain SharedPreferences; the flag is not sensitive.
@Singleton
class CloudflareSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private companion object {
        const val PREFS_NAME = "cloudflare_prefs"
        const val KEY_ENABLED = "cf_bypass_enabled"
    }
}
