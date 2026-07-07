package com.otakustream.feature.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.tracking.TrackerLink
import com.otakustream.core.database.tracking.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LinkDialogUiState(
    val results: List<AniListMedia> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LinkAniListViewModel @Inject constructor(
    private val aniListClient: AniListClient,
    private val trackingRepository: TrackingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LinkDialogUiState())
    val uiState: StateFlow<LinkDialogUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String) {
        if (query.isBlank()) return
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(isSearching = true, error = null)
        searchJob = viewModelScope.launch {
            runCatching { aniListClient.searchAnime(query) }
                .onSuccess { _uiState.value = _uiState.value.copy(results = it, isSearching = false) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message, isSearching = false) }
        }
    }

    fun link(mediaUrl: String, media: AniListMedia, onDone: () -> Unit) {
        viewModelScope.launch {
            trackingRepository.saveLink(TrackerLink(mediaUrl = mediaUrl, trackerMediaId = media.id, trackerTitle = media.title))
            onDone()
        }
    }
}

@Composable
fun LinkAniListDialog(
    mediaUrl: String,
    defaultQuery: String,
    onDismiss: () -> Unit,
    viewModel: LinkAniListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf(defaultQuery) }

    LaunchedEffect(Unit) { viewModel.search(defaultQuery) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Link to AniList") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search AniList") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { viewModel.search(query) }) { Text("Search") }

                if (uiState.isSearching) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                }
                uiState.error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }

                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(uiState.results, key = { it.id }) { media ->
                        ListItem(
                            headlineContent = { Text(media.title) },
                            supportingContent = { media.episodes?.let { Text("$it episodes") } },
                            modifier = Modifier.clickable { viewModel.link(mediaUrl, media, onDismiss) },
                        )
                    }
                }
            }
        },
    )
}
