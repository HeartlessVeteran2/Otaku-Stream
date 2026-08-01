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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Serialised, and the "Saved" tick is tied to the values it actually saved.
    //
    // Bringing the JS engine up takes long enough for a second Save — or an edit — to land while the
    // first is still running. Without the lock, two saves could commit out of order and leave the
    // registry holding the older values; without the version check, a save completing after the user
    // had already changed something would tick "Saved" next to values that were never written.
    private val saveLock = Mutex()

    fun save() {
        viewModelScope.launch {
            saveLock.withLock {
                val editsAtStart = editVersion
                runCatching {
                    val resolved = JSONObject()
                    _uiState.value.items.forEach { item ->
                        when (item) {
                            is PrefItem.ListPref ->
                                resolved.put(item.key, item.entryValues.getOrNull(item.selectedIndex) ?: "")
                            is PrefItem.SwitchPref -> resolved.put(item.key, item.checked)
                            is PrefItem.EditTextPref -> resolved.put(item.key, item.value)
                        }
                    }
                    val prefsJson = resolved.toString()

                    // Build the replacement first, and only commit once it exists.
                    //
                    // createFromRecord brings the JS engine up with these values, which is the whole
                    // point — it is what makes "Saved" mean the extension accepted them. But that
                    // means it can throw on a value the extension rejects, and in the old order the
                    // throw landed after the write and the unregister: the rejected value was
                    // persisted, the working source was gone, and the user was left with an error
                    // and no extension. Constructing first makes a rejection a no-op.
                    val current = mangayomiRepository.getAll().firstOrNull { it.id == sourceId }
                        ?: error("Extension is no longer installed")
                    val replacement = factory.createFromRecord(current.copy(prefsJson = prefsJson))

                    try {
                        // NonCancellable covers the commit alone, so leaving the screen cannot
                        // strand the registry half-swapped.
                        withContext(NonCancellable) {
                            // The registry decides whether this extension still exists, rather than
                            // a check beforehand. Bringing QuickJS up takes long enough for the user
                            // to uninstall meanwhile, and re-reading the record only narrows that
                            // window — the delete can still land between the check and the swap.
                            // replaceDynamic refuses once the id is gone, which is the same instant
                            // uninstall removes it, so no ordering can resurrect a removed source.
                            if (!sourceRepository.replaceDynamic(replacement)) {
                                error("Extension is no longer installed")
                            }
                            mangayomiRepository.updatePrefs(sourceId, prefsJson)
                        }
                    } catch (t: Throwable) {
                        // Never registered, so nothing else will ever close it — and it owns a
                        // QuickJS thread and native context.
                        runCatching { replacement.close() }
                        throw t
                    }

                    // Reported only if the values on screen are still the ones this save captured;
                    // otherwise a save finishing after the user moved on would tick "Saved" next to
                    // values it knows nothing about. Clearing the error for the same reason: one
                    // left visible beside "Saved" describes two different attempts at once.
                    if (editVersion == editsAtStart) {
                        _uiState.value = _uiState.value.copy(saved = true, error = null)
                    }
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (editVersion == editsAtStart) {
                        // saved = false too: a failed retry after an earlier success would otherwise
                        // show "Saved" and an error side by side.
                        _uiState.value = _uiState.value.copy(
                            saved = false,
                            error = failure.message ?: "Failed to save preferences",
                        )
                    }
                }
            }
        }
    }

    // Bumped on every edit so an in-flight save can tell whether the values it captured are still
    // the ones on screen.
    private var editVersion = 0

    private fun updateItem(key: String, transform: (PrefItem) -> PrefItem) {
        editVersion++
        _uiState.value = _uiState.value.copy(
            items = _uiState.value.items.map { if (it.key == key) transform(it) else it },
            saved = false,
            // The values on screen are no longer the ones that were rejected, so the rejection no
            // longer describes anything the user can see.
            error = null,
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
