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

    // Swap whatever is registered under `id` for `source`, atomically.
    //
    // Not unregister-then-register: those are two compareAndSet loops, and any registration landing
    // between them wins — after which registerDynamic sees a duplicate, closes the caller's freshly
    // built instance and returns, leaving the caller believing it swapped. The reload-after-editing
    // preferences path then reported success while the registry kept serving the old preferences.
    fun replaceDynamic(id: Long, source: VideoSource)

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
    override fun replaceDynamic(id: Long, source: VideoSource) {
        while (true) {
            val current = _dynamicSources.value
            val next = current.filterNot { it.id == id } + source
            if (_dynamicSources.compareAndSet(current, next)) {
                current.forEach { if (it.id == id && it !== source) closeQuietly(it) }
                return
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
