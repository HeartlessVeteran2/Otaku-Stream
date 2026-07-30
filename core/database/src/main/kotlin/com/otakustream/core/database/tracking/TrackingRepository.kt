package com.otakustream.core.database.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

interface TrackingRepository {
    // season defaults to the whole-series sentinel, so existing callers that don't know or care
    // about seasons behave exactly as before. Both of these resolve an exact-season link first and
    // fall back to the whole-series one.
    suspend fun getLink(mediaUrl: String, season: Int = TRACKER_SEASON_WHOLE_SERIES): TrackerLink?
    fun observeLink(mediaUrl: String, season: Int = TRACKER_SEASON_WHOLE_SERIES): Flow<TrackerLink?>
    suspend fun getLinkByTrackerId(trackerMediaId: Long): TrackerLink?
    suspend fun saveLink(link: TrackerLink)
    suspend fun removeLink(mediaUrl: String, season: Int = TRACKER_SEASON_WHOLE_SERIES)

    suspend fun getToken(): String?
    fun observeToken(): Flow<String?>
    suspend fun saveToken(accessToken: String)
    suspend fun clearToken()
}

class TrackingRepositoryImpl @Inject constructor(
    private val dao: TrackingDao,
    private val tokenStore: EncryptedTokenStore,
    // Only ever used to VACUUM after the legacy-token migration. Provider rather than the database
    // itself so this repository does not force the database open earlier than it otherwise would.
    private val database: javax.inject.Provider<com.otakustream.core.database.AppDatabase>,
) : TrackingRepository {
    override suspend fun getLink(mediaUrl: String, season: Int): TrackerLink? = dao.getLink(mediaUrl, season)
    override fun observeLink(mediaUrl: String, season: Int): Flow<TrackerLink?> = dao.observeLink(mediaUrl, season)
    override suspend fun getLinkByTrackerId(trackerMediaId: Long): TrackerLink? =
        dao.getLinkByTrackerId(trackerMediaId)
    override suspend fun saveLink(link: TrackerLink) = dao.upsertLink(link)
    override suspend fun removeLink(mediaUrl: String, season: Int) = dao.deleteLink(mediaUrl, season)

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
            val legacy = runCatching { dao.getToken()?.accessToken }.getOrNull()
            if (tokenStore.current() == null && !legacy.isNullOrEmpty()) tokenStore.save(legacy)
            runCatching { dao.clearToken() }
            // DELETE marks the page free; it does not erase it. Until something else happens to
            // reuse that page, the plaintext bearer token is still sitting in the database file —
            // readable by anything that gets hold of the file, which is exactly the exposure moving
            // the token to the encrypted store was meant to end. VACUUM rewrites the file from the
            // live pages only, so the free page and its contents are gone.
            //
            // Conditional on a token having actually been there. VACUUM rewrites the entire
            // database, and on a fresh install — the overwhelmingly common case, where there was
            // never a legacy row — that would be a pointless full-file rewrite on the first read of
            // the token.
            if (!legacy.isNullOrEmpty()) {
                runCatching { database.get().openHelper.writableDatabase.execSQL("VACUUM") }
            }
            migrated = true
        }
    }
}
