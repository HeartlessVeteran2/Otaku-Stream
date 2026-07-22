package com.otakustream.core.database.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val tokenStore: EncryptedTokenStore,
) : TrackingRepository {
    override suspend fun getLink(mediaUrl: String): TrackerLink? = dao.getLink(mediaUrl)
    override fun observeLink(mediaUrl: String): Flow<TrackerLink?> = dao.observeLink(mediaUrl)
    override suspend fun saveLink(link: TrackerLink) = dao.upsertLink(link)
    override suspend fun removeLink(mediaUrl: String) = dao.deleteLink(mediaUrl)

    // The token now lives in the Keystore-backed EncryptedTokenStore. Reads/observes migrate any
    // pre-existing plaintext Room token into the encrypted store once, then wipe the Room row.
    override suspend fun getToken(): String? {
        ensureTokenMigrated()
        return tokenStore.current()
    }

    override fun observeToken(): Flow<String?> = flow {
        ensureTokenMigrated()
        emitAll(tokenStore.token)
    }

    override suspend fun saveToken(accessToken: String) {
        tokenStore.save(accessToken)
        // Never leave a plaintext copy behind in Room.
        runCatching { dao.clearToken() }
        migrated = true
    }

    override suspend fun clearToken() {
        tokenStore.clear()
        runCatching { dao.clearToken() }
        migrated = true
    }

    private val migrationMutex = Mutex()
    @Volatile
    private var migrated = false

    // One-time move of the legacy plaintext token (tracker_tokens row) into the encrypted store,
    // then clear the row. Runs at most once per process; safe if the row is already gone.
    private suspend fun ensureTokenMigrated() {
        if (migrated) return
        migrationMutex.withLock {
            if (migrated) return
            if (tokenStore.current() == null) {
                val legacy = runCatching { dao.getToken()?.accessToken }.getOrNull()
                if (!legacy.isNullOrEmpty()) tokenStore.save(legacy)
            }
            runCatching { dao.clearToken() }
            migrated = true
        }
    }
}
