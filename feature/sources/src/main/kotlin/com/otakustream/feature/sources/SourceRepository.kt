package com.otakustream.feature.sources

import com.otakustream.core.sources.api.VideoSource
import javax.inject.Inject
import javax.inject.Singleton

interface SourceRepository {
    fun getSources(): List<VideoSource>
    fun getSource(id: Long): VideoSource?
}

@Singleton
class SourceRegistry @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards VideoSource>,
) : SourceRepository {
    override fun getSources(): List<VideoSource> = sources.toList()

    override fun getSource(id: Long): VideoSource? = sources.firstOrNull { it.id == id }
}
