package com.otakustream.core.database.skip

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SkipSegmentDao {
    @Query("SELECT * FROM skip_segments WHERE mediaUrl = :mediaUrl")
    fun observeForMedia(mediaUrl: String): Flow<List<SkipSegmentEntity>>

    @Insert
    suspend fun insert(entity: SkipSegmentEntity): Long

    @Query("DELETE FROM skip_segments WHERE id = :id")
    suspend fun delete(id: Long)
}
