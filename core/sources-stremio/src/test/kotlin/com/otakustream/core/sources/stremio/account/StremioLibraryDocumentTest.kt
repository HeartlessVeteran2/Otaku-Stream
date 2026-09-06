package com.otakustream.core.sources.stremio.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Guards the one function in the app that can destroy data on a server the app doesn't own.
//
// A Stremio datastorePut replaces the document outright, so a field this app doesn't round-trip is
// a field it deletes from the user's account — on their TV, their desktop and the web, as soon as
// sync propagates, with no undo. Everything below is checking that a push changes exactly what a
// push means to change and nothing else.
class StremioLibraryDocumentTest {

    // A realistic server document: a half-watched series with progress worth losing.
    private fun remoteSeries(removed: Boolean = false): String = JSONObject()
        .put("_id", "tt0388629")
        .put("name", "One Piece")
        .put("type", "series")
        .put("poster", "https://images.example/one-piece.jpg")
        .put("posterShape", "landscape")
        .put("background", "https://images.example/one-piece-bg.jpg")
        .put("logo", "https://images.example/one-piece-logo.png")
        .put("year", "1999")
        .put("removed", removed)
        .put("temp", false)
        .put("_ctime", "2019-04-02T10:15:00.000Z")
        .put("_mtime", "2026-08-30T21:40:12.000Z")
        .put(
            "state",
            JSONObject()
                .put("lastWatched", "2026-08-30T21:40:12.000Z")
                .put("timeWatched", 1_402_000)
                .put("timeOffset", 812_000)
                .put("overallTimeWatched", 91_004_000)
                .put("timesWatched", 74)
                .put("flaggedWatched", 1)
                .put("duration", 1_440_000)
                .put("video_id", "tt0388629:21:1086")
                .put("watched", "eJyzsbGxAQAAugC7")
                .put("noNotif", false)
                .put("season", 21)
                .put("episode", 1086),
        )
        .toString()

    private fun localCopyOf(remoteJson: String?, removed: Boolean = false) = StremioLibraryItem(
        id = "tt0388629",
        type = "series",
        // Deliberately poorer local metadata than the server's, to prove the push doesn't
        // overwrite the account's richer copy with what the local library happens to know.
        name = "One Piece",
        poster = null,
        removed = removed,
        remoteJson = remoteJson,
    )

    @Test
    fun `an item the account already has keeps its watch state`() {
        val document = libraryItemDocument(localCopyOf(remoteSeries()), NOW)!!
        val state = document.getJSONObject("state")

        // This is the bug the whole file exists for: the previous implementation attached a zeroed
        // state to every pushed item, so pressing "Push my saves" once reset resume position and
        // watched-episode marks on every show the user also had saved locally.
        assertEquals(812_000, state.getInt("timeOffset"))
        assertEquals(91_004_000, state.getInt("overallTimeWatched"))
        assertEquals(74, state.getInt("timesWatched"))
        assertEquals("eJyzsbGxAQAAugC7", state.getString("watched"))
        assertEquals(21, state.getInt("season"))
        assertEquals(1086, state.getInt("episode"))
        assertEquals("tt0388629:21:1086", state.getString("video_id"))
        assertEquals("2026-08-30T21:40:12.000Z", state.getString("lastWatched"))
    }

    @Test
    fun `an existing item keeps the metadata other Stremio clients filled in`() {
        val document = libraryItemDocument(localCopyOf(remoteSeries()), NOW)!!

        // The old builder hardcoded posterShape=poster and flattened these three to placeholders,
        // so a push downgraded artwork the user's other clients had populated.
        assertEquals("landscape", document.getString("posterShape"))
        assertEquals("https://images.example/one-piece-bg.jpg", document.getString("background"))
        assertEquals("https://images.example/one-piece-logo.png", document.getString("logo"))
        assertEquals("1999", document.getString("year"))
        // The local copy carries no poster at all; the server's must survive.
        assertEquals("https://images.example/one-piece.jpg", document.getString("poster"))
    }

    @Test
    fun `fields this app has never heard of survive a push`() {
        val withFutureField = JSONObject(remoteSeries())
            .put("someFieldAddedAfterThisAppWasWritten", "keep me")
            .toString()

        val document = libraryItemDocument(localCopyOf(withFutureField), NOW)!!

        // Round-tripping the raw document, rather than re-deriving one from parsed fields, is what
        // makes this hold — the app doesn't need to know a field exists to avoid deleting it.
        assertEquals("keep me", document.getString("someFieldAddedAfterThisAppWasWritten"))
    }

    @Test
    fun `an unchanged item is re-sent without winning a sync race`() {
        val document = libraryItemDocument(localCopyOf(remoteSeries()), NOW)!!

        // _mtime untouched: if another client edited this item while the push was in flight, a
        // gratuitously bumped mtime here would overwrite that edit with the copy read moments ago.
        assertEquals("2026-08-30T21:40:12.000Z", document.getString("_mtime"))
        assertEquals("2019-04-02T10:15:00.000Z", document.getString("_ctime"))
    }

    @Test
    fun `un-removing an item does bump mtime`() {
        // Remote says removed, local says saved — the one thing a push is genuinely asking to
        // change, so it has to win last-write-wins.
        val document = libraryItemDocument(localCopyOf(remoteSeries(removed = true), removed = false), NOW)!!

        assertEquals(false, document.getBoolean("removed"))
        assertEquals(NOW, document.getString("_mtime"))
        // Creation time is the server's, not ours, even when the row does change.
        assertEquals("2019-04-02T10:15:00.000Z", document.getString("_ctime"))
        // And the state still survives the one field that did change.
        assertEquals(1086, document.getJSONObject("state").getInt("episode"))
    }

    @Test
    fun `a genuinely new item gets a zeroed state`() {
        val document = libraryItemDocument(
            StremioLibraryItem(
                id = "tt9999999",
                type = "series",
                name = "Something New",
                poster = "https://images.example/new.jpg",
                removed = false,
                remoteJson = null,
            ),
            NOW,
        )!!

        assertEquals("tt9999999", document.getString("_id"))
        assertEquals(NOW, document.getString("_ctime"))
        assertEquals(NOW, document.getString("_mtime"))
        // Inventing a state is correct here and only here: there is no prior state to lose.
        val state = document.getJSONObject("state")
        assertEquals(0, state.getInt("timeOffset"))
        assertEquals(0, state.getInt("episode"))
        assertEquals("", state.getString("watched"))
    }

    @Test
    fun `an existing item whose document cannot be parsed is skipped, not overwritten`() {
        // A row this app can't read is a row it can't safely rewrite — dropping it leaves the
        // account exactly as it was, which is strictly better than replacing it with a guess.
        assertNull(libraryItemDocument(localCopyOf("{not json"), NOW))
    }

    @Test
    fun `the document a push sends is otherwise byte-for-byte the server's own`() {
        val remote = remoteSeries()
        val document = libraryItemDocument(localCopyOf(remote), NOW)!!

        // Belt-and-braces over the field-by-field assertions above: for an unchanged item the push
        // is a no-op, so every key the server sent comes back with the same value.
        val original = JSONObject(remote)
        for (key in original.keys()) {
            assertTrue("missing key $key", document.has(key))
            assertEquals("key $key changed", original.get(key).toString(), document.get(key).toString())
        }
    }

    private companion object {
        const val NOW = "2026-09-06T12:00:00.000Z"
    }
}
