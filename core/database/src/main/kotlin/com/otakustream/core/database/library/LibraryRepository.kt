package com.otakustream.core.database.library

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
    suspend fun clearHistory()
}

class LibraryRepositoryImpl @Inject constructor(
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

    override suspend fun recordWatch(entry: WatchHistoryEntry) {
        historyDao.insert(entry)
        // Starting an episode of a saved-but-not-yet-started title promotes it to "Watching".
        // getStatus returns null when the title isn't in the library, so this is a no-op then.
        if (libraryDao.getStatus(entry.mediaUrl) == LIBRARY_STATUS_PLANNED) {
            libraryDao.setStatus(entry.mediaUrl, LIBRARY_STATUS_WATCHING)
        }
    }

    override suspend fun clearHistory() = historyDao.clear()
}
