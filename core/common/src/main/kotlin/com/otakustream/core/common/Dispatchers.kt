package com.otakustream.core.common

import javax.inject.Qualifier

// The IO dispatcher, injected rather than referenced.
//
// A ViewModel that writes Dispatchers.IO into a flowOn cannot be tested deterministically: the work
// lands on real threads, so a test has no way to know when it is done except to wait on the clock
// and hope. That is what the first version of DownloadRemovalFailureTest did — poll for two seconds
// and give up — which passes on a quiet runner and fails on a busy one. A test that fails that way
// is worse than no test, because the failure looks like a bug in the code it covers.
//
// Only the qualifier lives here, not the @Provides that satisfies it — that is in the app module.
// A library declaring an @InstallIn module needs the Hilt processor to emit the aggregation
// metadata the app reads, which would mean adding the Hilt plugin and kapt to this module; a bare
// @Qualifier annotation needs no processing at all to be used. So the annotation is shared and the
// binding is declared once, where the component is.
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
