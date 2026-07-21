package com.otakustream.feature.sources.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.mangayomi.MangayomiSourceRecord
import com.otakustream.core.database.mangayomi.MangayomiSourceRepository
import com.otakustream.core.sources.mangayomi.MangayomiSourceFactory
import com.otakustream.core.sources.mangayomi.MangayomiVideoSource
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

// One editable preference declared by an extension's getSourcePreferences().
sealed interface PrefItem {
    val key: String
    val title: String
    val summary: String

    data class ListPref(
        override val key: String,
        override val title: String,
        override val summary: String,
        val entries: List<String>,
        val entryValues: List<String>,
        val selectedIndex: Int,
    ) : PrefItem

    data class SwitchPref(
        override val key: String,
        override val title: String,
        override val summary: String,
        val checked: Boolean,
    ) : PrefItem

    data class EditTextPref(
        override val key: String,
        override val title: String,
        override val summary: String,
        val value: String,
    ) : PrefItem
}

data class MangayomiPreferencesUiState(
    val isLoading: Boolean = true,
    val sourceName: String = "",
    val items: List<PrefItem> = emptyList(),
    val saved: Boolean = false,
    val error: String? = null,
)

// Renders an installed extension's declared preferences, persists changes to
// mangayomi_sources.prefsJson, and reloads the source so the new values take effect (the QuickJS
// runtime reads them at construction via getPreference/__pref_get).
@HiltViewModel
class MangayomiPreferencesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sourceRepository: SourceRepository,
    private val mangayomiRepository: MangayomiSourceRepository,
    private val factory: MangayomiSourceFactory,
) : ViewModel() {

    private val sourceId: Long = savedStateHandle.get<String>("sourceId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(MangayomiPreferencesUiState())
    val uiState: StateFlow<MangayomiPreferencesUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            runCatching {
                val record = mangayomiRepository.getAll().firstOrNull { it.id == sourceId }
                val source = sourceRepository.getSource(sourceId) as? MangayomiVideoSource
                val descriptors = source?.getSourcePreferences() ?: "[]"
                val stored = record?.prefsJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                val items = parseDescriptors(descriptors, stored)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    sourceName = record?.name.orEmpty(),
                    items = items,
                )
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _uiState.value = _uiState.value.copy(isLoading = false, error = failure.message ?: "Failed to load preferences")
            }
        }
    }

    fun onListSelect(key: String, index: Int) = updateItem(key) {
        (it as? PrefItem.ListPref)?.copy(selectedIndex = index) ?: it
    }

    fun onSwitchToggle(key: String, checked: Boolean) = updateItem(key) {
        (it as? PrefItem.SwitchPref)?.copy(checked = checked) ?: it
    }

    fun onEditText(key: String, value: String) = updateItem(key) {
        (it as? PrefItem.EditTextPref)?.copy(value = value) ?: it
    }

    fun save() {
        viewModelScope.launch {
            runCatching {
                val resolved = JSONObject()
                _uiState.value.items.forEach { item ->
                    when (item) {
                        is PrefItem.ListPref -> resolved.put(item.key, item.entryValues.getOrNull(item.selectedIndex) ?: "")
                        is PrefItem.SwitchPref -> resolved.put(item.key, item.checked)
                        is PrefItem.EditTextPref -> resolved.put(item.key, item.value)
                    }
                }
                val prefsJson = resolved.toString()
                // Persist, then swap the live source for one rebuilt with the new preferences.
                // NonCancellable so the DB write + registry swap complete atomically.
                withContext(NonCancellable) {
                    mangayomiRepository.updatePrefs(sourceId, prefsJson)
                    val updated = mangayomiRepository.getAll().firstOrNull { it.id == sourceId } ?: return@withContext
                    sourceRepository.unregisterDynamic(sourceId)
                    sourceRepository.registerDynamic(factory.createFromRecord(updated))
                }
                _uiState.value = _uiState.value.copy(saved = true)
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _uiState.value = _uiState.value.copy(error = failure.message ?: "Failed to save preferences")
            }
        }
    }

    private fun updateItem(key: String, transform: (PrefItem) -> PrefItem) {
        _uiState.value = _uiState.value.copy(
            items = _uiState.value.items.map { if (it.key == key) transform(it) else it },
            saved = false,
        )
    }

    private fun parseDescriptors(json: String, stored: JSONObject?): List<PrefItem> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val key = obj.optString("key").ifEmpty { return@mapNotNull null }
            obj.optJSONObject("listPreference")?.let { pref ->
                val entryValues = pref.optJSONArray("entryValues").toStringList()
                val storedValue = stored?.opt(key)?.toString()
                val defaultIndex = pref.optInt("valueIndex", 0)
                val selected = storedValue?.let { entryValues.indexOf(it).takeIf { i -> i >= 0 } } ?: defaultIndex
                return@mapNotNull PrefItem.ListPref(
                    key = key,
                    title = pref.optString("title", key),
                    summary = pref.optString("summary"),
                    entries = pref.optJSONArray("entries").toStringList(),
                    entryValues = entryValues,
                    selectedIndex = selected.coerceIn(0, (entryValues.size - 1).coerceAtLeast(0)),
                )
            }
            obj.optJSONObject("switchPreferenceCompat")?.let { pref ->
                val default = pref.optBoolean("value", false)
                return@mapNotNull PrefItem.SwitchPref(
                    key = key,
                    title = pref.optString("title", key),
                    summary = pref.optString("summary"),
                    checked = stored?.optBoolean(key, default) ?: default,
                )
            }
            obj.optJSONObject("editTextPreference")?.let { pref ->
                val default = pref.optString("value")
                return@mapNotNull PrefItem.EditTextPref(
                    key = key,
                    title = pref.optString("title", key),
                    summary = pref.optString("summary"),
                    value = stored?.optString(key, default) ?: default,
                )
            }
            null
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }
}
