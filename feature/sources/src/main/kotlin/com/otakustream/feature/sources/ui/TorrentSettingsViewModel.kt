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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class QuotaChoice(val label: String, val bytes: Long)

data class TorrentSettingsUiState(
    val deviceSupported: Boolean = false,
    val enabled: Boolean = false,
    val unmeteredOnly: Boolean = true,
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

    // One job for every disk operation this screen triggers, cancelled and replaced rather than run
    // alongside its predecessor.
    //
    // Necessary, not tidy. Tapping 512 MB then 8 GB used to queue two sweeps: the first captured
    // 512 MB and, if it finished second, evicted files the user had just chosen to keep. The same race
    // let a usage scan started before a sweep report its pre-sweep total afterwards, so the figure
    // jumped back up on its own.
    private var cacheJob: Job? = null

    // Cancelling the job above is not sufficient on its own. TorrentCacheSweeper is ordinary blocking
    // filesystem code with no suspension points, so a cancelled coroutine that is already inside it
    // keeps walking and deleting to completion. The mutex is what actually makes the operations
    // serial: a newer request waits rather than deleting and scanning concurrently with the old one,
    // so the usage figure it finally publishes describes a directory nobody else is still changing.
    private val cacheMutex = Mutex()

    init {
        readSettings()
        refreshUsage()
    }

    fun setEnabled(value: Boolean) {
        settings.enabled = value
        readSettings()
    }

    fun setUnmeteredOnly(value: Boolean) {
        settings.unmeteredOnly = value
        readSettings()
    }

    fun setQuota(bytes: Long) {
        settings.quotaBytes = bytes
        readSettings()
        // Apply it immediately rather than waiting for the next playback to end: lowering the limit
        // and seeing the usage figure stay put looks like the setting was ignored.
        runCacheOperation {
            TorrentCacheSweeper.sweep(
                torrentCacheDir(context),
                // Read back from settings, not from the captured argument: the setter clamps to a
                // minimum, and by the time this runs a later tap may have raised the limit.
                quotaBytes = settings.quotaBytes,
                protectedPaths = engine.protectedCachePaths(),
            )
        }
    }

    fun clearCache() {
        _uiState.value = _uiState.value.copy(isClearing = true)
        runCacheOperation(
            onFinished = { _uiState.value = _uiState.value.copy(isClearing = false) },
        ) {
            TorrentCacheSweeper.clear(torrentCacheDir(context), engine.protectedCachePaths())
        }
    }

    // Runs one disk operation, then re-reads usage — replacing any operation still in flight so the
    // most recent request is the one whose result the user sees.
    private fun runCacheOperation(onFinished: () -> Unit = {}, block: () -> Unit) {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            try {
                // Sweep and re-read under one lock hold, so the figure published below can't be
                // measured against a directory a queued operation is about to change again.
                val usage = cacheMutex.withLock {
                    withContext(Dispatchers.IO) {
                        runCatching(block)
                        readUsage()
                    }
                }
                _uiState.value = _uiState.value.copy(usageBytes = usage)
            } finally {
                // In a finally block so a cancelled clear can't leave the button stuck on "Clearing…".
                onFinished()
            }
        }
    }

    private fun refreshUsage() = runCacheOperation {}

    private fun readSettings() {
        // isAvailable, not isUsable: this screen is where the user turns the feature on, so it must
        // reflect what the device can do rather than what the current settings allow — otherwise
        // switching it off would hide the switch that turns it back on.
        val supported = engine.isAvailable
        _uiState.value = _uiState.value.copy(
            deviceSupported = supported,
            enabled = supported && settings.enabled,
            unmeteredOnly = settings.unmeteredOnly,
            quotaBytes = settings.quotaBytes,
        )
    }

    private fun readUsage(): Long =
        runCatching { TorrentCacheSweeper.usageBytes(torrentCacheDir(context)) }.getOrDefault(0L)
}
