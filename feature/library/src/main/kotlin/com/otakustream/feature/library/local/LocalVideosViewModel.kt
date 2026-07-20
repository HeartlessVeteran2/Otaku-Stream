package com.otakustream.feature.library.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalVideosUiState(
    val hasPermission: Boolean = false,
    val isLoading: Boolean = false,
    val videos: List<LocalVideo> = emptyList(),
    val hasLoadedOnce: Boolean = false,
)

@HiltViewModel
class LocalVideosViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LocalVideoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalVideosUiState(hasPermission = hasReadPermission()))
    val uiState: StateFlow<LocalVideosUiState> = _uiState.asStateFlow()

    // The permission the current OS actually gates video reads on.
    val requiredPermission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun refresh() {
        val granted = hasReadPermission()
        _uiState.value = _uiState.value.copy(hasPermission = granted)
        if (!granted) return
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            val videos = repository.loadVideos()
            _uiState.value = _uiState.value.copy(isLoading = false, videos = videos, hasLoadedOnce = true)
        }
    }

    private fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
}
