package com.otakustream.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
// Misses are cached too, as a null result. A grey poster yields nothing, and without recording that
// the app would re-decode it on every visit to learn the same thing again.
private object AccentCache {

    private val entries = LruCache<String, Entry>(CACHE_SIZE)

    private class Entry(val argb: Int?)

    // One extraction per key at a time, shared by everyone who asks for it while it runs.
    //
    // Without this, every caller that missed the cache started its own decode of the same cover.
    // That happens for real: a details screen and the rail it was opened from ask together, a
    // rotation asks again before the first answer lands, and the whole point of the cache is that
    // this work happens once.
    private val inFlight = mutableMapOf<String, Deferred<Int?>>()
    private val mutex = Mutex()

    // The shared work runs here rather than on whichever caller happened to arrive first.
    //
    // That is the part it would be easy to get wrong: if the extraction ran on the first caller's
    // scope, a user who opened a page and immediately went back would cancel it — and every other
    // caller awaiting the same result would be failed by a navigation that had nothing to do with
    // them. On a scope of its own, cancelling a caller cancels only its own await.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun peek(url: String?, against: Int): Color? {
        if (url.isNullOrBlank()) return null
        return entries.get(key(url, against))?.argb?.let(::Color)
    }

    suspend fun resolve(context: Context, url: String, against: Int): Color? {
        val cacheKey = key(url, against)
        entries.get(cacheKey)?.let { return it.argb?.let(::Color) }

        val work = mutex.withLock {
            // Checked again under the lock: between the fast path above and here, another caller's
            // extraction may have finished and filled the cache.
            entries.get(cacheKey)?.let { return it.argb?.let(::Color) }
            inFlight.getOrPut(cacheKey) {
                // The application context, not the caller's.
                //
                // The job outlives whoever started it — that is the point of it — so capturing the
                // Activity here would hold a destroyed one alive for the length of a network fetch
                // and a decode every time someone opened a details screen and immediately left.
                // Moving the work off the caller's scope is what created that: while it ran on the
                // caller, the capture died with the caller.
                val appContext = context.applicationContext
                scope.async { extract(appContext, url, against, cacheKey) }
            }
        }
        // The shared job is never cancelled by a caller leaving, but it can still fail; a caller
        // that gets nothing renders on the theme colour, which is the same as a poster with no
        // usable colour in it.
        //
        // Cancellation is not one of those failures and must not be swallowed. await() throws
        // CancellationException when *this* caller is cancelled — navigating away, or the cover
        // changing under it — and letting runCatching turn that into null would resume a composition
        // effect that has been cancelled and have it publish an answer nobody is waiting for.
        return runCatching { work.await() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.let(::Color)
    }

    private suspend fun extract(context: Context, url: String, against: Int, cacheKey: String): Int? {
        try {
            // The two ways this comes back empty are not the same and must not be cached the same
            // way.
            //
            // A bitmap we could not fetch is a dead host, a flaky connection, a decode that ran out
            // of memory — all transient, all worth trying again next time the page is opened.
            // Storing that as "this poster has no accent" would freeze a temporary failure in for
            // the life of the cache entry, so a missing bitmap returns without recording anything.
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { bitmapFor(context, url) }.getOrNull()
            } ?: return null

            // Same distinction one level down. Quantising can throw — an OOM on a large bitmap, a
            // recycled one — and that is a failure to look, not a poster with no colour in it, so
            // it must not be recorded either. Only a run that completed produces a cacheable
            // answer.
            //
            // A completed run that found nothing *is* cacheable: a grey poster will be grey every
            // time, and without recording that, every visit to a monochrome cover would decode it
            // again to learn the same thing.
            val extraction = withContext(Dispatchers.Default) {
                runCatching { pickAccent(bitmap.candidates(), against) }
            }
            if (extraction.isFailure) return null
            val argb = extraction.getOrNull()
            entries.put(cacheKey, Entry(argb))
            return argb
        } finally {
            // NonCancellable because this suspends: a cancelled job that skipped this would leave a
            // dead Deferred in the map, and every later request for that cover would await a result
            // that is never coming.
            withContext(NonCancellable) { mutex.withLock { inFlight.remove(cacheKey) } }
        }
    }

    private fun key(url: String, against: Int) = "$url|$against"

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
