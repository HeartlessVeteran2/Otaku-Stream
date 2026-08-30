package com.otakustream.core.database.download

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

// The display metadata for downloads. State lives in Media3's index, not here — see DownloadEntry.
interface DownloadRepository {
    fun observeAll(): Flow<List<DownloadEntry>>
    fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntry>>
    suspend fun entryForEpisode(episodeUrl: String): DownloadEntry?
    suspend fun remember(entry: DownloadEntry)
    suspend fun forget(videoUrl: String)
}

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val dao: DownloadDao,
) : DownloadRepository {
    override fun observeAll(): Flow<List<DownloadEntry>> = dao.observeAll()
    override fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntry>> = dao.observeForMedia(mediaUrl)
    override suspend fun entryForEpisode(episodeUrl: String): DownloadEntry? = dao.entryForEpisode(episodeUrl)
    override suspend fun remember(entry: DownloadEntry) = dao.upsert(entry)
    override suspend fun forget(videoUrl: String) = dao.delete(videoUrl)
}
