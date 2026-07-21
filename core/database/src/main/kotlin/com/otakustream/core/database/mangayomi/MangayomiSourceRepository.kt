package com.otakustream.core.database.mangayomi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class MangayomiSourceRecord(
    val id: Long,
    val repoUrl: String,
    val sourceCodeUrl: String,
    val scriptContent: String,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val iconUrl: String?,
    val version: String,
    val isNsfw: Boolean,
    val itemType: Int,
    val sourceCodeLanguage: Int,
    val prefsJson: String? = null,
)

interface MangayomiSourceRepository {
    fun observeAll(): Flow<List<MangayomiSourceRecord>>
    suspend fun getAll(): List<MangayomiSourceRecord>
    suspend fun save(record: MangayomiSourceRecord)
    suspend fun updatePrefs(id: Long, prefsJson: String?)
    suspend fun delete(id: Long)
}

class MangayomiSourceRepositoryImpl @Inject constructor(
    private val dao: MangayomiSourceDao,
) : MangayomiSourceRepository {

    override fun observeAll(): Flow<List<MangayomiSourceRecord>> =
        dao.observeAll().map { list -> list.map { it.toRecord() } }

    override suspend fun getAll(): List<MangayomiSourceRecord> = dao.getAll().map { it.toRecord() }

    override suspend fun save(record: MangayomiSourceRecord) {
        dao.upsert(
            MangayomiSourceEntity(
                id = record.id,
                repoUrl = record.repoUrl,
                sourceCodeUrl = record.sourceCodeUrl,
                scriptContent = record.scriptContent,
                name = record.name,
                lang = record.lang,
                baseUrl = record.baseUrl,
                iconUrl = record.iconUrl,
                version = record.version,
                isNsfw = record.isNsfw,
                itemType = record.itemType,
                sourceCodeLanguage = record.sourceCodeLanguage,
                prefsJson = record.prefsJson,
            ),
        )
    }

    override suspend fun updatePrefs(id: Long, prefsJson: String?) = dao.updatePrefs(id, prefsJson)

    override suspend fun delete(id: Long) = dao.delete(id)
}

private fun MangayomiSourceEntity.toRecord() = MangayomiSourceRecord(
    id = id,
    repoUrl = repoUrl,
    sourceCodeUrl = sourceCodeUrl,
    scriptContent = scriptContent,
    name = name,
    lang = lang,
    baseUrl = baseUrl,
    iconUrl = iconUrl,
    version = version,
    isNsfw = isNsfw,
    itemType = itemType,
    sourceCodeLanguage = sourceCodeLanguage,
    prefsJson = prefsJson,
)
