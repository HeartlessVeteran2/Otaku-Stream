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
// behind an "Open with" or a pasted link, so the player records it itself. The magnet's `dn` goes
// with it as directPlayTitle: the url is torrent://<hash>/0, so left to derive a name from that the
// player would file every torrent in Continue Watching as "0" — its last path segment.
//
// Provenance is USER on both paths. MainActivity only reaches here after the user accepted the
// magnet prompt, and PlayScreen only after they pasted the link themselves — either way this is
// the user's own choice rather than a source's. The url is torrent:// regardless, but saying so
// keeps provenance honest instead of relying on the scheme happening to be allowed.
//
// Named for the stash, not the conversion: this does not just compute a url, it replaces the
// process-wide pending-playback hand-off. Calling it to *test* a link would silently discard
// whatever another screen had queued.
internal fun prepareMagnetPlayback(magnet: String): String? {
    val link = MagnetLinks.parse(magnet) ?: return null
    val url = MagnetLinks.toTorrentUrl(link) ?: return null
    // `dn` is optional in a magnet, and plenty of real ones omit it. Falling through to the player's
    // URL-derived name would file those as "0" — the last path segment of torrent://<hash>/0 — so
    // every unnamed torrent would look like the same entry in Continue Watching. A short prefix of
    // the info hash is not pretty, but it is stable and distinct, which is what the row needs to be.
    //
    // A `dn` that is present has already been stripped of control characters and length-bounded by
    // MagnetLinks.parse, which is where that belongs: every caller of it is handling a link that
    // came from outside the app.
    val title = link.displayName ?: "Torrent ${link.infoHash.take(SHORT_HASH_LENGTH).uppercase()}"
    PendingPlayback.stash(
        video = Video(url = url, quality = link.displayName ?: "torrent", trackers = link.trackers),
        historyHandled = false,
        provenance = PendingPlayback.Provenance.USER,
        directPlayTitle = title,
    )
    return url
}

// Enough hex to tell two torrents apart at a glance without filling the row with the full 40.
private const val SHORT_HASH_LENGTH = 8
