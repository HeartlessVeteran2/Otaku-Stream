package com.otakustream.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.otakustream.core.common.InFlightCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

// The colour of the thing you are looking at, applied to the page around it.
//
// The app is a grid of other people's artwork, so the chrome is deliberately neutral (see the note
// in the app's Color.kt) and the colour comes from the show instead. On a detail screen the hero
// gradient, the primary action and the selected chips take a colour pulled out of the poster; the
// background, the surfaces and every piece of body text stay on the fixed scheme.
//
// That division is the safety property, not a stylistic choice. Text legibility never depends on
// what a cover happens to look like, because text is never accented. The accent only ever appears
// where being a bit wrong costs nothing, and AccentMath.kt guarantees even that is readable.
//
// Screens that provide nothing render exactly as they did before — titleAccent() falls back to the
// theme's own primary, so this is opt-in per screen rather than something that changes the app.
val LocalTitleAccent: ProvidableCompositionLocal<Color?> = staticCompositionLocalOf { null }

// The accent for the current screen, or the theme's primary where there isn't one.
@Composable
fun titleAccent(): Color = LocalTitleAccent.current ?: MaterialTheme.colorScheme.primary

// Black or white, whichever is readable on top of the current accent — for a label sitting on an
// accented fill, such as a selected chip.
//
// Not the theme's onPrimary: that is matched to the theme's primary, and the moment the fill under
// it becomes a poster's colour instead, the pairing is a guess. This one is computed from the fill
// actually being drawn.
@Composable
fun onTitleAccent(): Color = Color(onAccentFor(titleAccent().toArgb()))

// Extracts an accent from `coverUrl` and provides it to `content`.
//
// Starts on the theme colour and animates to the extracted one, so a page opens in the right colour
// immediately and settles into the show's as the artwork arrives — rather than flashing a different
// colour a moment after you have started reading.
@Composable
fun ProvideTitleAccent(coverUrl: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val fallback = MaterialTheme.colorScheme.primary
    // Clamped against the surface the accent will actually sit on, so the answer is scheme-specific
    // and changes when the user switches between light and dark.
    val against = MaterialTheme.colorScheme.surface.toArgb()

    var extracted by remember(coverUrl, against) {
        mutableStateOf(AccentCache.peek(coverUrl, against))
    }
    LaunchedEffect(coverUrl, against) {
        if (coverUrl.isNullOrBlank()) return@LaunchedEffect
        // Unconditional, and resolve() answers from the cache when it can. The earlier version
        // asked "is it cached?" and returned without assigning when it was — which dropped the
        // answer whenever another screen filled that key between this composition and this effect
        // running, leaving the page on the theme colour with the accent sitting in the cache.
        extracted = AccentCache.resolve(context, coverUrl, against)
    }

    val target = extracted ?: fallback
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = ACCENT_FADE_MS),
        label = "titleAccent",
    )
    CompositionLocalProvider(LocalTitleAccent provides animated, content = content)
}

// Remembers what each poster yielded, so scrolling back to a show does not decode its cover again.
//
// Keyed on the surface as well as the url: the same poster clamps to a different colour in the
// light and dark schemes, and caching only by url would hand the dark answer to the light theme
// the first time someone switched.
//
// The sharing and caching are InFlightCache's, not this file's.
//
// They used to be hand-rolled here — an LruCache, a mutex, a map of Deferreds, a NonCancellable
// eviction in a finally block — reimplementing a class in core/common whose own header calls
// duplicating it "a standing hazard", written because two source adapters had each grown a copy and
// the copies had drifted.
//
// To be accurate about what was wrong with the version this replaces: it was not broken. Its
// shared job started eagerly and could in principle finish before the map entry existed, but the
// eviction ran under the same mutex that was held across the insertion, so it could not actually
// get ahead of it. That is the problem, though — it was correct by lock ordering, in a file about
// palette extraction, where the next person to touch either half has to reconstruct the argument.
// InFlightCache is correct by construction: LAZY jobs started only after insertion, eviction by
// identity so a fresh attempt survives an older job's completion, and a test suite, none of which
// this file now has to carry.
//
// Misses are cached, transient failures are not, and the difference is carried by whether `extract`
// returns or throws — see AccentUnavailable.
private object AccentCache {

    private data class Key(val url: String, val against: Int)

    // Signals "we could not look", as distinct from "we looked and there was nothing".
    //
    // InFlightCache stores what its producer returns and evicts what it throws, which is exactly the
    // distinction this needs. A poster that failed to fetch or decode is a dead host, a flaky
    // connection, an OOM — all transient, all worth retrying the next time the page is opened — so
    // that path throws and nothing is recorded. A poster that was successfully read and simply has
    // no usable colour in it returns null, and null is cached: a grey cover will be grey every time,
    // and without recording that, every visit would decode it again to learn the same thing.
    private class AccentUnavailable : Exception(null, null, false, false)

    // The shared work runs here rather than on whichever caller happened to arrive first.
    //
    // That is the part it would be easy to get wrong: if the extraction ran on the first caller's
    // scope, a user who opened a page and immediately went back would cancel it — and every other
    // caller awaiting the same result would be failed by a navigation that had nothing to do with
    // them. On a scope of its own, cancelling a caller cancels only its own await.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Built on first use because the producer needs an application Context and this object has none.
    //
    // Every caller's applicationContext is the same singleton, so which one wins the race to build
    // this does not matter; double-checked only so the losers reuse the winner's cache rather than
    // each getting their own.
    @Volatile
    private var cache: InFlightCache<Key, Int?>? = null

    private fun cacheFor(context: Context): InFlightCache<Key, Int?> =
        cache ?: synchronized(this) {
            cache ?: run {
                val appContext = context.applicationContext
                InFlightCache<Key, Int?>(
                    scope = scope,
                    maxEntries = CACHE_SIZE,
                    // Effectively no expiry. A TTL is for answers that go stale, and this one cannot:
                    // the key is a url, and the image at a url does not change into a different image.
                    // What bounds the memory here is maxEntries, as it did when this was an LruCache.
                    ttlMs = Long.MAX_VALUE,
                ) { key -> extract(appContext, key) }.also { cache = it }
            }
        }

    fun peek(url: String?, against: Int): Color? {
        if (url.isNullOrBlank()) return null
        // Not cacheFor(): peek has no context and must not build anything. Before the first resolve
        // there is nothing cached to answer with anyway.
        return cache?.peek(Key(url, against))?.let(::Color)
    }

    suspend fun resolve(context: Context, url: String, against: Int): Color? {
        // The shared job is never cancelled by a caller leaving, but it can still fail; a caller
        // that gets nothing renders on the theme colour, which is the same as a poster with no
        // usable colour in it.
        //
        // Cancellation is not one of those failures and must not be swallowed. get() throws
        // CancellationException when *this* caller is cancelled — navigating away, or the cover
        // changing under it — and letting runCatching turn that into null would resume a composition
        // effect that has been cancelled and have it publish an answer nobody is waiting for.
        return runCatching { cacheFor(context).get(Key(url, against)) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.let(::Color)
    }

    private suspend fun extract(context: Context, key: Key): Int? {
        // The two ways this comes back empty are not the same and must not be recorded the same way
        // — see AccentUnavailable. A bitmap we could not fetch is transient, so it throws rather
        // than returning null, and nothing is cached for that key.
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { bitmapFor(context, key.url) }.getOrNull()
        } ?: throw AccentUnavailable()

        // Same distinction one level down. Quantising can throw — an OOM on a large bitmap, a
        // recycled one — and that is a failure to look, not a poster with no colour in it.
        val extraction = withContext(Dispatchers.Default) {
            runCatching { pickAccent(bitmap.candidates(), key.against) }
        }
        if (extraction.isFailure) throw AccentUnavailable()
        // A completed run that found nothing is a real answer, and returning it is what caches it.
        return extraction.getOrNull()
    }

    private suspend fun bitmapFor(context: Context, url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            // Palette reads pixels back, which a HARDWARE bitmap will not allow. Without this the
            // extraction throws on every device that hardware-decodes, which is most of them.
            .allowHardware(false)
            // A poster's colours do not need its resolution. Decoding to 128px is roughly a
            // hundredth of the pixels and gives the quantiser the same answer.
            .size(SAMPLE_SIZE)
            // Not kept in Coil's memory cache. This is a second, smaller decode of an image the
            // screen is also displaying at full size — allowHardware(false) alone already makes it
            // a separate entry — and it is read exactly once, since the answer is then cached here
            // as a single Int. Keeping it would evict real poster bitmaps to store something
            // nothing will ask for again. The network fetch is still shared: the disk cache is
            // untouched, so this costs a decode, not a download.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = context.imageLoader.execute(request)
        return ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
    }

    // Palette's own swatches, translated into the shape AccentMath judges. Everything Palette found
    // is offered rather than just its "vibrant" pick — the vibrant swatch is often absent on a muted
    // poster, and the decision about what is usable belongs to code that can be tested.
    private fun Bitmap.candidates(): List<AccentCandidate> {
        val palette = Palette.from(this).clearFilters().generate()
        val vibrant = setOfNotNull(
            palette.vibrantSwatch,
            palette.lightVibrantSwatch,
            palette.darkVibrantSwatch,
        )
        return palette.swatches.map { swatch ->
            AccentCandidate(
                argb = swatch.rgb,
                population = swatch.population,
                vibrant = swatch in vibrant,
            )
        }
    }
}

// Long enough to read as the page settling rather than a colour glitch, short enough that it has
// finished before you have finished looking at the hero.
private const val ACCENT_FADE_MS = 450

private const val SAMPLE_SIZE = 128

// Enough for a long browsing session's worth of detail screens in both schemes, at one boxed Int
// each — the memory here is the map, not the images, which Coil owns.
private const val CACHE_SIZE = 64
