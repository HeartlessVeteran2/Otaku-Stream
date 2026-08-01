package com.otakustream.feature.sources

import com.otakustream.core.sources.api.VideoSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface SourceRepository {
    fun getSources(): List<VideoSource>
    fun getSource(id: Long): VideoSource?
    fun registerDynamic(source: VideoSource)

    // Swap whatever is registered under `source.id` for `source`, atomically.
    //
    // Not unregister-then-register: those are two compareAndSet loops, and any registration landing
    // between them wins — after which registerDynamic sees a duplicate, closes the caller's freshly
    // built instance and returns, leaving the caller believing it swapped. The reload-after-editing
    // preferences path then reported success while the registry kept serving the old preferences.
    //
    // The target id comes from the source rather than a separate parameter, so there is no way to
    // ask for a swap that removes one id and installs another.
    //
    // `expected` is the instance the caller believes is registered, matched by identity. Returns
    // false, having registered nothing, when that exact instance is no longer there.
    //
    // Identity rather than id, because an id-only check cannot tell "still the one I set out to
    // replace" from "uninstalled and reinstalled while I was working". Both leave the id present;
    // only the second means the caller's replacement is stale, built from a record that has since
    // been replaced — and swapping it in would close a live runtime that someone else just
    // installed. Refusing also covers plain uninstall, where the id is gone entirely and appending
    // would resurrect a source whose record is deleted.
    //
    // The caller owns the instance it built and is expected to close it when this returns false.
    fun replaceDynamic(expected: VideoSource, source: VideoSource): Boolean

    fun unregisterDynamic(id: Long)
    fun observeSources(): Flow<List<VideoSource>>
}

@Singleton
class SourceRegistry @Inject constructor(
    private val builtInSources: Set<@JvmSuppressWildcards VideoSource>,
) : SourceRepository {

    private val _dynamicSources = MutableStateFlow<List<VideoSource>>(emptyList())
    val dynamicSources: StateFlow<List<VideoSource>> = _dynamicSources.asStateFlow()

    // Snapshotted once: the built-in set never changes, so re-materializing it into a list on every
    // getSources()/getSource()/observeSources() emission was pure allocation.
    private val builtIns: List<VideoSource> = builtInSources.toList()

    override fun getSources(): List<VideoSource> = builtIns + _dynamicSources.value

    // O(1) rather than a linear scan over a freshly concatenated list: this is called per episode
    // tap and again when resolving the next episode, not just once per screen.
    override fun getSource(id: Long): VideoSource? =
        builtInsById[id] ?: _dynamicSources.value.firstOrNull { it.id == id }

    private val builtInsById: Map<Long, VideoSource> = builtIns.associateBy { it.id }

    override fun observeSources(): Flow<List<VideoSource>> = _dynamicSources.map { builtIns + it }

    // compareAndSet rather than read-check-write, because the thing being guarded against *is* a
    // race. Bootstrap registers add-ons in parallel while a manual install can land at any moment;
    // two of those reading the same list and both writing their own `list + source` means one of the
    // two sources vanishes from the registry with its QuickJS runtime still alive. Losing the CAS
    // sends this back through the duplicate check, so the loser is either published or closed.
    override fun registerDynamic(source: VideoSource) {
        while (true) {
            val current = _dynamicSources.value
            val registered = current.firstOrNull { it.id == source.id }
            if (registered != null) {
                // Already registered, so this instance is never going to be used — but it has been
                // *built*, and for a Mangayomi extension that means a live QuickJS runtime with its
                // own thread and native context. Dropping the reference does not release either.
                // Duplicates are routine: a reinstall, or a bootstrap racing a manual install,
                // produces one.
                //
                // Unless the duplicate is this very instance, in which case closing it would kill a
                // source that is registered and in use.
                if (registered !== source) closeQuietly(source)
                return
            }
            if (_dynamicSources.compareAndSet(current, current + source)) return
        }
    }

    // One compareAndSet, so there is no moment where the id is unregistered and something else can
    // claim it. The displaced instances are closed once the swap has actually taken — closing before
    // would kill a live source if the loop had to retry.
    //
    // In place, not remove-and-append: this list is the source picker's order and the priority the
    // home rails interleave by, so appending would silently reorder the UI every time an extension's
    // preferences were saved.
    override fun replaceDynamic(expected: VideoSource, source: VideoSource): Boolean {
        while (true) {
            val current = _dynamicSources.value
            // By identity, and re-checked on every attempt: a lost CAS means the list changed under
            // us, and what changed may be exactly this id being uninstalled and installed afresh.
            // Both conditions: identity answers "still the registration I meant", and the id check
            // makes the documented invariant real rather than merely asserted — a replacement with a
            // different id would otherwise drop `expected`'s id and install a second entry for its
            // own.
            val index = current.indexOfFirst { it === expected && it.id == source.id }
            if (index < 0) return false
            val next = current.toMutableList().apply { this[index] = source }
            if (_dynamicSources.compareAndSet(current, next)) {
                if (expected !== source) closeQuietly(expected)
                return true
            }
        }
    }

    override fun unregisterDynamic(id: Long) {
        while (true) {
            val current = _dynamicSources.value
            val (removed, remaining) = current.partition { it.id == id }
            if (_dynamicSources.compareAndSet(current, remaining)) {
                // Release any engine-backed source (e.g. a Mangayomi QuickJS runtime) as it leaves
                // the registry, so uninstall/reload frees its thread + native context. Closed only
                // once the removal is the one that actually landed — a losing CAS must not close a
                // source that is still published.
                removed.forEach { closeQuietly(it) }
                return
            }
        }
    }

    private fun closeQuietly(source: VideoSource) {
        (source as? AutoCloseable)?.let { runCatching { it.close() } }
    }
}
