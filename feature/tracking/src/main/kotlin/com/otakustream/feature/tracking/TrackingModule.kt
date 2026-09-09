package com.otakustream.feature.tracking

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Binds the interface the ViewModels depend on to the AniList-backed implementation.
//
// Scoped, because the implementation it replaces was a @Singleton and the call sites assume one:
// an unscoped binding would hand each injection point its own instance, and the sync state each
// one holds would then diverge.
@Module
@InstallIn(SingletonComponent::class)
abstract class TrackingModule {

    @Binds
    @Singleton
    abstract fun bindTrackingManager(impl: TrackingManagerImpl): TrackingManager

    // @Singleton is load-bearing here too, and for a reason specific to this class: the client
    // memoizes the discovery rails for a TTL and caches MAL id lookups. A second instance would
    // have empty caches, so every screen would re-request what another had just fetched.
    @Binds
    @Singleton
    abstract fun bindAniListClient(impl: AniListClientImpl): AniListClient

    @Binds
    @Singleton
    abstract fun bindAniSkipClient(impl: AniSkipClientImpl): AniSkipClient
}
