package com.otakustream.app

import com.otakustream.core.sources.api.PendingPlayback
import com.otakustream.core.sources.api.Video
import com.otakustream.core.torrent.MagnetLinks

// Turns a magnet link into the torrent:// url the player understands, stashing what can't ride in
// the url. Returns null if the link doesn't parse — no infohash, or a scheme that isn't magnet.
//
// One copy, called from both places a magnet can enter playback: an ACTION_VIEW intent from another
// app (MainActivity, behind a confirmation prompt) and the paste-a-link dialog (PlayScreen, where
// the paste *is* the confirmation). They had drifted into two identical private functions; the
// stash is a contract with the player, so a change made to one and not the other would show up as
// missing trackers or an unrecorded watch on one path only.
//
// The trackers can't be encoded into the url: the url is the identity that resume position and
// watch history are keyed on, and it has to stay the same for a given torrent however the link that
// introduced it was written. So they travel through the same out-of-band channel the catalog flow
// uses.
//
// historyHandled = false because nothing upstream recorded this play — there is no view model
// behind an "Open with" or a pasted link, so the player records it itself.
//
// Provenance is USER on both paths. MainActivity only reaches here after the user accepted the
// magnet prompt, and PlayScreen only after they pasted the link themselves — either way this is
// the user's own choice rather than a source's. The url is torrent:// regardless, but saying so
// keeps provenance honest instead of relying on the scheme happening to be allowed.
internal fun magnetToPlayableUrl(magnet: String): String? {
    val link = MagnetLinks.parse(magnet) ?: return null
    val url = MagnetLinks.toTorrentUrl(link) ?: return null
    PendingPlayback.stash(
        video = Video(url = url, quality = link.displayName ?: "torrent", trackers = link.trackers),
        historyHandled = false,
        provenance = PendingPlayback.Provenance.USER,
    )
    return url
}
