package com.otakustream.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// The point of extracting the maths from the Palette call is that this can run at all — there is
// no device or emulator in the environment this was written in, so "the accent will be readable"
// had to become something provable rather than something eyeballed on one poster.

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

// The two scheme surfaces the accent actually gets clamped against.
private const val DARK_SURFACE = 0xFF0D0F13.toInt()
private const val LIGHT_SURFACE = 0xFFFCFAF7.toInt()

class AccentMathTest {

    // ---- luminance and contrast, against values published in WCAG ------------------------------

    @Test
    fun `luminance matches WCAG reference values`() {
        assertEquals(0.0, relativeLuminance(BLACK), 1e-9)
        assertEquals(1.0, relativeLuminance(WHITE), 1e-9)
        assertEquals(0.2126, relativeLuminance(0xFFFF0000.toInt()), 1e-4)
        assertEquals(0.7152, relativeLuminance(0xFF00FF00.toInt()), 1e-4)
        assertEquals(0.0722, relativeLuminance(0xFF0000FF.toInt()), 1e-4)
    }

    @Test
    fun `contrast of black on white is twenty-one to one`() {
        assertEquals(21.0, contrastRatio(BLACK, WHITE), 1e-6)
        assertEquals(21.0, contrastRatio(WHITE, BLACK), 1e-6)
    }

    @Test
    fun `contrast of a colour with itself is one to one`() {
        assertEquals(1.0, contrastRatio(DARK_SURFACE, DARK_SURFACE), 1e-9)
    }

    @Test
    fun `alpha is ignored`() {
        assertEquals(relativeLuminance(0xFFFF0000.toInt()), relativeLuminance(0x00FF0000), 1e-9)
    }

    // ---- clamping ------------------------------------------------------------------------------

    @Test
    fun `a colour that already has contrast is returned untouched`() {
        val gold = 0xFFF0C05A.toInt()
        assertEquals(gold, clampForContrast(gold, DARK_SURFACE, TEXT_CONTRAST))
    }

    // The failure this whole file exists to prevent: a dark poster giving a dark accent that
    // vanishes into a dark page.
    @Test
    fun `a dark colour on a dark surface is lightened until it is readable`() {
        val darkBrown = 0xFF2A1A0C.toInt()
        assertTrue(contrastRatio(darkBrown, DARK_SURFACE) < TEXT_CONTRAST)
        val clamped = nonNull(clampForContrast(darkBrown, DARK_SURFACE, TEXT_CONTRAST))
        assertTrue(contrastRatio(clamped, DARK_SURFACE) >= TEXT_CONTRAST)
    }

    // And the same failure in the other scheme, which is the one designed without a screen to
    // check it on.
    @Test
    fun `a pale colour on a light surface is darkened until it is readable`() {
        val paleYellow = 0xFFFFF6C8.toInt()
        assertTrue(contrastRatio(paleYellow, LIGHT_SURFACE) < TEXT_CONTRAST)
        val clamped = nonNull(clampForContrast(paleYellow, LIGHT_SURFACE, TEXT_CONTRAST))
        assertTrue(contrastRatio(clamped, LIGHT_SURFACE) >= TEXT_CONTRAST)
    }

    @Test
    fun `clamping meets the ratio for a wide spread of inputs on both surfaces`() {
        val hues = 0 until 360 step 15
        val saturations = listOf(0.2, 0.5, 0.8, 1.0)
        val lightnesses = listOf(0.05, 0.2, 0.4, 0.5, 0.6, 0.8, 0.95)
        var checked = 0
        for (h in hues) for (s in saturations) for (l in lightnesses) {
            val color = hslToArgb(h.toDouble(), s, l)
            for (surface in listOf(DARK_SURFACE, LIGHT_SURFACE)) {
                val clamped = clampForContrast(color, surface, TEXT_CONTRAST)
                    ?: continue // rescue genuinely impossible; pickAccent falls back
                val ratio = contrastRatio(clamped, surface)
                assertTrue(
                    "hsl($h, $s, $l) on ${surface.hex()} clamped to ${clamped.hex()} at %.2f:1".format(ratio),
                    ratio >= TEXT_CONTRAST,
                )
                checked++
            }
        }
        // Guards against the loop silently `continue`-ing its way to a vacuous pass.
        assertTrue("only $checked cases actually asserted", checked > 500)
    }

    // The reason for clamping rather than substituting: the show's colour is supposed to survive.
    @Test
    fun `clamping preserves hue`() {
        val teal = 0xFF0B3B38.toInt()
        val clamped = nonNull(clampForContrast(teal, DARK_SURFACE, TEXT_CONTRAST))
        val before = teal.toHsl().first
        val after = clamped.toHsl().first
        assertTrue("hue moved from $before to $after", hueDistance(before, after) < 2.0)
    }

    @Test
    fun `clamping moves as little as it has to`() {
        val dim = 0xFF6B4A18.toInt()
        val clamped = nonNull(clampForContrast(dim, DARK_SURFACE, DECORATIVE_CONTRAST))
        // One step back towards the original must fail the ratio, or it moved further than needed.
        val (h, s, l) = clamped.toHsl()
        val originalL = dim.toHsl().third
        val backwards = hslToArgb(h, s, if (l > originalL) l - 0.02 else l + 0.02)
        assertTrue(contrastRatio(backwards, DARK_SURFACE) < DECORATIVE_CONTRAST)
    }

    // Asked for AAA rather than AA, because AA cannot be refused: white is 3.95:1 from mid-grey and
    // black is 5.32:1, so one of them always lands. Raising the bar to 7:1 puts both out of reach
    // and exercises the path that gives up.
    @Test
    fun `a colour that cannot be rescued returns null`() {
        val midGrey = 0xFF7F7F7F.toInt()
        assertNull(clampForContrast(midGrey, midGrey, 7.0))
    }

    // The counterpart, and the reason the app never actually sees that null: at the ratio it asks
    // for, every colour on every background is rescuable, because white and black between them
    // cover the whole range. Worth pinning down — it is why a poster can only fail to yield an
    // accent by being grey, not by being dark.
    @Test
    fun `at the ratio the app asks for, every colour is rescuable`() {
        val surfaces = listOf(DARK_SURFACE, LIGHT_SURFACE, 0xFF7F7F7F.toInt(), 0xFF808080.toInt())
        for (h in 0 until 360 step 30) for (s in listOf(0.15, 0.5, 1.0)) for (l in listOf(0.02, 0.5, 0.98)) {
            val color = hslToArgb(h.toDouble(), s, l)
            surfaces.forEach { surface ->
                assertNotNull(
                    "hsl($h, $s, $l) could not be rescued on ${surface.hex()}",
                    clampForContrast(color, surface, TEXT_CONTRAST),
                )
            }
        }
    }

    // ---- picking -------------------------------------------------------------------------------

    @Test
    fun `a black and white poster falls back rather than yielding a grey accent`() {
        val greys = listOf(
            AccentCandidate(0xFF1A1A1A.toInt(), population = 9000, vibrant = false),
            AccentCandidate(0xFF808080.toInt(), population = 5000, vibrant = false),
            AccentCandidate(0xFFEFEFEF.toInt(), population = 2000, vibrant = true),
        )
        assertNull(pickAccent(greys, DARK_SURFACE))
    }

    @Test
    fun `vibrant beats muted at equal coverage`() {
        val muted = 0xFF7A6A55.toInt()
        val vibrant = 0xFFE0522A.toInt()
        val picked = pickAccent(
            listOf(
                AccentCandidate(muted, population = 1000, vibrant = false),
                AccentCandidate(vibrant, population = 1000, vibrant = true),
            ),
            DARK_SURFACE,
        )
        assertEquals(vibrant.toHsl().first, nonNull(picked).toHsl().first, 1.0)
    }

    // Vibrance is a thumb on the scale, not a veto — a swatch covering most of the poster still
    // wins.
    @Test
    fun `coverage still wins when it is overwhelming`() {
        val muted = 0xFF7A6A55.toInt()
        val vibrant = 0xFFE0522A.toInt()
        val picked = pickAccent(
            listOf(
                AccentCandidate(muted, population = 10_000, vibrant = false),
                AccentCandidate(vibrant, population = 1000, vibrant = true),
            ),
            DARK_SURFACE,
        )
        assertEquals(muted.toHsl().first, nonNull(picked).toHsl().first, 1.0)
    }

    @Test
    fun `an empty poster falls back`() {
        assertNull(pickAccent(emptyList(), DARK_SURFACE))
        assertNull(pickAccent(listOf(AccentCandidate(0xFFE0522A.toInt(), 0, true)), DARK_SURFACE))
    }

    @Test
    fun `whatever is picked clears the ratio it was asked for`() {
        val poster = listOf(
            AccentCandidate(0xFF120A05.toInt(), population = 8000, vibrant = false),
            AccentCandidate(0xFF3A1C0B.toInt(), population = 4000, vibrant = true),
        )
        val picked = nonNull(pickAccent(poster, DARK_SURFACE, TEXT_CONTRAST))
        assertTrue(contrastRatio(picked, DARK_SURFACE) >= TEXT_CONTRAST)
    }

    // ---- ink on an accent ------------------------------------------------------------------------

    @Test
    fun `ink on an accent is always readable`() {
        for (h in 0 until 360 step 15) for (s in listOf(0.2, 0.6, 1.0)) for (l in listOf(0.1, 0.3, 0.5, 0.7, 0.9)) {
            val fill = hslToArgb(h.toDouble(), s, l)
            val ink = onAccentFor(fill)
            val ratio = contrastRatio(fill, ink)
            assertTrue(
                "ink on hsl($h, $s, $l) is only %.2f:1".format(ratio),
                ratio >= TEXT_CONTRAST,
            )
        }
    }

    @Test
    fun `ink is dark on a bright accent and light on a dark one`() {
        assertEquals(0xFF000000.toInt(), onAccentFor(0xFFF0C05A.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), onAccentFor(0xFF3A1C0B.toInt()))
    }

    // ---- colour space round trip ---------------------------------------------------------------

    @Test
    fun `rgb to hsl and back is lossless within rounding`() {
        val samples = listOf(
            0xFFF0C05A, 0xFF0D0F13, 0xFFFCFAF7, 0xFFB2182C, 0xFF4A6FA5,
            0xFF000000, 0xFFFFFFFF, 0xFF7F7F7F, 0xFF00FF00, 0xFF123456,
        ).map { it.toInt() }
        samples.forEach { original ->
            val (h, s, l) = original.toHsl()
            val roundTripped = hslToArgb(h, s, l)
            assertEquals("red of ${original.hex()}", original.red(), roundTripped.red())
            assertEquals("green of ${original.hex()}", original.green(), roundTripped.green())
            assertEquals("blue of ${original.hex()}", original.blue(), roundTripped.blue())
        }
    }

    @Test
    fun `greys report zero saturation`() {
        listOf(0xFF000000, 0xFF404040, 0xFF7F7F7F, 0xFFFFFFFF).forEach {
            assertEquals(0.0, it.toInt().toHsl().second, 1e-9)
        }
    }
}

private fun hueDistance(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

private fun Int.hex(): String = "#%08X".format(this)

// JUnit's assertNotNull returns Unit, so this stands in for it and hands back the value, letting
// a test assert on what came out instead of re-null-checking it.
private fun <T : Any> nonNull(value: T?): T {
    org.junit.Assert.assertNotNull(value)
    return value!!
}
