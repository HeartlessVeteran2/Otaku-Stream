package com.otakustream.app.navigation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.otakustream.app.R
import com.otakustream.core.sources.api.PendingPlayback
import com.otakustream.core.sources.api.Video
import com.otakustream.core.torrent.MagnetLinks
import com.otakustream.feature.sources.ui.HomeContent

// The app's "front door": a content-forward home in the Stremio mold. Quick actions up top
// (open a local file, paste a link, browse add-ons), then Continue Watching and Popular/Latest
// rails fanned out across the installed sources.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    onPlayVideo: (String) -> Unit,
    onBrowseAddons: () -> Unit,
    onMediaClick: (sourceId: Long, mediaUrl: String, title: String) -> Unit,
    onAniListClick: (mediaId: Long, title: String) -> Unit,
    onAniListSearch: () -> Unit,
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.brand_logo),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Otaku Stream")
                    }
                },
                actions = {
                    IconButton(onClick = onAniListSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search AniList")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedButton(onClick = { filePicker.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open file")
                }
                OutlinedButton(onClick = { showUrlDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play link")
                }
                OutlinedButton(onClick = onBrowseAddons, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Extension, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add-ons")
                }
            }

            HomeContent(
                onMediaClick = onMediaClick,
                onPlayDirect = onPlayVideo,
                onBrowseAddons = onBrowseAddons,
                onAniListClick = onAniListClick,
            )
        }
    }

    if (showUrlDialog) {
        PasteUrlDialog(
            onDismiss = { showUrlDialog = false },
            onPlay = { url ->
                showUrlDialog = false
                // A magnet has to become a torrent:// url before the player sees it; anything else
                // goes straight through. An unparseable magnet yields null and simply does nothing,
                // which is the same outcome the disabled button gave before.
                val playable = if (url.startsWith("magnet:", ignoreCase = true)) {
                    magnetToPlayableUrl(url)
                } else {
                    url
                }
                playable?.let(onPlayVideo)
            },
        )
    }
}

@Composable
private fun PasteUrlDialog(onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val trimmed = url.trim()
    // Only allow schemes the player can actually open, so a typo can't navigate into a dead player.
    //
    // magnet: is one of them. The manifest already accepts magnet links from other apps, and the
    // torrent player handles them — but this dialog rejected the one thing a user is most likely to
    // have on their clipboard, so pasting a magnet left Play greyed out with nothing explaining why.
    val isMagnet = trimmed.startsWith("magnet:", ignoreCase = true)
    val isPlayable = isMagnet ||
        listOf("http://", "https://", "content://", "file://").any { trimmed.startsWith(it, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play from a link") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Paste a video link") },
                supportingText = { Text("Works with a direct video, a stream link, or a magnet link.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onPlay(trimmed) }, enabled = isPlayable) { Text("Play") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// A magnet link the user pasted themselves, turned into the torrent:// url the player understands
// plus the tracker stash that has to travel out of band — the url is the identity that resume
// position and watch history are keyed on, so it must not vary with how the link was written.
//
// No confirmation prompt, unlike the same conversion in MainActivity. That prompt exists because any
// web page can fire a magnet intent at the app and joining a swarm publishes the user's IP; a link
// they pasted into this dialog is already their deliberate choice, and asking twice for one action
// is noise. Provenance says so explicitly rather than leaning on the scheme being allowed anyway.
private fun magnetToPlayableUrl(magnet: String): String? {
    val link = MagnetLinks.parse(magnet) ?: return null
    val url = MagnetLinks.toTorrentUrl(link) ?: return null
    PendingPlayback.stash(
        video = Video(url = url, quality = link.displayName ?: "torrent", trackers = link.trackers),
        // Nothing upstream recorded this play, so the player records it itself.
        historyHandled = false,
        provenance = PendingPlayback.Provenance.USER,
    )
    return url
}
