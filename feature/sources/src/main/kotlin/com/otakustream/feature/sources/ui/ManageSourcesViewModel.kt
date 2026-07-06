package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.scripted.ScriptedSourceRecord
import com.otakustream.core.database.scripted.ScriptedSourceRepository
import com.otakustream.core.sources.scripting.ScriptSourceInstaller
import com.otakustream.core.sources.scripting.stableSourceId
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ManageSourcesUiState(
    val installed: List<ScriptedSourceRecord> = emptyList(),
    val urlInput: String = "",
    val isInstalling: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ManageSourcesViewModel @Inject constructor(
    private val scriptedSourceRepository: ScriptedSourceRepository,
    private val installer: ScriptSourceInstaller,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    private val urlInput = MutableStateFlow("")
    private val isInstalling = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ManageSourcesUiState> = combine(
        scriptedSourceRepository.observeAll(),
        urlInput,
        isInstalling,
        error,
    ) { installed, url, installing, err ->
        ManageSourcesUiState(installed = installed, urlInput = url, isInstalling = installing, error = err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageSourcesUiState())

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
                .onSuccess { source ->
                    sourceRepository.registerDynamic(source)
                    urlInput.value = ""
                }
                .onFailure { failure -> error.value = failure.message ?: "Failed to install source" }
            isInstalling.value = false
        }
    }

    fun remove(record: ScriptedSourceRecord) {
        viewModelScope.launch {
            installer.uninstall(record.scriptUrl)
            sourceRepository.unregisterDynamic(stableSourceId(record.name, record.lang))
        }
    }
}
