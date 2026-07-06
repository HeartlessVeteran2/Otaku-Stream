package com.otakustream.core.database.scripted

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class ScriptedSourceRecord(
    val scriptUrl: String,
    val scriptContent: String,
    val name: String,
    val lang: String,
    val version: Int,
)

interface ScriptedSourceRepository {
    fun observeAll(): Flow<List<ScriptedSourceRecord>>
    suspend fun getAll(): List<ScriptedSourceRecord>
    suspend fun save(record: ScriptedSourceRecord)
    suspend fun delete(scriptUrl: String)
}

class ScriptedSourceRepositoryImpl @Inject constructor(
    private val dao: ScriptedSourceDao,
) : ScriptedSourceRepository {

    override fun observeAll(): Flow<List<ScriptedSourceRecord>> =
        dao.observeAll().map { list -> list.map { it.toRecord() } }

    override suspend fun getAll(): List<ScriptedSourceRecord> = dao.getAll().map { it.toRecord() }

    override suspend fun save(record: ScriptedSourceRecord) {
        dao.upsert(
            ScriptedSourceEntity(
                scriptUrl = record.scriptUrl,
                scriptContent = record.scriptContent,
                name = record.name,
                lang = record.lang,
                version = record.version,
            ),
        )
    }

    override suspend fun delete(scriptUrl: String) = dao.delete(scriptUrl)
}

private fun ScriptedSourceEntity.toRecord() = ScriptedSourceRecord(
    scriptUrl = scriptUrl,
    scriptContent = scriptContent,
    name = name,
    lang = lang,
    version = version,
)
