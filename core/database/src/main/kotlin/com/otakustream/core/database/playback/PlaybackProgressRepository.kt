package com.otakustream.core.database.playback

import javax.inject.Inject

interface PlaybackProgressRepository {
    suspend fun getSavedPositionMs(mediaUrl: String): Long?
    suspend fun save(mediaUrl: String, positionMs: Long, durationMs: Long)
    suspend fun clear(mediaUrl: String)
}

class PlaybackProgressRepositoryImpl @Inject constructor(
    private val dao: PlaybackProgressDao,
) : PlaybackProgressRepository {

    override suspend fun getSavedPositionMs(mediaUrl: String): Long? = dao.get(mediaUrl)?.positionMs

    override suspend fun save(mediaUrl: String, positionMs: Long, durationMs: Long) {
        dao.upsert(
            PlaybackProgressEntity(
                mediaUrl = mediaUrl,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun clear(mediaUrl: String) {
        dao.delete(mediaUrl)
    }
}
