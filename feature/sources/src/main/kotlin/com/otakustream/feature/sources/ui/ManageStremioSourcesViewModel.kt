package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.stremio.StremioAddonRecord
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.sources.stremio.StremioAddonInstaller
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ManageStremioUiState(
    val addons: List<StremioAddonRecord> = emptyList(),
    val urlInput: String = "",
    val isInstalling: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ManageStremioSourcesViewModel @Inject constructor(
    private val stremioRepository: StremioRepository,
    private val installer: StremioAddonInstaller,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    private val urlInput = MutableStateFlow("")
    private val isInstalling = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ManageStremioUiState> = combine(
        stremioRepository.observeAddons(),
        urlInput,
        isInstalling,
        error,
    ) { addons, url, installing, err ->
        ManageStremioUiState(addons = addons, urlInput = url, isInstalling = installing, error = err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageStremioUiState())

    val serverBaseUrl: StateFlow<String?> = stremioRepository.observeServerBaseUrl()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onUrlInputChange(value: String) {
        urlInput.value = value
    }

    fun install() {
        val url = urlInput.value.trim()
        if (url.isBlank()) return
        isInstalling.value = true
        error.value = null
        viewModelScope.launch {
            runCatching { installer.installFromUrl(url) }
                .onSuccess { sources ->
                    sources.forEach(sourceRepository::registerDynamic)
                    urlInput.value = ""
                }
                .onFailure { failure -> error.value = failure.message ?: "Failed to install addon" }
            isInstalling.value = false
        }
    }

    fun remove(record: StremioAddonRecord) {
        viewModelScope.launch {
            runCatching { installer.buildSources(record.manifestUrl, record.manifestJson) }
                .getOrDefault(emptyList())
                .forEach { source -> sourceRepository.unregisterDynamic(source.id) }
            installer.uninstall(record.manifestUrl)
        }
    }

    fun saveServerUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        viewModelScope.launch { stremioRepository.saveServerBaseUrl(normalized) }
    }

    fun clearServerUrl() {
        viewModelScope.launch { stremioRepository.clearServerBaseUrl() }
    }
}
