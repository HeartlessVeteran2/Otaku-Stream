package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.stremio.StremioAddonRecord
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.sources.api.stableSourceId
import com.otakustream.core.sources.stremio.StremioAddonInstaller
import com.otakustream.core.sources.stremio.model.parseManifest
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StremioCatalogItem(val type: String, val id: String, val name: String, val enabled: Boolean)

data class StremioAddonItem(val record: StremioAddonRecord, val catalogs: List<StremioCatalogItem>)

data class ManageStremioUiState(
    val addons: List<StremioAddonItem> = emptyList(),
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
    private val addonItems = MutableStateFlow<List<StremioAddonItem>>(emptyList())

    val uiState: StateFlow<ManageStremioUiState> = combine(
        addonItems,
        urlInput,
        isInstalling,
        error,
    ) { addons, url, installing, err ->
        ManageStremioUiState(addons = addons, urlInput = url, isInstalling = installing, error = err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageStremioUiState())

    val serverBaseUrl: StateFlow<String?> = stremioRepository.observeServerBaseUrl()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // stremio_catalog_toggles is a separate table from stremio_addons, so a catalog-only
        // toggle doesn't itself re-trigger this — refresh() is called explicitly after those.
        viewModelScope.launch {
            stremioRepository.observeAddons().collect { records -> refreshAddonItems(records) }
        }
    }

    private suspend fun refreshAddonItems(records: List<StremioAddonRecord>) {
        val disabledCatalogKeys = stremioRepository.getDisabledCatalogKeys()
        addonItems.value = records.map { record ->
            val catalogs = runCatching { parseManifest(record.manifestJson) }.getOrNull()?.catalogs.orEmpty().map { catalog ->
                StremioCatalogItem(
                    type = catalog.type,
                    id = catalog.id,
                    name = catalog.name,
                    enabled = Triple(record.manifestUrl, catalog.type, catalog.id) !in disabledCatalogKeys,
                )
            }
            StremioAddonItem(record = record, catalogs = catalogs)
        }
    }

    private suspend fun refresh() = refreshAddonItems(stremioRepository.getAllAddons())

    fun onUrlInputChange(value: String) {
        urlInput.value = value
    }

    fun install() {
        val url = urlInput.value.trim()
        if (url.isBlank()) return
        isInstalling.value = true
        error.value = null
        viewModelScope.launch {
            runCatching {
                val nextPriority = (stremioRepository.getAllAddons().maxOfOrNull { it.priority } ?: -1) + 1
                installer.installFromUrl(url, priority = nextPriority)
            }
                .onSuccess { sources ->
                    sources.forEach(sourceRepository::registerDynamic)
                    urlInput.value = ""
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    error.value = failure.message ?: "Failed to install addon"
                }
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

    fun toggleAddonEnabled(item: StremioAddonItem) {
        val record = item.record
        val newEnabled = !record.enabled
        viewModelScope.launch {
            stremioRepository.setAddonEnabled(record.manifestUrl, newEnabled)
            val sources = runCatching {
                installer.buildSources(record.manifestUrl, record.manifestJson) { type, id ->
                    item.catalogs.find { it.type == type && it.id == id }?.enabled ?: true
                }
            }.getOrDefault(emptyList())
            if (newEnabled) {
                sources.forEach(sourceRepository::registerDynamic)
            } else {
                sources.forEach { source -> sourceRepository.unregisterDynamic(source.id) }
            }
        }
    }

    // Reordering only affects the persisted priority (and therefore cold-start rebuild order) —
    // it doesn't try to live-reorder already-registered dynamic sources, since those interleave
    // catalog-sources from every installed addon with no cheap way to regroup them by addon.
    fun moveAddon(item: StremioAddonItem, direction: Int) {
        val list = addonItems.value
        val index = list.indexOfFirst { it.record.manifestUrl == item.record.manifestUrl }
        val targetIndex = index + direction
        if (index < 0 || targetIndex !in list.indices) return
        val other = list[targetIndex]
        viewModelScope.launch {
            stremioRepository.setAddonPriority(item.record.manifestUrl, other.record.priority)
            stremioRepository.setAddonPriority(other.record.manifestUrl, item.record.priority)
        }
    }

    fun toggleCatalogEnabled(item: StremioAddonItem, catalog: StremioCatalogItem) {
        val newEnabled = !catalog.enabled
        viewModelScope.launch {
            stremioRepository.setCatalogEnabled(item.record.manifestUrl, catalog.type, catalog.id, newEnabled)
            val sourceId = stableSourceId(item.record.manifestUrl, catalog.type, catalog.id)
            if (newEnabled) {
                if (item.record.enabled) {
                    installer.buildSources(item.record.manifestUrl, item.record.manifestJson) { type, id ->
                        type == catalog.type && id == catalog.id
                    }.firstOrNull()?.let(sourceRepository::registerDynamic)
                }
            } else {
                sourceRepository.unregisterDynamic(sourceId)
            }
            refresh()
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
