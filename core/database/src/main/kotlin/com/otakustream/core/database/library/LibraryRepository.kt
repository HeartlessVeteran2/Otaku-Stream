package com.otakustream.core.database.library

import androidx.room.withTransaction
import com.otakustream.core.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryEntry>>
    fun observeInLibrary(mediaUrl: String): Flow<Boolean>
    fun observeStatus(mediaUrl: String): Flow<String?>
    // The only way to save a title, and deliberately the non-destructive one.
    //
    // There used to be an `add` alongside this that wrapped a whole-row @Upsert, and every caller
    // that reached for it did so from a stale "is this saved?" flag — so on a stale read it
    // replaced an existing row, resetting the user's watch status to Plan-to-watch and the entry's
    // added-at to now. Nothing needs replace semantics here; setStatus covers the one field that
    // legitimately changes. Leaving a destructive twin next to this one is how that bug comes back,
    // so it is gone.
    //
    // Returns whether the row was actually inserted, so a caller undoing a removal can tell
    // "put back" from "someone saved it again first".
    suspend fun addIfAbsent(entry: LibraryEntry): Boolean
    suspend fun remove(mediaUrl: String)

    // Deletes a row and hands back exactly what was deleted, in one transaction.
    //
    // Undo needs the two to be the same thing. Reading the entry and then deleting it are separate
    // suspending calls, so a save or a status change landing between them means the row that was
    // removed is not the row the snackbar is holding — and undoing then restores the older
    // snapshot, silently reverting the change that happened in between. Returns null when there
    // was nothing to delete.
    suspend fun removeAndReturn(mediaUrl: String): LibraryEntry?
    suspend fun setStatus(mediaUrl: String, status: String)

    fun observeHistory(): Flow<List<WatchHistoryEntry>>
    fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>>
    suspend fun recordWatch(entry: WatchHistoryEntry)
    suspend fun lastTitleFor(mediaUrl: String): String?
    // Deletes one history row and hands back exactly what was deleted, in one transaction — the
    // same shape removeAndReturn uses for the watchlist, and for the same reason: undo has to
    // restore the row that actually went, not a snapshot read beforehand that something else may
    // have changed in between.
    suspend fun removeHistoryEntryAndReturn(id: Long): WatchHistoryEntry?

    // Puts a deleted history row back. The implementation resets the auto-generated id to zero so
    // SQLite allocates a fresh one — Room does *not* ignore a non-zero id, it would reuse it — which
    // is why undo restores the entry rather than the id, and why the snackbar's action does not try
    // to preserve it.
    //
    // The row keeps its original watchedAtEpochMs, so it returns to where it was in the list rather
    // than jumping to the top. Restoring it with a fresh timestamp would claim the user had watched
    // it just now, which is false and reorders Continue Watching around a deletion they undid.
    suspend fun restoreHistoryEntry(entry: WatchHistoryEntry)

    suspend fun clearHistory()
}

class LibraryRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val libraryDao: LibraryDao,
    private val historyDao: WatchHistoryDao,
) : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryEntry>> = libraryDao.observeAll()
    override fun observeInLibrary(mediaUrl: String): Flow<Boolean> = libraryDao.observeInLibrary(mediaUrl)
    override fun observeStatus(mediaUrl: String): Flow<String?> = libraryDao.observeStatus(mediaUrl)
    override suspend fun addIfAbsent(entry: LibraryEntry): Boolean =
        libraryDao.insertIfAbsent(entry) != -1L
    override suspend fun remove(mediaUrl: String) = libraryDao.delete(mediaUrl)

    override suspend fun removeAndReturn(mediaUrl: String): LibraryEntry? = database.withTransaction {
        val entry = libraryDao.get(mediaUrl)
        libraryDao.delete(mediaUrl)
        entry
    }
    override suspend fun setStatus(mediaUrl: String, status: String) = libraryDao.setStatus(mediaUrl, status)

    override fun observeHistory(): Flow<List<WatchHistoryEntry>> = historyDao.observeRecent()
    override fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>> =
        historyDao.observeWatchedEpisodeUrls(mediaUrl)

    // One transaction, because this is a read-modify-write across two tables and it runs on every
    // episode start — including auto-play, which fires it while the user is still watching. Without
    // it, two starts interleaving between getStatus and setStatus both see PLANNED and both write
    // WATCHING (harmless), but a crash or a concurrent status change landing between the insert and
    // the update leaves history saying the title was watched while the library still calls it
    // planned. withTransaction rather than @Transaction: the two DAOs are separate, and a @Transaction
    // method only spans the DAO it is declared on.
    override suspend fun recordWatch(entry: WatchHistoryEntry) = database.withTransaction {
        historyDao.insert(entry)
        // Starting an episode of a saved-but-not-yet-started title promotes it to "Watching".
        // getStatus returns null when the title isn't in the library, so this is a no-op then.
        if (libraryDao.getStatus(entry.mediaUrl) == LIBRARY_STATUS_PLANNED) {
            libraryDao.setStatus(entry.mediaUrl, LIBRARY_STATUS_WATCHING)
        }
    }

    override suspend fun lastTitleFor(mediaUrl: String): String? = historyDao.lastTitleFor(mediaUrl)

    override suspend fun removeHistoryEntryAndReturn(id: Long): WatchHistoryEntry? =
        database.withTransaction {
            val entry = historyDao.getHistoryEntry(id)
            historyDao.deleteHistoryEntry(id)
            entry
        }

    override suspend fun restoreHistoryEntry(entry: WatchHistoryEntry) =
        historyDao.insert(entry.copy(id = 0))

    override suspend fun clearHistory() = historyDao.clear()
}
