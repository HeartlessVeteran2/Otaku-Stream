package com.otakustream.core.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.otakustream.core.player.TorrentPackFile

// The other episodes in the torrent being played.
//
// A season pack is one torrent holding a dozen files, and until this the app opened whichever was
// largest and gave no way to reach the rest — so a viewer who wanted episode 7 had to go back and
// hope a different magnet held only that episode.
//
// Its own sheet rather than a section in the track sheet, because a season is long enough to need
// scrolling of its own and the track sheet is a fixed-height Column. Picking a row starts a new
// playback: torrent:// is one identity per file, so the position, skip markers and history the
// viewer builds up belong to the episode rather than to the pack.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackFileSheet(
    files: List<TorrentPackFile>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(text = "In this torrent", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${files.size} playable files. Picking one keeps your place in the others.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            // selectableGroup for the same reason the track rows have one: these are mutually
            // exclusive, and without it TalkBack knows each row is a radio button and nothing about
            // the set it belongs to — so it cannot say "7 of 12", which on this sheet is most of
            // what a viewer needs to hear.
            LazyColumn(modifier = Modifier.selectableGroup()) {
                items(files, key = { it.fileIndex }) { file ->
                    PackFileRow(file = file, onClick = { onSelect(file.fileIndex) })
                }
            }
        }
    }
}

@Composable
private fun PackFileRow(file: TorrentPackFile, onClick: () -> Unit) {
    Row(
        // The whole row is the target and the RadioButton takes no onClick of its own — one choice,
        // one tappable node, as the track rows already do.
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = file.isCurrent, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = file.isCurrent, onClick = null)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(text = file.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = readableSize(file.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Enough to tell a 350 MB episode from a 1.4 GB one, which is the only question a size answers here:
// whether this row is the episode or an extra that slipped past the filter.
//
// format() without an explicit locale, deliberately: this string is read by a person, so the decimal
// separator should be theirs. A German viewer should see "1,4 GB". ReadableSizeTest pins that in
// both directions, because the alternative reading — that the default locale is an oversight — leads
// to Locale.ROOT and a dot printed to everyone.
internal fun readableSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    // Not "0 MB": a file this small is not an episode, and rounding it to zero hides that.
    else -> "${bytes / 1024L} KB"
}
