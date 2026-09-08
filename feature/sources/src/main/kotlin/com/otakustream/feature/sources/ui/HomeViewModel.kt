package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.common.IoDispatcher
import com.otakustream.core.common.runCatchingCancellable
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.feature.sources.SourceBootstrapper
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val RAIL_ITEM_CAP = 20
private const val CONTINUE_WATCHING_CAP = 10
// Soft cap per source per rail so one slow source can't hold up the whole home fan-out.
private const val RAIL_FETCH_TIMEOUT_MS = 15_000L

// How long registrations must stay quiet before the rails are rebuilt. Applies to changes after the
// first snapshot only — long enough to swallow the burst of a multi-catalog add-on registering, short
// enough that installing a single add-on still feels immediate.
private const val SOURCE_SETTLE_MS = 300L

// What one rail fan-out came back with, and whether it is worth believing. A rail no source
// answered carries an empty list that means "we don't know", which must not be confused with the
// empty list that means "there is nothing".
private data class RailResult(val entries: List<CatalogEntry>, val anySourceAnswered: Boolean) {
    fun orPrevious(previous: List<CatalogEntry>): List<CatalogEntry> =
        if (anySourceAnswered) entries else previous
}

data class HomeUiState(
    val popular: List<CatalogEntry> = emptyList(),
    val latest: List<CatalogEntry> = emptyList(),
    val isLoading: Boolean = false,
    // Separate from isLoading, which is also true during the *first* load. Binding the pull
    // indicator to isLoading would spin it on every cold start, next to the in-content spinner the
    // screen already shows for that case. This one is set only by refresh().
    val isRefreshing: Boolean = false,
    val hasAnySources: Boolean = false,
    val hasLoadedOnce: Boolean = false,
)

// Drives the content-forward home on the Play tab: Continue Watching plus Popular/Latest rails
// fanned out across every enabled source. Lives in feature:sources (not app) because it must run
// both source bootstrappers — Play is the start destination, so persisted addons may not be
// registered yet when it first loads. registerDynamic dedupes by id, so CatalogViewModel
// bootstrapping again later is harmless.
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val sourceBootstrapper: SourceBootstrapper,
    libraryRepository: LibraryRepository,
    // Injected, not Dispatchers.IO written into the scope below. The deadline this ViewModel
    // enforces is the thing most worth testing about it, and a test cannot advance a real clock —
    // it would have to sleep fifteen seconds and hope, which is the kind of test that passes on a
    // quiet runner and fails on a busy one.
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val continueWatching: StateFlow<List<WatchHistoryEntry>> = libraryRepository.observeHistory()
        .map { history -> history.distinctBy { it.mediaUrl }.take(CONTINUE_WATCHING_CAP) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var railsJob: Job? = null

    // Source calls run here, detached from the coroutine that awaits them.
    //
    // withTimeoutOrNull only ends work that cooperates with cancellation, and the scripted sources
    // do not: ScriptedVideoSource wraps a blocking Rhino call in withContext(Dispatchers.IO), so a
    // script that never returns leaves its coroutine running no matter what deadline is around it.
    // As a child of the awaiting scope that is fatal — coroutineScope cannot return until every
    // child completes, so awaitAll waits forever, the rails never update, and the pull indicator
    // spins until the app is force-stopped. Detached, the deadline is enforceable: the fan-out
    // gives up on that source, the rails update from the ones that answered, and the wedged call is
    // left leaked rather than taking the screen down with it.
    //
    // This does not fix the wedge. A script with no instruction budget still holds its source's
    // mutex forever, and every later call to that source still blocks; that is a separate change,
    // in the engines. What this bounds is the damage to everything else.
    //
    // SupervisorJob so one source's failure cannot cancel its siblings.
    private val sourceScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    override fun onCleared() {
        super.onCleared()
        sourceScope.cancel()
    }

    init {
        viewModelScope.launch {
            // Shared, once-per-process rehydrate of persisted sources (dedup with CatalogViewModel).
            // A failed bootstrap (corrupt persisted source, I/O error) must not kill this coroutine
            // before observeSources() collection starts — that would leave the home stuck loading
            // forever — so SourceBootstrapper guards each rehydrate internally.
            sourceBootstrapper.ensureStarted()
            // React to every registration change (bootstrap above, addon install/removal later)
            // so a newly installed add-on populates the home without a restart.
            // Every emission but the first waits for the registry to settle. A change *after*
            // bootstrap can still arrive in a burst — installing a Stremio add-on that declares
            // several catalogs registers one source per catalog — and without the wait each one
            // cancels the rail fan-out and restarts requests to every source registered so far.
            //
            // Not a `debounce()` on the flow, which would delay the first emission too. The first
            // one is not a burst: ensureStarted() above has already awaited bootstrap, so by the
            // time collection begins the persisted sources are registered and observeSources()
            // replays them as a single snapshot. Debouncing that just held the home screen empty
            // for another 300 ms on every launch, buying nothing. collectLatest cancels the delay
            // below when a newer emission arrives, which is what makes it settle the burst.
            var seenFirst = false
            sourceRepository.observeSources()
                .distinctUntilChanged()
                .collectLatest { sources ->
                    if (seenFirst) delay(SOURCE_SETTLE_MS)
                    seenFirst = true
                    _uiState.value = _uiState.value.copy(hasAnySources = sources.isNotEmpty())
                    refreshRails(sources)
                }
        }
    }

    // Pull-to-refresh, and the retry behind the rails' error state. Re-runs the fan-out against
    // whatever is registered now.
    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        refreshRails(sourceRepository.getSources())
    }

    private fun refreshRails(sources: List<VideoSource>) {
        railsJob?.cancel()
        // The previous fan-out's requests are no longer children of railsJob, so cancelling that
        // does not reach them. Every source that *can* be cancelled still is, at the same moment it
        // was before; the ones that cannot are the reason the scope is detached in the first place.
        sourceScope.coroutineContext.cancelChildren()
        // Started before the launch below, so both rails' requests go out together rather than the
        // second waiting on the first.
        val popular = startRail(sources) { it.getPopular(1) }
        val latest = startRail(sources) { it.getLatest(1) }
        railsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            // Awaited concurrently so the deadline is one 15-second window for both rails rather
            // than one after the other. These awaits are ordinary suspending waits, so unlike the
            // source calls they cancel on request.
            coroutineScope {
                val popularRail = async { awaitRail(popular) }
                val latestRail = async { awaitRail(latest) }
                _uiState.value = _uiState.value.copy(
                    // A rail where not one source answered keeps what it had. Replacing it with the
                    // empty list is how a refresh during a dropped connection wiped a home screen
                    // full of content and said nothing about why — and unlike Browse, this screen
                    // has no failure banner to say it. Stale rails with no explanation are bad;
                    // blank rails with no explanation are worse, and they lose the thing the user
                    // was about to tap.
                    popular = popularRail.await().orPrevious(_uiState.value.popular),
                    latest = latestRail.await().orPrevious(_uiState.value.latest),
                    isLoading = false,
                    // Cleared by whichever fan-out finishes, not only by one started from refresh().
                    // A registration change cancels the running job and starts a replacement, so
                    // clearing it only in refresh()'s own job would strand the indicator on screen
                    // for a pull that was superseded a frame later by an add-on finishing its
                    // install. Every path here ends in this one assignment.
                    isRefreshing = false,
                    hasLoadedOnce = true,
                )
            }
        }
    }

    // One request per source, on the detached scope, started immediately.
    //
    // null means that source did not produce a result — it threw, or it never came back. Both are
    // the same thing to the caller, which only needs to know whether *anything* answered before it
    // decides to overwrite a rail.
    private fun startRail(
        sources: List<VideoSource>,
        fetch: suspend (VideoSource) -> com.otakustream.core.sources.api.CatalogPage,
    ): List<Deferred<List<CatalogEntry>?>> = sources.map { source ->
        // Per-source, so one broken add-on cannot blank the whole rail.
        sourceScope.async {
            runCatchingCancellable { fetch(source).items.map { CatalogEntry(source.id, it) } }
                .getOrNull()
        }
    }

    // Waits out the deadline and assembles the rail; results are interleaved round-robin so a
    // single prolific source doesn't crowd the others out.
    private suspend fun awaitRail(perSource: List<Deferred<List<CatalogEntry>?>>): RailResult =
        coroutineScope {
            val results = perSource
                .map { deferred -> async { withTimeoutOrNull(RAIL_FETCH_TIMEOUT_MS) { deferred.await() } } }
                .awaitAll()
            RailResult(
                // Dedupe by (source, url) before the cap: the rails key on that pair, and a source
                // can repeat an item — a duplicate key would crash the LazyRow.
                entries = interleave(results.map { it ?: emptyList() })
                    .distinctBy { it.sourceId to it.media.url }
                    .take(RAIL_ITEM_CAP),
                // No sources registered is itself an answer: the rail is legitimately empty, and
                // holding on to what a since-removed add-on contributed would be the bug.
                // A source that answers with nothing counts as answering.
                anySourceAnswered = results.isEmpty() || results.any { it != null },
            )
        }

    private fun interleave(lists: List<List<CatalogEntry>>): List<CatalogEntry> {
        val result = mutableListOf<CatalogEntry>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (index in 0 until maxSize) {
            for (list in lists) {
                list.getOrNull(index)?.let(result::add)
            }
        }
        return result
    }
}
