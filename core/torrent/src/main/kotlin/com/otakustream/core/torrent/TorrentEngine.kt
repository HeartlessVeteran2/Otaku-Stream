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
class TorrentEngine @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val settings: TorrentSettings,
    private val trackerStore: TorrentTrackerStore,
) {

    private val lock = Any()

    @Volatile
    private var session: SessionManager? = null

    // How many readers currently hold a file open. The session lives exactly as long as this is
    // above zero: once the last reader closes, the torrent is removed and the session shut down.
    //
    // Reference counting rather than "stop when the player stops" on purpose. The engine has no
    // visibility into player lifecycle, auto-play can hand one playback straight to the next, and
    // Media3 may open more than one DataSource for a single item. Counting readers is the only signal
    // that is actually true.
    private val openReaders = java.util.concurrent.atomic.AtomicInteger(0)

    // The reader currently streaming, kept so the notification can report its rate and peer count and
    // so its in-torrent subtitles can be offered. Cleared on release so a stale handle is never
    // queried after removal — calling into a removed handle is undefined at the native layer.
    private val activeReader = java.util.concurrent.atomic.AtomicReference<TorrentFileReader?>(null)

    // Bumped every time the session is torn down wholesale. Readers carry the generation they were
    // opened under, and one from an earlier generation must not touch the reference count.
    //
    // Without this: tap Stop, start something else straight away, and when the *old* reader finally
    // closes its decrement lands on the new playback's count — taking the new session and its service
    // down mid-episode. Clamping the count doesn't help, because the count is legitimately 1 at that
    // point; the close simply belongs to a session that no longer exists.
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    // Teardown is deferred, not immediate, because "the last reader closed" is not "playback ended".
    //
    // Media3 closes the current DataSource *before* opening its replacement — on every seek past the
    // buffer, on an error retry, and on setMediaSource, which is exactly what offering in-torrent
    // subtitles does mid-playback. Each of those drops the reader count to zero for a few
    // milliseconds. Tearing down on that gap meant every seek stopped the session, removed the
    // torrent, and then re-added it and waited for metadata again — and ran a cache sweep with
    // nothing protected, which on a 512 MB quota could delete the file being watched.
    private val teardownScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "torrent-teardown").apply { isDaemon = true }
    }

    // Both guarded by `lock`. The handle is held rather than removed immediately so a seek doesn't
    // cost a remove-and-re-add of the torrent; it is removed when the teardown actually runs, or
    // sooner if a *different* torrent starts (see openFile).
    private var pendingTeardown: java.util.concurrent.ScheduledFuture<*>? = null
    private var handleAwaitingRemoval: org.libtorrent4j.TorrentHandle? = null

    // Every reader currently registered, in the order they were opened. Guarded by `lock`.
    //
    // A list rather than a count, because more than one reader is open at a time far more often than
    // the design first assumed: Media3 opens a second DataSource over the same file before closing
    // the first on a seek, and the in-torrent subtitle feature adds another. Everything that used to
    // be tracked separately — which reader is current, which paths are protected — is derived from
    // this, so those views cannot disagree with each other or outlive the readers they describe.
    private val liveReaders = mutableListOf<TorrentFileReader>()

    // Info hashes of readers that have taken the count but have not finished opening. Guarded by
    // `lock`, and a multiset because two opens of the same torrent can be in flight at once.
    //
    // liveReaders alone is not the whole picture. openFile() increments the reader count and *then*
    // blocks in TorrentFileReader.open waiting for metadata, so between those two points a reader
    // exists as far as the count is concerned and is invisible to anything reading the list. If the
    // previous reader of that same torrent closes in the window, the count is still above zero, so
    // teardown is skipped and the close falls through to the "remove it now" branch — which would
    // remove the torrent the reader still opening is about to read.
    private val pendingOpens = mutableMapOf<String, Int>()

    // Absolute paths of files currently open for reading. The cache sweep is handed these as
    // protected paths: a sweep triggered from the settings screen can fire while a playback is in
    // progress, and deleting the file being streamed would kill it.
    //
    // Guarded by `lock`, and derived from liveReaders rather than kept as a set. As a set it was a
    // silent data-loss bug: two readers of the same file added one entry between them, so the first
    // close unprotected a file the second was still streaming, and the sweep was then free to delete
    // it mid-playback — the exact failure the protection exists to prevent.
    private fun protectedPathsLocked(): Set<String> = liveReaders.mapTo(mutableSetOf()) { it.filePath }

    // Snapshot for callers that sweep the cache while playback may be running.
    fun protectedCachePaths(): Set<String> = synchronized(lock) { protectedPathsLocked() }

    // Whether anything still holds a file open. True from the moment a playback starts resolving — not
    // from when the torrent's metadata arrives — which is what makes it the right signal for "is there
    // still work here?" as opposed to stats(), which is null for the whole of a slow start.
    val hasOpenReaders: Boolean get() = openReaders.get() > 0

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
            // The three cases above are expected — that's what a missing ABI looks like, and they
            // stay at warning level. Anything else reaching here is a bug or a broken environment,
            // not a device without the library, so it is logged as an error to stand out. Still
            // caught rather than rethrown: whatever went wrong, the right outcome for the user is a
            // disabled feature, not a dead app.
            Log.e(TAG, "Torrent engine unavailable: unexpected error probing libtorrent4j", e)
            false
        }
    }

    val isRunning: Boolean
        get() = session?.isRunning == true

    // Whether a torrent should be offered right now — the check callers actually want.
    //
    // isAvailable answers "can this device run it at all"; this folds in the two things the user
    // controls. Wi-Fi-only is checked here rather than at the settings screen because connectivity
    // changes between choosing a stream and playing it, and a metered connection at play time is the
    // moment that matters.
    val isUsable: Boolean
        get() = isAvailable && settings.enabled && (!settings.unmeteredOnly || isUnmetered())

    private fun isUnmetered(): Boolean {
        val manager = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // Idempotent: returns the running session, starting one if needed, or null when the engine
    // isn't available on this device. Synchronized because playback resolution and the (later)
    // service lifecycle can both reach this concurrently, and two sessions binding the same ports
    // would be a mess to diagnose.
    fun ensureStarted(): SessionManager? {
        if (!isAvailable) return null
        // Everything below is under the same lock stop() takes, deliberately including the
        // already-running check: an unsynchronized fast path could read a live session, have stop()
        // discard it, and then hand the caller a stopped manager. This is called once per playback,
        // not per read, so the lock costs nothing worth optimizing away.
        synchronized(lock) {
            session?.let { existing ->
                if (existing.isRunning) return existing
                // A stored session that isn't running is one whose stop() previously failed. Try
                // once more and discard it either way, so a new session doesn't get created on top
                // of native state still holding the old ports.
                runCatching { existing.stop() }
                    .onFailure { Log.w(TAG, "Discarding a session that would not stop", it) }
                session = null
                // A handle only means anything inside the session that produced it, so one held for
                // deferred removal dies with that session. Left set, it would be handed to the *new*
                // session below, where calling into it is undefined at the native layer.
                handleAwaitingRemoval = null
            }
            var starting: SessionManager? = null
            return try {
                starting = SessionManager()
                starting.start()
                applySettings(starting)
                session = starting
                Log.i(TAG, "Torrent session started")
                starting
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start torrent session", e)
                // start() can fail after the native session has already claimed sockets. It was
                // never stored in `session`, so stop() can't reach it — release it here or every
                // retry strands another set of native resources.
                starting?.let { runCatching { it.stop() } }
                null
            }
        }
    }

    // Opens one file inside a torrent for reading.
    //
    // The only entry point consumers use, and deliberately the only one: it keeps SessionManager and
    // every other libtorrent type inside this module, so :core:player can adapt a torrent to Media3
    // without libtorrent on its compile classpath.
    @Throws(java.io.IOException::class)
    fun openFile(ref: TorrentRef, trackers: List<String>, saveDir: java.io.File): TorrentFileReader {
        // Acquiring the session and registering the reader are one critical section, because the
        // deferred teardown runs on its own thread and takes this same lock. Split apart — the shape
        // this had when teardown was inline — the teardown fires between the two, sees a count of zero
        // and an unchanged generation, and stops the very session this call is about to read from. The
        // auto-play case walks straight into it: the previous episode's close schedules a teardown, and
        // resolving the next episode's stream can easily take the whole grace period. synchronized is
        // reentrant, so ensureStarted() nesting here costs nothing — it already took this lock for the
        // whole of its body.
        val session: SessionManager
        val openedAt: Long
        val isFirstReader: Boolean
        synchronized(lock) {
            // A reader arriving inside the grace window means the previous close was a seek or a
            // retry, not the end of playback — cancel the teardown so the session and the torrent
            // survive it. First, so that a teardown already blocked on this lock finds a non-zero
            // count when it finally runs and declines to proceed.
            pendingTeardown?.cancel(false)
            pendingTeardown = null
            // Before the count is taken, so there is nothing to give back on the failure path.
            session = ensureStarted()
                ?: throw java.io.IOException("The torrent engine is unavailable on this device")
            // Reading the generation and taking the count must be one step. Split apart, stopAll()
            // landing between them tags this reader with a generation that is already retired while its
            // increment counts against the new one — so its close is later dismissed as stale and the
            // count stays one too high forever, and the session never tears down at the end of playback.
            openedAt = generation.get()
            isFirstReader = openReaders.getAndIncrement() == 0
            // Declared with the count, for the same reason the count is taken here: from this point
            // on this reader is real to everything that has to decide whether a torrent is still
            // needed, even though it will not exist as an object for another few seconds.
            pendingOpens.merge(ref.infoHash.lowercase(), 1, Int::plus)
            // Unless this is a *different* torrent, in which case the held handle belongs to
            // something nobody is watching any more and has to go now. Auto-play reaches here about a
            // second after the previous episode's reader closed, so without this the finished
            // episode's torrent would keep talking to peers for the rest of the session.
            handleAwaitingRemoval?.let { held ->
                val heldHash = runCatching { held.infoHash().toHex() }.getOrNull()
                if (!heldHash.equals(ref.infoHash, ignoreCase = true)) {
                    runCatching { session.remove(held) }
                        .onFailure { Log.w(TAG, "Could not remove the previous torrent", it) }
                    handleAwaitingRemoval = null
                }
            }
        }
        // A replay from watch history carries no trackers — the url is deliberately just the torrent's
        // identity — so without this it would fall back to DHT alone, which is slow to bootstrap and
        // often finds no peers at all. Reuse whatever worked last time.
        val effectiveTrackers = trackers.ifEmpty { trackerStore.trackersFor(ref.infoHash) }
        if (isFirstReader) {
            // Both of these are once per playback, not once per reader. openFile() runs from
            // DataSource.open(), and Media3 opens more than one DataSource for a single item — so
            // without the guard this would repeat a SharedPreferences write on a playback thread, and
            // start a service that is already running.
            trackerStore.remember(ref.infoHash, trackers)
            // Bring up the foreground service so the download isn't stopped the moment the app is
            // backgrounded. Outside the lock: it's a binder call, and nothing it reaches needs engine
            // state.
            TorrentService.start(appContext)
        }
        val reader = try {
            TorrentFileReader.open(
                session,
                ref,
                effectiveTrackers,
                saveDir,
                onClosed = { closing -> releaseReader(openedAt, closing) },
            )
        } catch (e: Throwable) {
            // The count was taken before open() could fail; give it back, or a failed open would
            // pin the session open for the rest of the process. No reader exists to deregister.
            synchronized(lock) { clearPendingOpenLocked(ref.infoHash) }
            releaseReader(openedAt, reader = null)
            throw e
        }
        // Registered under the lock, re-checking the generation, because open() above blocks — it
        // waits for the torrent's metadata, which on a cold magnet can take the better part of a
        // minute. stopAll() landing in that window retires this generation, resets the count and
        // empties the reader registry, and the registration would then run *after* all of it.
        //
        // Both halves matter. A stale activeReader means stats() calls into a handle whose torrent
        // has been removed, which is undefined at the native layer. A stale registry entry is worse
        // in a quiet way: releaseReader returns early for a retired generation, before it can
        // deregister, so that file stays protected from eviction for the rest of the process — and
        // once the protected file is the largest thing in the cache, the quota can never be met
        // again.
        val stillCurrent = synchronized(lock) {
            val current = openedAt == generation.get()
            if (current) {
                liveReaders += reader
                activeReader.set(reader)
            }
            // Dropped in the same critical section that adds the reader to the list, so there is no
            // instant where this torrent is neither pending nor live and a close could decide
            // nothing needs it.
            clearPendingOpenLocked(ref.infoHash)
            current
        }
        if (!stillCurrent) {
            // The user stopped playback while this was opening. The reader belongs to a session that
            // has already been torn down, so it is closed rather than handed back; its close runs
            // releaseReader, which correctly ignores it as stale. Closing is the *only* release on
            // this path — routing the throw below through the catch above as well would have run
            // releaseReader twice for one reader.
            Log.i(TAG, "Discarding a reader whose session was stopped while it was opening")
            runCatching { reader.close() }
            throw java.io.IOException("Playback was stopped while the torrent was opening")
        }
        return reader
    }

    // A snapshot for the notification, or null when nothing is being read. Returns a plain data
    // class rather than the handle so no libtorrent type escapes this module.
    fun stats(): TorrentStats? {
        val handle = activeReader.get()?.torrentHandle ?: return null
        return runCatching {
            if (!handle.isValid) return null
            val status = handle.status()
            TorrentStats(
                name = status.name().orEmpty(),
                downloadRateBytesPerSec = status.downloadRate(),
                peers = status.numPeers(),
                seeds = status.numSeeds(),
                progress = status.progress(),
            )
        }.getOrNull()
    }

    // Subtitle files inside the torrent being streamed: how many are worth offering, and which have
    // fully arrived so far.
    //
    // Polled rather than pushed. The alternative is a flow fed from libtorrent's alert thread, which
    // would mean either a scope this @Singleton doesn't have or a listener outliving the torrent it
    // describes — and the caller is a player that is already ticking once a second.
    // Null while no reader is open — which a caller must not confuse with a torrent that has no
    // subtitles, because the file list isn't known until the reader has resolved metadata.
    fun subtitleProgress(): TorrentSubtitleProgress? {
        val reader = activeReader.get() ?: return null
        return TorrentSubtitleProgress(
            total = reader.subtitleCount,
            ready = runCatching { reader.readySubtitles() }.getOrDefault(emptyList()),
        )
    }

    // Tears everything down regardless of reader count — the notification's Stop action. Readers still
    // holding the torrent will fail their next read with an IOException, which surfaces as a player
    // error. That is the intended outcome: the user asked it to stop.
    fun stopAll() {
        // One critical section for the whole retirement, so no reader can be opened or released
        // half-way through it and end up attributed to the wrong side of the boundary. stop() takes
        // the same lock and synchronized is reentrant, so it belongs in here too: releasing before
        // stopping would let a new playback start a session this call then pulls out from under it.
        synchronized(lock) {
            activeReader.getAndSet(null)?.let { reader ->
                runCatching { session?.remove(reader.torrentHandle) }
            }
            // Retires every reader currently open before the count is reset, so their closes can't be
            // charged against whatever plays next.
            generation.incrementAndGet()
            openReaders.set(0)
            // Cleared with the count, not left to the readers. Their close() will still run and still
            // call releaseReader, but a reader that never closes cleanly would otherwise leave its path
            // protected for the rest of the process — permanently exempting the largest file in the
            // cache from eviction, so the quota could never be met again.
            liveReaders.clear()
            // Opens still in flight belong to the generation being retired. Their registration will
            // find itself stale and close the reader it built, so nothing is left to protect.
            pendingOpens.clear()
            // Stop means stop: no grace period, and any teardown already scheduled is redundant now.
            pendingTeardown?.cancel(false)
            pendingTeardown = null
            handleAwaitingRemoval = null
            stop()
        }
        TorrentService.stop(appContext)
    }

    // Called when a reader closes, and when an open fails after the count was taken.
    //
    // Removing the torrent is what actually stops peer traffic: pausing or clearing priorities still
    // leaves the session talking to the swarm. Files are left on disk deliberately — they are the
    // cache a resumed playback reads from, and the storage quota is what reclaims them.
    private fun releaseReader(openedAt: Long, reader: TorrentFileReader?) {
        val handle = reader?.torrentHandle
        // The generation check and the count mutation are one critical section, matching stopAll().
        // Checked and then decremented separately, stopAll() landing in the gap would let a reader
        // that passed the check as current go on to decrement a count that has since been reset for a
        // new playback — stopping the session that playback is streaming from.
        val teardown: Boolean
        synchronized(lock) {
            if (openedAt != generation.get()) {
                // A reader from a session stopAll() already tore down. Everything this would otherwise
                // do has either happened already or belongs to somebody else now:
                //
                //  - the count was reset wholesale, so decrementing would take a *live* playback's
                //    count to zero and stop the session it is streaming from;
                //  - the torrent left the session with the session itself, and the handle is no longer
                //    valid to call into;
                //  - the registry was emptied, and if the user replayed the same file its path is
                //    protected again on behalf of the new reader — deregistering here would unprotect
                //    a file currently being streamed and let the sweep delete it mid-playback.
                Log.i(TAG, "Ignoring the close of a reader from a stopped session")
                return
            }
            reader?.let { closing ->
                // Identity, not path: two readers of the same file are otherwise indistinguishable,
                // and removing "a reader with this path" would deregister whichever one happened to
                // be found first.
                liveReaders.removeAll { it === closing }
                // activeReader must never outlive the reader it names — stats() and
                // subtitleProgress() call into it, and a closed reader's torrent may already have
                // been removed from the session, which is undefined at the native layer. Hand the
                // role to the newest reader still open, or drop it if this was the last.
                if (activeReader.get() === closing) activeReader.set(liveReaders.lastOrNull())
            }
            // Floored at zero rather than a plain decrement, for the same reason: stopAll() can reset
            // the count under a reader that then closes normally, and a bare decrement would go
            // negative — after which the next openFile()'s `getAndIncrement() == 0` check is false and
            // the foreground service never starts for the following playback.
            val remaining = openReaders.updateAndGet { (it - 1).coerceAtLeast(0) }
            teardown = remaining <= 0
            if (teardown) {
                // Nothing is reading *right now*, which on its own means very little — a seek looks
                // identical to the end of playback from here. Hold the handle and schedule the real
                // teardown; openFile() cancels it if a reader arrives inside the window.
                handle?.let { handleAwaitingRemoval = it }
                scheduleTeardown(openedAt)
            } else if (handle != null && !isSharedLocked(handle)) {
                // Another reader is still going, but not on this torrent, so removing it now is safe
                // — and removing the torrent is what actually stops peer traffic. The shared case is
                // the one that had to be excluded: Media3 opens a second DataSource over the same
                // file before closing the first on a seek, and removing there would pull the torrent
                // out from under the reader that is still streaming it.
                runCatching { session?.remove(handle) }
                    .onFailure { Log.w(TAG, "Could not remove torrent from the session", it) }
            }
        }
    }

    // Retires one in-flight open of `infoHash`. Caller must hold `lock`. Tolerates an entry that
    // stopAll() has already cleared, which is why it is a decrement-or-remove rather than a remove.
    private fun clearPendingOpenLocked(infoHash: String) {
        val key = infoHash.lowercase()
        val remaining = (pendingOpens[key] ?: 0) - 1
        if (remaining > 0) pendingOpens[key] = remaining else pendingOpens.remove(key)
    }

    // Whether anything else still needs the torrent behind `handle` — a reader already streaming it,
    // or one still opening it. Caller must hold `lock`.
    //
    // Compared by info hash rather than by TorrentHandle identity: handles are thin wrappers that
    // libtorrent4j hands out afresh, so two of them can name the same torrent.
    //
    // Every unreadable hash counts as a match, deliberately. The two outcomes are not symmetric: a
    // torrent left in place is removed by the teardown path a few seconds later anyway, while a
    // torrent removed in error kills playback that is in progress. So a transient infoHash() failure
    // — on either side of the comparison — has to fall towards "still needed".
    private fun isSharedLocked(handle: org.libtorrent4j.TorrentHandle): Boolean {
        val closingHash = runCatching { handle.infoHash().toHex() }.getOrNull()
            // Nothing to compare against, so anything still holding a reader might be holding this.
            ?: return liveReaders.isNotEmpty() || pendingOpens.isNotEmpty()
        if (pendingOpens.keys.any { it.equals(closingHash, ignoreCase = true) }) return true
        return liveReaders.any { other ->
            val otherHash = runCatching { other.torrentHandle.infoHash().toHex() }.getOrNull()
            otherHash == null || otherHash.equals(closingHash, ignoreCase = true)
        }
    }

    // Caller must hold `lock`.
    private fun scheduleTeardown(openedAt: Long) {
        pendingTeardown?.cancel(false)
        pendingTeardown = runCatching {
            teardownScheduler.schedule(
                { tearDownIfStillIdle(openedAt) },
                TEARDOWN_GRACE_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }.getOrElse {
            // The scheduler is gone (process shutting down). Better to tear down immediately than
            // to leave a session and a foreground notification running.
            Log.w(TAG, "Could not schedule torrent teardown; tearing down now", it)
            tearDownIfStillIdle(openedAt)
            null
        }
    }

    // Runs one grace period after the last reader closed. This is the real "playback ended" —
    // remove the torrent so it stops talking to peers, stop the session, and reclaim disk.
    private fun tearDownIfStillIdle(openedAt: Long) {
        val proceed: Boolean
        synchronized(lock) {
            pendingTeardown = null
            // A reader opened during the window (a seek, or the next episode), or stopAll() already
            // did all of this and retired this generation. Either way it is no longer ours to do.
            proceed = openReaders.get() <= 0 && openedAt == generation.get()
            if (proceed) {
                handleAwaitingRemoval?.let { handle ->
                    // The flagless overload keeps the downloaded data on disk, which is what we want:
                    // the files are the cache a resumed playback reads from.
                    runCatching { session?.remove(handle) }
                        .onFailure { Log.w(TAG, "Could not remove torrent from the session", it) }
                }
                handleAwaitingRemoval = null
                activeReader.set(null)
                stop()
            }
        }
        if (proceed) {
            // Outside the lock: a binder call and a thread spawn, neither of which touches engine
            // state that the section above protects.
            TorrentService.stop(appContext)
            sweepCache()
        }
    }

    // Stops the session and releases its sockets. Playback teardown calls this once nothing is being
    // read, so an idle app isn't holding a DHT node open on the user's connection.
    fun stop() {
        synchronized(lock) {
            val current = session ?: return
            try {
                current.stop()
                // Cleared only once the stop actually succeeded. Clearing first would mean a failed
                // stop leaves the engine believing it is stopped with no handle left to retry, while
                // the native session keeps running. Held onto here instead, and ensureStarted()
                // discards it if it is still around next time.
                session = null
                // The torrent went with the session, so a handle held for deferred removal is now a
                // pointer into freed native state. Cleared here rather than only at the call sites so
                // it holds for every path into stop(), not just teardown's.
                handleAwaitingRemoval = null
                Log.i(TAG, "Torrent session stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop torrent session cleanly; keeping the handle to retry", e)
            }
        }
    }

    // Brings the cache back under quota once nothing is being read. Deliberately here rather than
    // before a playback: sweeping at open time would compete with the download for IO exactly when
    // latency matters most.
    //
    // Protected paths are passed even though this only runs when no reader is open. "No reader is
    // open" was doing more work than it could carry: a seek closes the reader before opening its
    // replacement, so this used to run mid-playback with nothing protected — and since the file being
    // streamed is the largest and most recently grown thing in the cache, it was the first thing the
    // policy chose to delete. The grace period above is what actually fixes that; this is the belt to
    // its braces, and it costs one set copy.
    //
    // Off the calling thread — this can run from a reader's close(), which is on a playback thread.
    private fun sweepCache() {
        val dir = torrentCacheDir(appContext)
        val quota = settings.quotaBytes
        val protectedPaths = protectedCachePaths()
        Thread {
            runCatching { TorrentCacheSweeper.sweep(dir, quotaBytes = quota, protectedPaths = protectedPaths) }
                .onFailure { Log.w(TAG, "Torrent cache sweep failed", it) }
        }.apply { isDaemon = true }.start()
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

        // How long the session survives the last reader closing. Long enough to cover a seek's
        // close-then-open gap and Media3's error retries, short enough that a user who really has
        // stopped watching isn't left in a swarm they've forgotten about.
        const val TEARDOWN_GRACE_MS = 5_000L
    }
}
