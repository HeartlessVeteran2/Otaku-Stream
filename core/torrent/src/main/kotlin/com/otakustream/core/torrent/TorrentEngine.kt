package com.otakustream.core.torrent

import android.util.Log
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.settings_pack
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TorrentEngine"

// Owns the single libtorrent session for the process.
//
// Nothing here runs until something actually asks for a torrent: the session is created on first
// start() and the native library is only touched when isAvailable is first read. Starting a DHT
// session at app startup would undo the cold-start work, and most sessions never play a torrent at
// all.
@Singleton
class TorrentEngine @Inject constructor() {

    private val lock = Any()

    @Volatile
    private var session: SessionManager? = null

    // Whether the native library is usable on this device at all.
    //
    // Only arm64 is bundled (see the module's abiFilters), so on a 32-bit device the class fails to
    // initialize. That surfaces as UnsatisfiedLinkError the first time, and then as
    // NoClassDefFoundError on every later access because the class is left in an erroneous state —
    // which is exactly why this is computed once and cached. Callers use it to disable the feature
    // with an explanation instead of letting the app die.
    val isAvailable: Boolean by lazy {
        try {
            // Touching a native static is the cheapest way to force the library load and find out.
            org.libtorrent4j.LibTorrent.version()
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Torrent engine unavailable: native library missing for this ABI", e)
            false
        } catch (e: NoClassDefFoundError) {
            Log.w(TAG, "Torrent engine unavailable: libtorrent4j failed to initialize", e)
            false
        } catch (e: ExceptionInInitializerError) {
            Log.w(TAG, "Torrent engine unavailable: libtorrent4j failed to initialize", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Torrent engine unavailable", e)
            false
        }
    }

    val isRunning: Boolean
        get() = session?.isRunning == true

    // Idempotent: returns the running session, starting one if needed, or null when the engine
    // isn't available on this device. Synchronized because playback resolution and the (later)
    // service lifecycle can both reach this concurrently, and two sessions binding the same ports
    // would be a mess to diagnose.
    fun ensureStarted(): SessionManager? {
        if (!isAvailable) return null
        session?.let { if (it.isRunning) return it }
        synchronized(lock) {
            session?.let { if (it.isRunning) return it }
            return try {
                SessionManager().apply {
                    start()
                    applySettings(this)
                    session = this
                    Log.i(TAG, "Torrent session started")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start torrent session", e)
                null
            }
        }
    }

    // Stops the session and releases its sockets. Playback teardown calls this once nothing is being
    // read, so an idle app isn't holding a DHT node open on the user's connection.
    fun stop() {
        synchronized(lock) {
            val current = session ?: return
            session = null
            try {
                current.stop()
                Log.i(TAG, "Torrent session stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop torrent session cleanly", e)
            }
        }
    }

    // Streaming-shaped defaults, not download-shaped ones. Deliberately conservative on upload
    // because this app never seeds: uploading is bandwidth the user didn't ask to spend, and on a
    // phone it also costs battery. See the project's stated policy in the settings screen.
    private fun applySettings(manager: SessionManager) {
        val settings = SettingsPack()
            // Announce as a mainstream client; some trackers reject or throttle unknown peer ids.
            .setString(settings_pack.string_types.user_agent.swigValue(), USER_AGENT)
            // No seeding: cap upload to what the protocol needs for tit-for-tat to work at all.
            .setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), UPLOAD_LIMIT_BYTES_PER_SEC)
            .setInteger(settings_pack.int_types.active_seeds.swigValue(), 0)
            // Unmetered download; the storage quota is what bounds it, not a rate limit.
            .setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0)
            .setInteger(settings_pack.int_types.connections_limit.swigValue(), CONNECTION_LIMIT)
        runCatching { manager.applySettings(settings) }
            .onFailure { Log.w(TAG, "Failed to apply torrent session settings", it) }
    }

    private companion object {
        const val USER_AGENT = "libtorrent/2.1.0"

        // 64 KiB/s. Enough to stay a participating peer rather than being choked outright, low
        // enough that it can't compete with the download this is meant to be serving.
        const val UPLOAD_LIMIT_BYTES_PER_SEC = 64 * 1024

        // Plenty of peers for one streaming file, without opening hundreds of sockets on a phone.
        const val CONNECTION_LIMIT = 100
    }
}
