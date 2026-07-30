package com.otakustream.feature.tracking

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
    private val authState: AniListAuthState,
    private val aniListClient: AniListClient,
) : ViewModel() {

    val hasToken: StateFlow<Boolean> = trackingRepository.observeToken()
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // One-shot "you just signed in" confirmation, flipped when an OAuth redirect lands.
    private val _justSignedIn = MutableStateFlow(false)
    val justSignedIn: StateFlow<Boolean> = _justSignedIn.asStateFlow()

    // Set when a redirect is rejected, so the screen can say so instead of silently doing nothing.
    private val _signInRejected = MutableStateFlow(false)
    val signInRejected: StateFlow<Boolean> = _signInRejected.asStateFlow()

    // Called for the URL the app was opened with. Two things have to hold before a token is stored,
    // and neither did before.
    fun onOAuthToken(token: String, state: String?) {
        if (token.isBlank()) return
        // 1. It has to be the answer to a sign-in this app started. Any app or web page can fire
        //    otakustream://anilist-auth#access_token=... at this app; without the nonce the app
        //    would store an attacker's token and start writing the user's watch history into the
        //    attacker's AniList account, where they can read it. Check without consuming it so a
        //    transient validation or persistence failure can be retried.
        if (!authState.matches(state)) {
            _signInRejected.value = true
            return
        }
        viewModelScope.launch {
            // 2. It has to be a token AniList actually honours. The nonce proves the redirect
            //    belongs to our sign-in; it says nothing about whether the token in it works. Asking
            //    who the token belongs to before storing it turns "signed in" into a statement the
            //    app has checked, rather than one it is repeating back from a URL.
            val valid = runCatching { aniListClient.fetchViewer(token.trim()) }.isSuccess
            if (!valid) {
                _signInRejected.value = true
                return@launch
            }
            trackingRepository.saveToken(token.trim())
            // Retire the nonce only after validation and persistence have both succeeded.
            authState.consume(state)
            _signInRejected.value = false
            _justSignedIn.value = true
        }
    }

    // A fresh nonce per attempt, minted at the moment the user taps sign in.
    fun authorizeUrl(): String = AniListAuth.authorizeUrl(authState.begin())

    fun onRejectionShown() { _signInRejected.value = false }

    fun clearToken() {
        _justSignedIn.value = false
        viewModelScope.launch { trackingRepository.clearToken() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    pendingOAuthToken: String? = null,
    pendingOAuthState: String? = null,
    onPendingOAuthTokenConsumed: () -> Unit = {},
    viewModel: TrackingSettingsViewModel = hiltViewModel(),
) {
    val hasToken by viewModel.hasToken.collectAsState()
    val signInRejected by viewModel.signInRejected.collectAsState()
    val context = LocalContext.current
    var browserMissing by remember { mutableStateOf(false) }

    // A completed sign-in redirect lands here with the token still pending — persist it once.
    LaunchedEffect(pendingOAuthToken) {
        pendingOAuthToken?.let { token ->
            viewModel.onOAuthToken(token, pendingOAuthState)
            onPendingOAuthTokenConsumed()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("AniList tracking") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { scaffoldPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(scaffoldPadding).padding(16.dp)) {
        Text(
            text = "Sign in to sync your watch progress automatically.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // A redirect that failed either check. Worth saying out loud rather than leaving the user
        // looking at an unchanged screen: the honest cases (they took too long, or tapped an old
        // link) and the hostile one (someone else's redirect) look identical from here, and both
        // are fixed by starting sign-in again from this screen.
        if (signInRejected) {
            Text(
                text = "That sign-in couldn't be verified, so it wasn't used. " +
                    "Tap Sign in with AniList to start again.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        if (hasToken) {
            Text(
                text = "✓ Signed in — tracking is active.",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp),
            )
            TextButton(onClick = viewModel::clearToken) { Text("Sign out") }
        } else if (AniListAuth.isConfigured) {
            Button(
                onClick = {
                    // Some devices (TV boxes, stripped emulators) have no browser at all.
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.authorizeUrl())))
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
}
