package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.sources.scripting.ScriptSourceInstaller
import com.otakustream.core.sources.scripting.stableSourceId
import com.otakustream.core.sources.stremio.StremioAddonInstaller
import com.otakustream.core.sources.stremio.normalizeStremioManifestUrl
import com.otakustream.feature.sources.SourceCatalogClient
import com.otakustream.feature.sources.SourceCatalogEntry
import com.otakustream.feature.sources.SourceCatalogPrefs
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowseSourceCatalogUiState(
    val isLoading: Boolean = false,
    val entries: List<SourceCatalogEntry> = emptyList(),
    // entry.url values that are already installed.
    val installedUrls: Set<String> = emptySet(),
    val installingUrl: String? = null,
    val repoUrl: String = "",
    val error: String? = null,
)

@HiltViewModel
class BrowseSourceCatalogViewModel @Inject constructor(
    private val catalogClient: SourceCatalogClient,
    private val catalogPrefs: SourceCatalogPrefs,
    private val scriptInstaller: ScriptSourceInstaller,
    private val stremioInstaller: StremioAddonInstaller,
    private val stremioRepository: StremioRepository,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseSourceCatalogUiState(repoUrl = catalogPrefs.repoUrl))
    val uiState: StateFlow<BrowseSourceCatalogUiState> = _uiState.asStateFlow()

    private var installedSourceIds: Set<Long> = emptySet()
    private var installedManifestUrls: Set<String> = emptySet()
    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            combine(sourceRepository.observeSources(), stremioRepository.observeAddons()) { sources, addons ->
                sources.map { it.id }.toSet() to addons.map { it.manifestUrl }.toSet()
            }.collect { (sourceIds, manifestUrls) ->
                installedSourceIds = sourceIds
                installedManifestUrls = manifestUrls
                recomputeInstalled()
            }
        }
    }

    fun load() {
        // Cancel any in-flight load so fast Load/Retry taps can't race stale results into state.
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            runCatching { catalogClient.fetch() }
                .onSuccess { entries -> _uiState.value = _uiState.value.copy(entries = entries) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(error = failure.message ?: "Failed to load source catalog")
                }
            _uiState.value = _uiState.value.copy(isLoading = false)
            recomputeInstalled()
        }
    }

    fun onRepoUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(repoUrl = value)
    }

    fun saveRepoUrl() {
        catalogPrefs.repoUrl = _uiState.value.repoUrl
        load()
    }

    fun install(entry: SourceCatalogEntry) {
        _uiState.value = _uiState.value.copy(installingUrl = entry.url, error = null)
        viewModelScope.launch {
            runCatching {
                when (entry.type) {
                    SourceCatalogEntry.TYPE_SCRIPTED -> {
                        sourceRepository.registerDynamic(scriptInstaller.installFromUrl(entry.url))
                    }
                    SourceCatalogEntry.TYPE_STREMIO -> {
                        val nextPriority = (stremioRepository.getAllAddons().maxOfOrNull { it.priority } ?: -1) + 1
                        stremioInstaller.installFromUrl(entry.url, priority = nextPriority)
                            .forEach(sourceRepository::registerDynamic)
                    }
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _uiState.value = _uiState.value.copy(error = failure.message ?: "Failed to install source")
            }
            _uiState.value = _uiState.value.copy(installingUrl = null)
        }
    }

    // A scripted entry is installed when its stable id (name+lang) is registered; a Stremio entry
    // when its normalized manifest URL is among the installed add-ons.
    private fun recomputeInstalled() {
        val installed = _uiState.value.entries.filter { entry ->
            when (entry.type) {
                SourceCatalogEntry.TYPE_SCRIPTED -> stableSourceId(entry.name, entry.lang) in installedSourceIds
                SourceCatalogEntry.TYPE_STREMIO -> normalizeStremioManifestUrl(entry.url) in installedManifestUrls
                else -> false
            }
        }.map { it.url }.toSet()
        _uiState.value = _uiState.value.copy(installedUrls = installed)
    }
}
