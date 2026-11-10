package com.starwake.core.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Roblox flight arrow (02-flight.md 3.9 and 6.1, DECISIONS 20): the spec's examples. */
class FlightArrowTest {
    private val arrow = FlightArrow()

    private fun rgb(c: Int) = c and 0xFFFFFF

    private fun alpha(c: Int) = (c ushr 24) / 255f

    @Test
    fun halfADeflectionToTheRight() {
        arrow.setUnit(1f)
        arrow.update(0.5f, 0f)
        assertTrue(arrow.shown)
        assertEquals(64f, arrow.offsetX, "(16 + 96 x 0.5) design px")
        assertEquals(0f, arrow.offsetY)
        assertEquals(90f, arrow.rotationDeg, "tip to the right")
        assertFalse(arrow.idle, "outside the dead zone: its own colours")
        assertEquals(0xFFC93C, rgb(arrow.halfColor(true, 1f)))
        assertEquals(0xF07F1A, rgb(arrow.halfColor(false, 1f)))
        // the dot gives its light to the arrow: step 6 = (192, 90, 31) at 48 % transparency
        assertEquals(6, arrow.dotStep)
        assertEquals(0xC05A1F, rgb(arrow.dotColor(1f)))
        assertEquals(0.52f, alpha(arrow.dotColor(1f)), 0.003f)
    }

    @Test
    fun aSmallDeflectionUpIsIdle() {
        arrow.setUnit(1f)
        arrow.update(0f, -0.1f)
        assertTrue(arrow.shown)
        assertEquals(0f, arrow.offsetX)
        assertEquals(-26f, arrow.offsetY, "16 + 9.6 = 25.6, rounded away from zero")
        assertEquals(0f, arrow.rotationDeg, "tip up")
        assertTrue(arrow.idle)
        // the idle tint makes the yellow half exactly ArrowIdle, at 30 % transparency
        assertEquals(0x962416, rgb(arrow.halfColor(true, 1f)))
        assertEquals(0.7f, alpha(arrow.halfColor(true, 1f)), 0.003f)
        assertEquals(0, arrow.dotStep, "the dot stays bright in the dead zone")
        assertEquals(0xFFAA2D, rgb(arrow.dotColor(1f)))
    }

    @Test
    fun belowFourPercentOnlyTheDotShows() {
        arrow.setUnit(1f)
        arrow.update(0.03f, 0f)
        assertFalse(arrow.shown)
        arrow.update(0f, 0f)
        assertFalse(arrow.shown)
    }

    @Test
    fun fullDeflectionDownLeftAndBeyondTheCircle() {
        arrow.setUnit(1f)
        arrow.update(-2f, 2f) // beyond the unit circle: the distance stops at ArrowRange
        val d = 112.0 / kotlin.math.sqrt(2.0)
        assertEquals(-Math.round(d).toFloat(), arrow.offsetX)
        assertEquals(Math.round(d).toFloat(), arrow.offsetY)
        assertEquals(225f, arrow.rotationDeg, "atan2(1, -1) = 135 degrees + 90")
        assertEquals(10, arrow.dotStep)
    }

    @Test
    fun sizesFollowTheUnitWithThePhoneClamps() {
        // the image Roblox sizes: 24 x 24 px at unit 1, 26 x 28 px at 1.111 (2400 x 1200)
        arrow.setUnit(1f)
        assertEquals(24f, arrow.imageWidth)
        assertEquals(24f, arrow.imageHeight)
        assertEquals(4f, arrow.dotSize)
        arrow.setUnit(1.111f)
        assertEquals(26f, arrow.imageWidth)
        assertEquals(28f, arrow.imageHeight)
        // the triangle is 184/256 x 207/256 of the image: 17 x 20 design px
        arrow.setUnit(1f)
        assertEquals(24f * 184f / 256f, arrow.arrowWidth, 1e-4f)
        // phone clamps (02 9.1): the dot at least 2.5 dp, the base at least 9 dp, proportions kept
        arrow.setUnit(1.111f, minDot = 2.5f * 2.75f, minBase = 9f * 2.75f)
        assertEquals(6.875f, arrow.dotSize, 1e-4f)
        assertEquals(24.75f, arrow.arrowWidth, 1e-4f)
        assertEquals(24.75f * (28f * 207f / 256f) / (26f * 184f / 256f), arrow.arrowLength, 1e-3f)
        // the distances keep the design unit
        arrow.update(1f, 0f)
        assertEquals(Math.round(112f * 1.111f).toFloat(), arrow.offsetX)
    }

    @Test
    fun evenRoundsLikeRoblox() {
        assertEquals(24f, FlightArrow.even(23.65f, 6f))
        assertEquals(6f, FlightArrow.even(2f, 6f))
        assertEquals(0x962416, FlightArrow.multiply(0xFFC93C, 0x962E5E))
    }
}
