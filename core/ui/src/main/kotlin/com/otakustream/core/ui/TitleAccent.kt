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
import kotlinx.coroutines.Dispatchers
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

    fun peek(url: String?, against: Int): Color? {
        if (url.isNullOrBlank()) return null
        return entries.get(key(url, against))?.argb?.let(::Color)
    }

    suspend fun resolve(context: Context, url: String, against: Int): Color? {
        val cacheKey = key(url, against)
        entries.get(cacheKey)?.let { return it.argb?.let(::Color) }

        // The two ways this comes back empty are not the same and must not be cached the same way.
        //
        // A bitmap we could not fetch is a dead host, a flaky connection, a decode that ran out of
        // memory — all transient, all worth trying again next time the page is opened. Storing that
        // as "this poster has no accent" would freeze a temporary failure in for the life of the
        // cache entry, so a missing bitmap returns without recording anything.
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { bitmapFor(context, url) }.getOrNull()
        } ?: return null

        // Having looked at the pixels, the answer is final: a grey poster will be grey every time,
        // so a null here is a real result and gets cached like any other. Without that, every visit
        // to a monochrome cover would decode it again to learn the same thing.
        val argb = withContext(Dispatchers.Default) {
            runCatching { pickAccent(bitmap.candidates(), against) }.getOrNull()
        }
        entries.put(cacheKey, Entry(argb))
        return argb?.let(::Color)
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
