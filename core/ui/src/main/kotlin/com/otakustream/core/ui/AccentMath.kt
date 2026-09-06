package com.otakustream.core.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Deciding whether a colour pulled out of a poster is safe to actually use.
//
// This is the half of cover-art theming that goes wrong. Extraction is easy and Palette does it
// for us; what it hands back is the poster's dominant colours, which is not the same question as
// "what colour can this app draw on a near-black page and still be read". A dark-brown poster
// yields a dark brown. A black-and-white one yields a grey that isn't an accent at all, just a
// slightly different shade of the background. Shipping either is worse than having no per-title
// colour, because the failure mode is text you cannot read rather than a page that looks plain.
//
// So everything below is arithmetic on ARGB ints, with no Android types anywhere near it, and it
// is unit-tested. The Android part — getting a bitmap and asking Palette for swatches — lives in
// TitleAccent.kt and does no judging of its own.

// WCAG AA for body text. Anything an accent is drawn *behind*, or drawn *as*, has to clear this.
const val TEXT_CONTRAST = 4.5

// WCAG AA for large text and graphical objects: the accent used as a fill with nothing on it —
// a progress bar, the tint in a gradient — only has to be distinguishable from its surroundings.
const val DECORATIVE_CONTRAST = 3.0

// One colour Palette found, with how much of the image it covers. `vibrant` marks the swatches
// Palette itself judged saturated; they win ties against muted ones of the same size because a
// poster's muted swatch is usually its background, and its background is usually the thing that
// looks most like ours.
data class AccentCandidate(val argb: Int, val population: Int, val vibrant: Boolean)

// WCAG 2.1 relative luminance. Alpha is ignored: everything here is an opaque colour from an
// opaque poster, and silently compositing a translucent one against an assumed backdrop would
// give an answer that is right about nothing.
fun relativeLuminance(argb: Int): Double {
    fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(argb.red()) + 0.7152 * channel(argb.green()) + 0.0722 * channel(argb.blue())
}

fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

// Walks a colour's lightness until it stands off `against` by at least `minRatio`, keeping its hue
// and saturation — which is the whole point: the show's colour should survive, only its brightness
// negotiated.
//
// The direction is decided by which side of `against` there is room on, and both are tried, because
// neither is always right. A mid-grey background has room in both directions and the nearer edge
// wins; a colour on a near-black page can only go lighter. Contrast against a fixed colour is
// monotonic in lightness once you are moving away from it, so stepping is enough and a search would
// only be a more obscure way to get the same number.
//
// Returns null when neither direction gets there. At the ratios this app asks for, that does not
// happen: a colour's lightness converges to black at 0 and white at 1 whatever its saturation, and
// there is no background at all that both white and black fail 4.5:1 against — the two conditions
// for that are L > 0.183 and L < 0.175 simultaneously. So the fallback in pickAccent is reached by
// the saturation filter, essentially never by this. The null is still real and still returned,
// because the function has to be total and a caller asking for AAA (7:1) can genuinely be refused.
fun clampForContrast(argb: Int, against: Int, minRatio: Double): Int? {
    if (contrastRatio(argb, against) >= minRatio) return argb
    val (h, s, l) = argb.toHsl()
    val lighter = searchLightness(h, s, l, against, minRatio, step = LIGHTNESS_STEP)
    val darker = searchLightness(h, s, l, against, minRatio, step = -LIGHTNESS_STEP)
    return when {
        lighter != null && darker != null ->
            // Both work; take whichever moved less, so the colour stays closer to the poster's.
            if (abs(lighter.second - l) <= abs(darker.second - l)) lighter.first else darker.first
        else -> lighter?.first ?: darker?.first
    }
}

// Picks the colour to use, or null if nothing in the poster is usable.
//
// Two rejections, both deliberate:
//   * anything below MIN_SATURATION, because a grey "accent" is not an accent — on a neutral base
//     it is indistinguishable from the chrome it is meant to be tinting, and a monochrome poster
//     should get the theme's own colour rather than a pointless grey one;
//   * anything clampForContrast can't rescue.
//
// Among what survives, the score is coverage, with vibrant swatches doubled. Coverage alone picks
// a poster's background — usually the largest region and usually the least characteristic colour
// in it.
fun pickAccent(candidates: List<AccentCandidate>, against: Int, minRatio: Double = DECORATIVE_CONTRAST): Int? =
    candidates
        .asSequence()
        .filter { it.population > 0 }
        .filter { it.argb.toHsl().second >= MIN_SATURATION }
        .sortedByDescending { it.population * if (it.vibrant) VIBRANT_WEIGHT else 1.0 }
        .mapNotNull { clampForContrast(it.argb, against, minRatio) }
        .firstOrNull()

// Black or white, whichever is more readable on `argb`. For labels drawn on top of an accent fill.
//
// Two choices rather than a computed shade on purpose: black and white are the two extremes, so
// whichever of them wins is the most readable colour there is, and picking between them cannot
// produce a near-miss the way nudging a tint towards legibility can. The empty dead band described
// above is what makes this always adequate — there is no fill at all that both fail 4.5:1 against.
fun onAccentFor(argb: Int): Int =
    if (contrastRatio(argb, BLACK) >= contrastRatio(argb, WHITE)) BLACK else WHITE

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

// ---------------------------------------------------------------------------------------------
// Colour space plumbing. Hand-rolled rather than android.graphics.Color.colorToHSV, which is a
// stubbed method on the JVM and throws "not mocked" — the whole reason this file avoids Android
// types is so its tests can run without a device.
// ---------------------------------------------------------------------------------------------

internal fun Int.red(): Int = (this shr 16) and 0xFF
internal fun Int.green(): Int = (this shr 8) and 0xFF
internal fun Int.blue(): Int = this and 0xFF

// Hue in degrees 0..360, saturation and lightness 0..1.
internal fun Int.toHsl(): Triple<Double, Double, Double> {
    val r = red() / 255.0
    val g = green() / 255.0
    val b = blue() / 255.0
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    val delta = maxC - minC
    val l = (maxC + minC) / 2.0
    if (delta == 0.0) return Triple(0.0, 0.0, l)
    val s = delta / (1.0 - abs(2.0 * l - 1.0))
    val h = when (maxC) {
        r -> 60.0 * ((g - b) / delta % 6.0)
        g -> 60.0 * ((b - r) / delta + 2.0)
        else -> 60.0 * ((r - g) / delta + 4.0)
    }
    return Triple(if (h < 0) h + 360.0 else h, s, l)
}

internal fun hslToArgb(h: Double, s: Double, l: Double): Int {
    val c = (1.0 - abs(2.0 * l - 1.0)) * s
    // Named rather than inlined: `h / 60.0 % 2.0 - 1.0` is correct by precedence but reads as a
    // pile of operators, and this is the value the formula is actually about — where the hue sits
    // within its 60-degree sector.
    val sector = h / 60.0
    val x = c * (1.0 - abs(sector % 2.0 - 1.0))
    val m = l - c / 2.0
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0.0)
        h < 120 -> Triple(x, c, 0.0)
        h < 180 -> Triple(0.0, c, x)
        h < 240 -> Triple(0.0, x, c)
        h < 300 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    fun byte(v: Double) = ((v + m).coerceIn(0.0, 1.0) * 255.0).roundToInt()
    return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
}

// Steps lightness one way until the ratio is met, returning the colour and the lightness it landed
// on (the caller needs the distance travelled to choose between the two directions).
private fun searchLightness(
    h: Double,
    s: Double,
    from: Double,
    against: Int,
    minRatio: Double,
    step: Double,
): Pair<Int, Double>? {
    val limit = if (step > 0) 1.0 else 0.0
    var l = from + step
    while (l in 0.0..1.0) {
        val candidate = hslToArgb(h, s, l)
        if (contrastRatio(candidate, against) >= minRatio) return candidate to l
        l += step
    }
    // The loop walks off the end without ever standing on it — from 0.498 in 0.01 steps the last
    // value inside range is 0.998, and 1.0 is where the colour actually becomes white. Skipping the
    // boundary would reject a rescue that white performs perfectly well, so it is tried explicitly.
    val edge = hslToArgb(h, s, limit)
    return if (contrastRatio(edge, against) >= minRatio) edge to limit else null
}

// 1% steps: fine enough that the result is never visibly further from the poster's colour than it
// had to be, coarse enough that the worst case is a hundred iterations of cheap arithmetic.
//
// internal rather than private so the test that asserts the clamp moves as little as it has to can
// step back by exactly this, instead of by a number that happens to match it today.
internal const val LIGHTNESS_STEP = 0.01

// Below this a colour reads as grey rather than as a hue. Chosen low: the goal is only to reject
// colours that carry no identity, not to insist a poster be brightly coloured.
private const val MIN_SATURATION = 0.15

// A vibrant swatch has to be beaten on coverage by 2:1 before a muted one takes it.
private const val VIBRANT_WEIGHT = 2.0
