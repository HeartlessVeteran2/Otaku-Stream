package com.otakustream.app.navigation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// The app's "front door": a VLC-style launch screen. Open any local video via the system
// document picker, paste a direct URL, or jump to the Stremio-style add-on directory to install
// plugins that feed the catalog. All three funnel into the same player as the catalog flow does.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    onPlayVideo: (String) -> Unit,
    onBrowseAddons: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showUrlDialog by remember { mutableStateOf(false) }

    // OpenDocument (Storage Access Framework) rather than GetContent, so the read grant can be
    // made persistable — resume-from-position keys off the URI, so reopening the same file later
    // must still be able to read it.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        onPlayVideo(uri.toString())
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("OTAKU STREAM") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Button(
                onClick = { filePicker.launch(arrayOf("video/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Open local file")
            }
            Button(
                onClick = { showUrlDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Link, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Paste a URL")
            }
            OutlinedButton(
                onClick = onBrowseAddons,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Extension, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Browse add-ons")
            }
        }
    }

    if (showUrlDialog) {
        PasteUrlDialog(
            onDismiss = { showUrlDialog = false },
            onPlay = { url ->
                showUrlDialog = false
                onPlayVideo(url)
            },
        )
    }
}

@Composable
private fun PasteUrlDialog(onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play a URL") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Video URL (http, https, .m3u8, …)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onPlay(url.trim()) }, enabled = url.isNotBlank()) { Text("Play") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
