package com.otakustream.core.database.library

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryEntry>>
    fun observeInLibrary(mediaUrl: String): Flow<Boolean>
    suspend fun add(entry: LibraryEntry)
    suspend fun remove(mediaUrl: String)

    fun observeHistory(): Flow<List<WatchHistoryEntry>>
    suspend fun recordWatch(entry: WatchHistoryEntry)
    suspend fun clearHistory()
}

class LibraryRepositoryImpl @Inject constructor(
    private val libraryDao: LibraryDao,
    private val historyDao: WatchHistoryDao,
) : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryEntry>> = libraryDao.observeAll()
    override fun observeInLibrary(mediaUrl: String): Flow<Boolean> = libraryDao.observeInLibrary(mediaUrl)
    override suspend fun add(entry: LibraryEntry) = libraryDao.upsert(entry)
    override suspend fun remove(mediaUrl: String) = libraryDao.delete(mediaUrl)

    override fun observeHistory(): Flow<List<WatchHistoryEntry>> = historyDao.observeRecent()
    override suspend fun recordWatch(entry: WatchHistoryEntry) = historyDao.insert(entry)
    override suspend fun clearHistory() = historyDao.clear()
}
