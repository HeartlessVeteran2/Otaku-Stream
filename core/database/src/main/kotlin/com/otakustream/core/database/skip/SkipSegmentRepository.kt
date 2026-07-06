package com.otakustream.core.database.skip

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

interface SkipSegmentRepository {
    fun observeForMedia(mediaUrl: String): Flow<List<SkipSegment>>
    suspend fun insert(mediaUrl: String, startMs: Long, endMs: Long, type: SkipSegmentType): Long
    suspend fun delete(id: Long)
}

class SkipSegmentRepositoryImpl @Inject constructor(
    private val dao: SkipSegmentDao,
) : SkipSegmentRepository {

    override fun observeForMedia(mediaUrl: String): Flow<List<SkipSegment>> =
        dao.observeForMedia(mediaUrl).map { entities -> entities.map { it.toDomain() } }

    override suspend fun insert(mediaUrl: String, startMs: Long, endMs: Long, type: SkipSegmentType): Long =
        dao.insert(SkipSegmentEntity(mediaUrl = mediaUrl, startMs = startMs, endMs = endMs, type = type.name))

    override suspend fun delete(id: Long) = dao.delete(id)
}
