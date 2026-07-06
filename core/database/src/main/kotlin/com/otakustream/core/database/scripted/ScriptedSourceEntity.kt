package com.otakustream.core.database.scripted

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scripted_sources")
data class ScriptedSourceEntity(
    @PrimaryKey val scriptUrl: String,
    val scriptContent: String,
    val name: String,
    val lang: String,
    val version: Int,
)
