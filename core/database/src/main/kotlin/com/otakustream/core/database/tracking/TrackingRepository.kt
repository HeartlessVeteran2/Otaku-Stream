package com.otakustream.core.database.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

interface TrackingRepository {
    suspend fun getLink(mediaUrl: String): TrackerLink?
    fun observeLink(mediaUrl: String): Flow<TrackerLink?>
    suspend fun saveLink(link: TrackerLink)
    suspend fun removeLink(mediaUrl: String)

    suspend fun getToken(): String?
    fun observeToken(): Flow<String?>
    suspend fun saveToken(accessToken: String)
    suspend fun clearToken()
}

class TrackingRepositoryImpl @Inject constructor(
    private val dao: TrackingDao,
) : TrackingRepository {
    override suspend fun getLink(mediaUrl: String): TrackerLink? = dao.getLink(mediaUrl)
    override fun observeLink(mediaUrl: String): Flow<TrackerLink?> = dao.observeLink(mediaUrl)
    override suspend fun saveLink(link: TrackerLink) = dao.upsertLink(link)
    override suspend fun removeLink(mediaUrl: String) = dao.deleteLink(mediaUrl)

    override suspend fun getToken(): String? = dao.getToken()?.accessToken
    override fun observeToken(): Flow<String?> = dao.observeToken().map { it?.accessToken }
    override suspend fun saveToken(accessToken: String) = dao.upsertToken(TrackerToken(accessToken = accessToken))
    override suspend fun clearToken() = dao.clearToken()
}
