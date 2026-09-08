package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.common.runCatchingCancellable
import com.otakustream.core.database.tracking.TrackingRepository
import com.otakustream.feature.tracking.AniListClient
import com.otakustream.feature.tracking.AniListListEntry
import com.otakustream.feature.tracking.AniListMedia
import com.otakustream.feature.tracking.AiringDay
import com.otakustream.feature.tracking.ReadyToWatch
import com.otakustream.feature.tracking.airingSchedule
import com.otakustream.feature.tracking.readyToWatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class AniListHomeUiState(
    val trending: List<AniListMedia> = emptyList(),
    val thisSeason: List<AniListMedia> = emptyList(),
    val allTimePopular: List<AniListMedia> = emptyList(),
    // The signed-in user's in-progress list (CURRENT/REPEATING), shown as an AniList "Continue
    // watching" rail above the discovery rails. Empty when logged out.
    val continueWatching: List<AniListListEntry> = emptyList(),
    // Shows on that list with episodes already aired past where the viewer stopped. Distinct from
    // continueWatching, which is everything in progress whether or not there is anything new: this
    // is the "something you follow has dropped" rail, and it is the one worth leading with.
    val readyToWatch: List<ReadyToWatch> = emptyList(),
    // When the next episode of each of those shows airs, grouped by day.
    val airingDays: List<AiringDay> = emptyList(),
    val isLoading: Boolean = false,
    // Separate from isLoading, which is also true during the first load — see HomeUiState. Set only
    // by refresh(), and held until *both* halves of a refresh have landed, not just the discovery
    // rails.
    val isRefreshing: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val error: String? = null,
)

// Backs the AniList-forward discovery rails on the Play tab (AnymeX-style): Trending, This Season,
// and All-Time Popular come straight from AniList with no login. When a token is present it also
// loads the user's own in-progress list. Lives in feature:sources so it can share the Play home
// with the source-based rails; the AniList client itself is in feature:tracking.
@HiltViewModel
class AniListHomeViewModel @Inject constructor(
    private val aniListClient: AniListClient,
    private val trackingRepository: TrackingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AniListHomeUiState())
    val uiState: StateFlow<AniListHomeUiState> = _uiState.asStateFlow()

    // The in-flight personal-list load, so a token change can cancel it.
    //
    // Without this, signing out (or switching accounts) started a new load while the old one was
    // still running, and whichever finished last won. The losing order is the plausible one: the
    // sign-out path returns immediately with empty state, then the previous account's request lands
    // and writes its lists back over it. The user is signed out and looking at their own data, or
    // signed in as one account and looking at another's.
    private var listJob: Job? = null

    // The same guard for the discovery rails. It did not need one while the only caller was init
    // plus a Retry button behind an error state, but a pull gesture can start a second load over a
    // first — and then two fan-outs race to write the same three rails, which is exactly the
    // last-write-wins bug listJob exists to prevent.
    private var discoveryJob: Job? = null

    // Which refresh owns the indicator. A refresh superseded by a newer one still reaches its
    // `finally`, and without this it would clear the flag the newer one had just set — leaving the
    // screen looking idle while a load was still in flight.
    private val refreshGeneration = AtomicInteger(0)

    init {
        loadDiscovery()
        // Re-load the personal rail whenever sign-in state flips (token appears/clears).
        viewModelScope.launch {
            trackingRepository.observeToken().distinctUntilChanged().collect { token ->
                loadContinueWatching(token)
            }
        }
    }

    // Both halves, together.
    //
    // A pull on this screen is asking "has anything I follow dropped?" — and that is the New
    // episodes and Airing soon rails, which come from the personal list, not from discovery.
    // Reloading only loadDiscovery() would refresh the three rails the question is *not* about and
    // leave the one it is exactly as stale as before.
    //
    // The indicator is held until both are done rather than until the first finishes, so it stops
    // when the screen has actually finished changing.
    fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        // No job field for this coordinator, and nothing cancels it: the work it waits on is
        // already guarded by discoveryJob and listJob, so a superseded coordinator finds both of
        // its jobs cancelled and returns immediately. The generation check is what stops it
        // touching the indicator on its way out.
        viewModelScope.launch {
            try {
                // Started first, so the three discovery requests are already in flight during the
                // token read below.
                val discovery = loadDiscovery()
                // getToken(), not the last value observeToken happened to have emitted. A field
                // holding that starts null and is filled asynchronously, so a pull in the first
                // moments after the screen opens read null for a signed-in user — and null here is
                // not "skip the personal rails", it is loadContinueWatching's instruction to
                // *clear* them. The gesture would have emptied the rails it was asked to refresh.
                //
                // Only reloaded when there is a token to reload it with. Signed out, the rails are
                // already empty from the observer and there is nothing to do; if the read itself
                // fails, leaving them alone is a better answer than clearing them on the strength
                // of a failed keystore call.
                val personal = readToken()?.let { loadContinueWatching(it) }
                discovery.join()
                joinPersonal(personal)
            } finally {
                if (refreshGeneration.get() == generation) {
                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                }
            }
        }
    }

    private suspend fun readToken(): String? =
        runCatchingCancellable { trackingRepository.getToken() }.getOrNull()

    // Follows the handoff if the token observer replaces the personal-list job while we are waiting
    // on it — a sign-in landing mid-pull cancels ours and starts another. Joining only the job we
    // started would let the indicator stop while its replacement was still loading, which is the
    // one thing this coordinator exists to prevent. Each turn moves to a strictly newer job, so it
    // ends as soon as sign-in state settles.
    private suspend fun joinPersonal(started: Job?) {
        // Seeded from listJob when the pull started nothing of its own. A pull that begins while
        // signed out has no personal load — but a sign-in completing while discovery is still going
        // creates one through the observer, and the indicator should wait for that too rather than
        // stopping on a screen that is still filling in.
        var awaited = started ?: listJob?.takeIf { it.isActive }
        while (awaited != null) {
            awaited.join()
            val current = listJob
            awaited = current.takeIf { it !== awaited && it?.isActive == true }
        }
    }

    private fun loadDiscovery(): Job {
        discoveryJob?.cancel()
        val job = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching {
                coroutineScope {
                    val trending = async { aniListClient.fetchTrending().media }
                    val thisSeason = async { aniListClient.fetchPopularThisSeason().media }
                    val popular = async { aniListClient.fetchAllTimePopular().media }
                    Triple(trending.await(), thisSeason.await(), popular.await())
                }
            }.onSuccess { (trending, thisSeason, popular) ->
                _uiState.value = _uiState.value.copy(
                    trending = trending,
                    thisSeason = thisSeason,
                    allTimePopular = popular,
                    isLoading = false,
                    hasLoadedOnce = true,
                    error = null,
                )
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasLoadedOnce = true,
                    error = failure.message ?: "Couldn't load AniList",
                )
            }
        }
        discoveryJob = job
        return job
    }

    // Returns the job doing the work, or null when there is nothing to load, so refresh() can wait
    // on it. Without that the indicator would stop the moment the discovery rails landed, while the
    // personal ones were still on their way.
    private fun loadContinueWatching(token: String?): Job? {
        // Cancelled on the way out too: a sign-out must not merely be overwritten later by a
        // request that was already running when it happened.
        listJob?.cancel()
        if (token == null) {
            _uiState.value = _uiState.value.copy(
                continueWatching = emptyList(),
                readyToWatch = emptyList(),
                airingDays = emptyList(),
            )
            // Cleared, not left pointing at the job just cancelled: refresh() joins whatever this
            // returns, and handing back a dead job would be indistinguishable from handing back a
            // live one.
            listJob = null
            return null
        }
        listJob = viewModelScope.launch {
            runCatching {
                val viewer = aniListClient.fetchViewer(token)
                aniListClient.fetchUserAnimeLists(token, viewer.id)
            }.onSuccess { entries ->
                // Discarded if the account changed while this was in flight. refresh() reads the
                // token and then suspends before reaching this load, so a sign-out landing in that
                // window would otherwise have this request repopulate — with the previous account's
                // list — the very rails the sign-out had just cleared.
                //
                // Read fresh rather than compared against an observed field: the observer is a
                // frame behind by construction, and this is the check that decides whether someone
                // else's watch list goes on screen.
                if (readToken() != token) return@onSuccess
                // Only the actively-watching buckets belong in a "continue" rail; most-progress first.
                val inProgress = entries
                    .filter { it.status == "CURRENT" || it.status == "REPEATING" }
                    .sortedByDescending { it.progress }
                _uiState.value = _uiState.value.copy(
                    continueWatching = inProgress,
                    // Both derived from the same `entries` that were already being fetched here and
                    // filtered down to the rail above. MEDIA_SELECTION has always requested
                    // nextAiringEpisode { episode airingAt } and AniListModels has always parsed
                    // both, so the airing data was arriving on every refresh and being discarded.
                    // These cost no extra request.
                    readyToWatch = readyToWatch(entries),
                    airingDays = airingSchedule(entries, System.currentTimeMillis()),
                )
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                // A personal-rail failure must never blank the logged-out discovery rails.
                _uiState.value = _uiState.value.copy(
                    continueWatching = emptyList(),
                    readyToWatch = emptyList(),
                    airingDays = emptyList(),
                )
            }
        }
        return listJob
    }
}
