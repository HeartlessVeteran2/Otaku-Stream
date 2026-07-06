package com.otakustream.core.database.scripted

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptedSourceDao {
    @Query("SELECT * FROM scripted_sources")
    fun observeAll(): Flow<List<ScriptedSourceEntity>>

    @Query("SELECT * FROM scripted_sources")
    suspend fun getAll(): List<ScriptedSourceEntity>

    @Upsert
    suspend fun upsert(entity: ScriptedSourceEntity)

    @Query("DELETE FROM scripted_sources WHERE scriptUrl = :scriptUrl")
    suspend fun delete(scriptUrl: String)
}
