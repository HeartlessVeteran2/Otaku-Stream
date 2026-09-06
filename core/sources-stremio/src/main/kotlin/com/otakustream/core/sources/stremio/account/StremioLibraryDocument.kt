package com.otakustream.core.sources.stremio.account

import org.json.JSONObject

// Builds the `libraryItem` document that "Push my saves" sends to Stremio's datastorePut.
//
// Split out of StremioAccountClient and kept pure so it can be unit-tested, because this is the
// most destructive function in the app. datastorePut REPLACES the document — it does not merge —
// and every push stamps a fresh `_mtime`, so whatever this returns wins last-write-wins against the
// user's TV, desktop and web clients the moment sync propagates. Every field this function invents
// rather than carries across is a field it silently destroys on the user's real Stremio account.
//
// What that used to cost: the previous version built a complete document from the four fields the
// local library happens to know (id, type, name, poster) and unconditionally attached a zeroed
// `state`. `state` is where Stremio keeps the resume position, the watched-episode bitfield, the
// season/episode pointer and the watch counts. Pressing "Push my saves" once with an existing
// Stremio account therefore reset progress on every show the user also had saved locally —
// everywhere, at once, with no undo, under a toast that said "Pushed N titles to your Stremio
// library." The same push also flattened `posterShape`, `background`, `logo` and `year` to
// placeholders for items whose other clients had filled them in.
//
// So there are two shapes now, and only one of them invents anything:
//
//  - The item already exists in the account (`remoteJson` present): start from the server's own
//    document and change only `removed`, which is the single thing a push is actually asking to
//    change. `state` returns untouched, and so does every field Stremio has that this app has never
//    heard of — round-tripping the raw document means a future Stremio field survives a push from a
//    version of this app written before that field existed.
//  - The item is genuinely new (`remoteJson` null): build the minimal-but-valid document, with a
//    zeroed state the receiver fills in on the next sync. Inventing a state is correct here,
//    because there is no prior state to lose.
//
// Returns null when an existing item's document can't be parsed. Skipping that item is the safe
// direction: a row this app cannot read is a row it cannot safely rewrite.
internal fun libraryItemDocument(item: StremioLibraryItem, now: String): JSONObject? {
    val remoteJson = item.remoteJson ?: return newLibraryItemDocument(item, now)
    val document = runCatching { JSONObject(remoteJson) }.getOrNull() ?: return null
    // Only a genuine change earns a new `_mtime`. Re-sending an unchanged row as-is keeps it from
    // winning a sync race it has nothing to say in — if another client edited this item while the
    // push was in flight, a gratuitously bumped mtime here would overwrite that edit with the copy
    // read moments earlier.
    if (document.optBoolean("removed", false) == item.removed) return document
    return document.put("removed", item.removed).put("_mtime", now)
}

private fun newLibraryItemDocument(item: StremioLibraryItem, now: String): JSONObject =
    JSONObject()
        .put("_id", item.id)
        .put("name", item.name)
        .put("type", item.type)
        .put("poster", item.poster ?: "")
        .put("posterShape", "poster")
        .put("background", JSONObject.NULL)
        .put("logo", JSONObject.NULL)
        .put("year", "")
        .put("removed", item.removed)
        .put("temp", false)
        .put("_ctime", now)
        .put("_mtime", now)
        .put("state", newLibraryState())

// The zero state for an item Stremio has never seen. Reached only from newLibraryItemDocument —
// there is deliberately no path from an existing item to this function.
private fun newLibraryState(): JSONObject = JSONObject()
    .put("lastWatched", "")
    .put("timeWatched", 0)
    .put("timeOffset", 0)
    .put("overallTimeWatched", 0)
    .put("timesWatched", 0)
    .put("flaggedWatched", 0)
    .put("duration", 0)
    .put("video_id", "")
    .put("watched", "")
    .put("noNotif", false)
    .put("season", 0)
    .put("episode", 0)
