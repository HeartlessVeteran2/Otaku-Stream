package com.otakustream.core.database.mangayomi

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MangayomiSourceDao {
    @Query("SELECT * FROM mangayomi_sources")
    fun observeAll(): Flow<List<MangayomiSourceEntity>>

    @Query("SELECT * FROM mangayomi_sources")
    suspend fun getAll(): List<MangayomiSourceEntity>

    @Upsert
    suspend fun upsert(entity: MangayomiSourceEntity)

    @Query("UPDATE mangayomi_sources SET prefsJson = :prefsJson WHERE id = :id")
    suspend fun updatePrefs(id: Long, prefsJson: String?)

    @Query("DELETE FROM mangayomi_sources WHERE id = :id")
    suspend fun delete(id: Long)
}
