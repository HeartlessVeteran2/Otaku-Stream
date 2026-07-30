package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.torrent.TorrentCacheSweeper
import com.otakustream.core.torrent.TorrentEngine
import com.otakustream.core.torrent.TorrentSettings
import com.otakustream.core.torrent.torrentCacheDir
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class QuotaChoice(val label: String, val bytes: Long)

data class TorrentSettingsUiState(
    val deviceSupported: Boolean = false,
    val enabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val quotaBytes: Long = 0,
    val usageBytes: Long = 0,
    val isClearing: Boolean = false,
    val quotaChoices: List<QuotaChoice> = QUOTA_CHOICES,
) {
    val usageLabel: String
        get() = when {
            usageBytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(usageBytes / (1024f * 1024 * 1024))
            usageBytes >= 1024L * 1024 -> "${usageBytes / (1024 * 1024)} MB"
            usageBytes > 0 -> "under 1 MB"
            else -> "nothing"
        }
}

private val QUOTA_CHOICES = listOf(
    QuotaChoice("512 MB", 512L * 1024 * 1024),
    QuotaChoice("2 GB", 2L * 1024 * 1024 * 1024),
    QuotaChoice("8 GB", 8L * 1024 * 1024 * 1024),
)

@HiltViewModel
class TorrentSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: TorrentSettings,
    private val engine: TorrentEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TorrentSettingsUiState())
    val uiState: StateFlow<TorrentSettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun setEnabled(value: Boolean) {
        settings.enabled = value
        refresh()
    }

    fun setWifiOnly(value: Boolean) {
        settings.wifiOnly = value
        refresh()
    }

    fun setQuota(bytes: Long) {
        settings.quotaBytes = bytes
        refresh()
        // Apply it immediately rather than waiting for the next playback to end: lowering the limit
        // and seeing the usage figure stay put looks like the setting was ignored.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    TorrentCacheSweeper.sweep(
                        torrentCacheDir(context),
                        quotaBytes = bytes,
                        protectedPaths = engine.protectedCachePaths(),
                    )
                }
            }
            refresh()
        }
    }

    fun clearCache() {
        _uiState.value = _uiState.value.copy(isClearing = true)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    TorrentCacheSweeper.clear(torrentCacheDir(context), engine.protectedCachePaths())
                }
            }
            _uiState.value = _uiState.value.copy(isClearing = false)
            refresh()
        }
    }

    private fun refresh() {
        // isAvailable, not isUsable: this screen is where the user turns the feature on, so it must
        // reflect what the device can do rather than what the current settings allow — otherwise
        // switching it off would hide the switch that turns it back on.
        val supported = engine.isAvailable
        _uiState.value = _uiState.value.copy(
            deviceSupported = supported,
            enabled = supported && settings.enabled,
            wifiOnly = settings.wifiOnly,
            quotaBytes = settings.quotaBytes,
        )
        viewModelScope.launch {
            val usage = withContext(Dispatchers.IO) {
                runCatching { TorrentCacheSweeper.usageBytes(torrentCacheDir(context)) }.getOrDefault(0L)
            }
            _uiState.value = _uiState.value.copy(usageBytes = usage)
        }
    }
}
