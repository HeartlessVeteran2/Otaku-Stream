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

    override fun getSources(): List<VideoSource> = builtInSources.toList() + _dynamicSources.value

    override fun getSource(id: Long): VideoSource? = getSources().firstOrNull { it.id == id }

    override fun observeSources(): Flow<List<VideoSource>> = _dynamicSources.map { builtInSources.toList() + it }

    override fun registerDynamic(source: VideoSource) {
        if (_dynamicSources.value.any { it.id == source.id }) return
        _dynamicSources.value = _dynamicSources.value + source
    }

    override fun unregisterDynamic(id: Long) {
        _dynamicSources.value = _dynamicSources.value.filterNot { it.id == id }
    }
}
