package com.otakustream.feature.tracking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.database.tracking.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    private val trackingRepository: TrackingRepository,
) : ViewModel() {

    val hasToken: StateFlow<Boolean> = trackingRepository.observeToken()
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun saveToken(token: String) {
        if (token.isBlank()) return
        viewModelScope.launch { trackingRepository.saveToken(token.trim()) }
    }

    fun clearToken() {
        viewModelScope.launch { trackingRepository.clearToken() }
    }
}

@Composable
fun TrackingSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: TrackingSettingsViewModel = hiltViewModel(),
) {
    val hasToken by viewModel.hasToken.collectAsState()
    var tokenInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "AniList tracking", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Create an API client at anilist.co/settings/developer, open its authorize URL " +
                "(https://anilist.co/api/v2/oauth/authorize?client_id=YOUR_ID&response_type=token), " +
                "and paste the access token here. Linked shows then auto-update as you watch.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (hasToken) {
            Text(
                text = "✓ Token saved — tracking is active.",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp),
            )
            TextButton(onClick = viewModel::clearToken) { Text("Remove token") }
        } else {
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("AniList access token") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { viewModel.saveToken(tokenInput); tokenInput = "" }) { Text("Save") }
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}
