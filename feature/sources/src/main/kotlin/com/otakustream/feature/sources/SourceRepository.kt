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

    override fun registerDynamic(source: VideoSource) {
        if (_dynamicSources.value.any { it.id == source.id }) {
            // Already registered, so this instance is never going to be used — but it has been
            // *built*, and for a Mangayomi extension that means a live QuickJS runtime with its own
            // thread and native context. Dropping the reference does not release either. Duplicates
            // are routine: a reinstall, or a bootstrap racing a manual install, produces one.
            (source as? AutoCloseable)?.let { runCatching { it.close() } }
            return
        }
        _dynamicSources.value = _dynamicSources.value + source
    }

    override fun unregisterDynamic(id: Long) {
        val (removed, remaining) = _dynamicSources.value.partition { it.id == id }
        _dynamicSources.value = remaining
        // Release any engine-backed source (e.g. a Mangayomi QuickJS runtime) as it leaves the
        // registry, so uninstall/reload frees its thread + native context.
        removed.forEach { source -> (source as? AutoCloseable)?.let { runCatching { it.close() } } }
    }
}
