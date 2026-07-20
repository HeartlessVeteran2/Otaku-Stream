package com.otakustream.feature.tracking

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.database.tracking.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // One-shot "you just signed in" confirmation, flipped when an OAuth redirect lands.
    private val _justSignedIn = MutableStateFlow(false)
    val justSignedIn: StateFlow<Boolean> = _justSignedIn.asStateFlow()

    fun onOAuthToken(token: String) {
        if (token.isBlank()) return
        viewModelScope.launch {
            trackingRepository.saveToken(token.trim())
            _justSignedIn.value = true
        }
    }

    fun clearToken() {
        _justSignedIn.value = false
        viewModelScope.launch { trackingRepository.clearToken() }
    }
}

@Composable
fun TrackingSettingsScreen(
    modifier: Modifier = Modifier,
    pendingOAuthToken: String? = null,
    onPendingOAuthTokenConsumed: () -> Unit = {},
    viewModel: TrackingSettingsViewModel = hiltViewModel(),
) {
    val hasToken by viewModel.hasToken.collectAsState()
    val justSignedIn by viewModel.justSignedIn.collectAsState()
    val context = LocalContext.current
    var browserMissing by remember { mutableStateOf(false) }

    // A completed sign-in redirect lands here with the token still pending — persist it once.
    LaunchedEffect(pendingOAuthToken) {
        pendingOAuthToken?.let { token ->
            viewModel.onOAuthToken(token)
            onPendingOAuthTokenConsumed()
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "AniList", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Sign in to sync your watch progress automatically.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (hasToken) {
            Text(
                text = if (justSignedIn) "Signed in! Tracking is active." else "✓ Signed in — tracking is active.",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp),
            )
            TextButton(onClick = viewModel::clearToken) { Text("Sign out") }
        } else if (AniListAuth.isConfigured) {
            Button(
                onClick = {
                    // Some devices (TV boxes, stripped emulators) have no browser at all.
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AniListAuth.authorizeUrl())))
                    } catch (_: ActivityNotFoundException) {
                        browserMissing = true
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Sign in with AniList")
            }
            Text(
                text = "You'll approve access in your browser and come right back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (browserMissing) {
                Text(
                    text = "No browser found to open the sign-in page.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            Text(
                text = "AniList sign-in isn't set up in this build.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
