package com.otakustream.core.database.library

import androidx.room.withTransaction
import com.otakustream.core.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryEntry>>
    fun observeInLibrary(mediaUrl: String): Flow<Boolean>
    fun observeStatus(mediaUrl: String): Flow<String?>
    suspend fun add(entry: LibraryEntry)
    suspend fun remove(mediaUrl: String)
    suspend fun setStatus(mediaUrl: String, status: String)

    fun observeHistory(): Flow<List<WatchHistoryEntry>>
    fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>>
    suspend fun recordWatch(entry: WatchHistoryEntry)
    suspend fun lastTitleFor(mediaUrl: String): String?
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
    override suspend fun add(entry: LibraryEntry) = libraryDao.upsert(entry)
    override suspend fun remove(mediaUrl: String) = libraryDao.delete(mediaUrl)
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

    override suspend fun clearHistory() = historyDao.clear()
}
