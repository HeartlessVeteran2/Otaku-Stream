package com.otakustream.core.database.mangayomi

import androidx.room.withTransaction
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

    // Saves an extension without discarding the preferences the user set on it.
    //
    // save() is a whole-row upsert keyed on the extension's stable id, and an install builds its
    // record with prefsJson = null — so re-installing an extension silently wiped its custom
    // domain, preferred server, quality and language. That is reachable without meaning to: a
    // bootstrap failure leaves a database row with no registered source, and opening the extensions
    // screen before SourceBootstrapper finishes shows everything as "Install". Worse, the damage is
    // invisible at the time, because registerDynamic sees the id already present and closes the new
    // instance — so the old runtime keeps serving with the old preferences and the loss only
    // surfaces at the next cold start.
    //
    // One transaction, because reading the old preferences and writing the new row are otherwise
    // two suspending calls with a window between them.
    suspend fun saveKeepingPrefs(record: MangayomiSourceRecord)
    suspend fun updatePrefs(id: Long, prefsJson: String?)
    suspend fun delete(id: Long)
}

class MangayomiSourceRepositoryImpl @Inject constructor(
    private val database: com.otakustream.core.database.AppDatabase,
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

    override suspend fun saveKeepingPrefs(record: MangayomiSourceRecord) = database.withTransaction {
        save(record.copy(prefsJson = dao.getPrefs(record.id) ?: record.prefsJson))
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
