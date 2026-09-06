package com.otakustream.feature.sources.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.otakustream.core.ui.BackTopBar
import com.otakustream.core.ui.ConfirmDialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.ui.PosterTile

// Sign in to a Stremio account and sync the library. Logged out: email/password. Logged in: your
// Stremio library (read-only here — it isn't tied to a specific installed add-on) plus a one-tap
// push of local saves up to the account.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StremioAccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StremioAccountViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            BackTopBar(title = "Stremio account", onBack = onBack)
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            uiState.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (uiState.isLoggedIn) {
                LoggedInContent(uiState, viewModel)
            } else {
                LoginForm(uiState, viewModel)
            }
        }
    }
}

@Composable
private fun LoginForm(uiState: StremioAccountUiState, viewModel: StremioAccountViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Sign in with your Stremio email and password to sync your library. Your password " +
                "isn't stored — only the session token Stremio returns.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; viewModel.consumeMessage() },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; viewModel.consumeMessage() },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.login(email, password) },
            enabled = !uiState.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Sign in")
            }
        }
    }
}

@Composable
private fun LoggedInContent(uiState: StremioAccountUiState, viewModel: StremioAccountViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                text = "Signed in as ${uiState.email ?: "your Stremio account"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            var confirmSignOut by remember { mutableStateOf(false) }
            if (confirmSignOut) {
                ConfirmDialog(
                    title = "Sign out of Stremio?",
                    body = "Your saved credentials are removed from this device and the library " +
                        "below stops loading. Your Stremio account is not changed.",
                    confirmLabel = "Sign out",
                    onConfirm = viewModel::logout,
                    onDismiss = { confirmSignOut = false },
                )
            }
            OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Button(onClick = viewModel::pushLocalLibrary, enabled = !uiState.isBusy) {
                Text("Push my saves")
            }
            OutlinedButton(onClick = viewModel::refreshLibrary, enabled = !uiState.isBusy) {
                Text("Refresh")
            }
        }

        Text(
            text = "Your Stremio library",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Text(
            text = "Synced from your Stremio account for reference. To watch a title, search it on the " +
                "Play or Catalog tab with your installed sources.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )

        when {
            uiState.isBusy && uiState.library.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.library.isEmpty() -> {
                Text(
                    text = "Nothing in your Stremio library yet, or it couldn't be loaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                ) {
                    items(uiState.library, key = { it.mediaUrl }) { item ->
                        // The shared tile, not a fourth hand-rolled one. This grid had drifted to a
                        // different corner radius, no border and a caption below the poster rather
                        // than over it, so the same saved library looked like a different app here.
                        //
                        PosterTile(
                            title = item.name,
                            coverUrl = item.poster,
                            // Genuinely not tappable, and now says so. An empty lambda would still
                            // attach clickable: the tile would ripple under a finger, do nothing,
                            // and be announced to TalkBack as activatable. This screen shows what
                            // your Stremio account holds, and nothing here knows which installed
                            // source could play it — as the copy below the grid says.
                            onClick = null,
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
