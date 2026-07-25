package com.otakustream.core.sources.scripting.net

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// User toggle for the WebView-based Cloudflare bypass. On by default — it's a no-op unless a
// request actually hits a challenge — but exposed so it can be turned off if the hidden WebView
// solve ever misbehaves on a device. Backed by plain SharedPreferences; the flag is not sensitive.
@Singleton
class CloudflareSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    // Lazy + async load: this @Singleton is constructed while the OkHttp client is first provided, so
    // it must not read the prefs file on that thread. Default on; the persisted value loads shortly.
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ioScope.launch { _enabled.value = runCatching { prefs.getBoolean(KEY_ENABLED, true) }.getOrDefault(true) }
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        ioScope.launch { runCatching { prefs.edit().putBoolean(KEY_ENABLED, value).apply() } }
    }

    private companion object {
        const val PREFS_NAME = "cloudflare_prefs"
        const val KEY_ENABLED = "cf_bypass_enabled"
    }
}
