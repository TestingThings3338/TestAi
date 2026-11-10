package com.starwake.core.hud

import com.starwake.core.flight.FlightConfig
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.roundHalfAway
import com.starwake.core.math.withAlpha
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The Roblox flight arrow (`ShipCore/ShipFlightArrow.lua`, 02-flight.md 3.9 and 6.1, DECISIONS 20):
 * at rest a small dot at the screen centre; when steering a small two-tone triangle sits in the
 * direction of the cursor's deflection, tip outward, further out the further the deflection. In
 * the dead zone it is matte dark red; outside it shows its own colours (left half yellow, right
 * half orange). While steering the dot gives its light to the arrow: past the dead zone it dims to
 * dark red in ten steps.
 *
 * Sizes are 1080-line design pixels × [unit] (`shortSide / 1080`), with the phone clamps of 9.1
 * (the dot at least 2.5 dp, the triangle's base at least 9 dp). Positions and degrees are rounded
 * as Luau's `math.round`. Draws with [UiRenderer]: two half triangles and a square; no allocation.
 */
class FlightArrow {
    /** Pixels per design pixel. */
    var unit = 1f
        private set

    /** The dot's side, px. */
    var dotSize = 4f
        private set

    /** The triangle's base and tip-to-base length, px (the share of Roblox's image the triangle fills). */
    var arrowWidth = 17f
        private set
    var arrowLength = 20f
        private set

    /** The image Roblox sizes (`even(17·unit / (184/256), 6)` × `even(20·unit / (207/256), 6)`), px. */
    var imageWidth = 24f
        private set
    var imageHeight = 24f
        private set

    // ---- the state of the last [update]
    var shown = false
        private set

    /** Offset of the arrow's centre from the screen centre, whole px (X right, Y down). */
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set

    /** Rotation in whole degrees, clockwise; 0 = tip up. */
    var rotationDeg = 0f
        private set

    /** In the dead zone: the idle tint. */
    var idle = true
        private set

    /** The dot's dim step, 0 (bright) .. 10. */
    var dotStep = 0
        private set

    /**
     * `setUnit`: sizes for [unit] px per design px; the dot at least [minDot] px and the triangle's
     * base at least [minBase] px (the triangle keeps its proportions).
     */
    fun setUnit(unit: Float, minDot: Float = 0f, minBase: Float = 0f) {
        val c = FlightConfig.Cursor
        this.unit = unit
        dotSize = max(max(2f, roundHalfAway((c.DOT_SIZE * unit).toDouble()).toFloat()), minDot)
        imageWidth = even(c.ARROW_WIDTH * unit / c.TRIANGLE_WIDTH, 6f)
        imageHeight = even(c.ARROW_LENGTH * unit / c.TRIANGLE_HEIGHT, 6f)
        var w = imageWidth * c.TRIANGLE_WIDTH
        var l = imageHeight * c.TRIANGLE_HEIGHT
        if (w < minBase) {
            l *= minBase / w
            w = minBase
        }
        arrowWidth = w
        arrowLength = l
    }

    /** `update(aim)`: [aimX]/[aimY] the cursor as a share of its radius, X right, Y down. */
    fun update(aimX: Float, aimY: Float) {
        val c = FlightConfig.Cursor
        var m = sqrt(aimX * aimX + aimY * aimY)
        dotStep = if (c.DOT_FADE_RANGE > 0f && m > c.DEADZONE) {
            roundHalfAway((minOf((m - c.DEADZONE) / c.DOT_FADE_RANGE, 1f) * c.DOT_STEPS).toDouble()).toInt()
        } else {
            0
        }
        if (m < c.ARROW_HIDE) {
            shown = false
            offsetX = 0f
            offsetY = 0f
            return
        }
        val dx = aimX / m
        val dy = aimY / m
        m = minOf(m, 1f)
        // the distance grows with the deflection; right at the centre the dot stays free
        val distance = (c.ARROW_INNER + (c.ARROW_RANGE - c.ARROW_INNER) * m) * unit
        offsetX = roundHalfAway((dx * distance).toDouble()).toFloat()
        offsetY = roundHalfAway((dy * distance).toDouble()).toFloat()
        // tip outward: the image points up, 0 degrees = right
        rotationDeg = (roundHalfAway(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))) + 90.0).toFloat()
        idle = m <= c.DEADZONE
        shown = true
    }

    /** The dot's colour (ARGB) at [alpha]: `DotColor:Lerp(ArrowIdle, step / 10)`, `DotDimTransparency · step / 10`. */
    fun dotColor(alpha: Float): Int {
        val c = FlightConfig.Cursor
        val dim = dotStep / c.DOT_STEPS.toFloat()
        return withAlpha(OPAQUE or lerpRgb(c.DOT_COLOR, c.ARROW_IDLE, dim), alpha * (1f - c.DOT_DIM_TRANSPARENCY * dim))
    }

    /** The colour (ARGB) of the left (yellow) or right (orange) half at [alpha]: its own, or tinted in the dead zone. */
    fun halfColor(left: Boolean, alpha: Float): Int {
        val c = FlightConfig.Cursor
        val base = if (left) c.ARROW_LEFT else c.ARROW_RIGHT
        if (!idle) return withAlpha(OPAQUE or base, alpha)
        return withAlpha(OPAQUE or multiply(base, c.ARROW_IDLE_TINT), alpha * (1f - c.ARROW_IDLE_TRANSPARENCY))
    }

    /** Draws the dot and (when [shown]) the arrow round the screen point ([cx], [cy]). */
    fun draw(ui: UiRenderer, cx: Float, cy: Float, alpha: Float) {
        val d = dotSize
        ui.rect(cx - d * 0.5f, cy - d * 0.5f, d, d, dotColor(alpha), 0f, 0f, 0.6f)
        if (!shown) return
        val ax = cx + offsetX
        val ay = cy + offsetY
        val angle = rotationDeg * DEG2RAD
        ui.triangle(ax, ay, arrowWidth, arrowLength, angle, halfColor(true, alpha), -1)
        ui.triangle(ax, ay, arrowWidth, arrowLength, angle, halfColor(false, alpha), 1)
    }

    companion object {
        private const val OPAQUE = -0x1000000

        /** Roblox `even(v, least) = max(least, 2 · round(v / 2))`. */
        fun even(v: Float, least: Float): Float = max(least, 2f * roundHalfAway((v / 2f).toDouble()).toFloat())

        /** `Color3:Lerp` of two 0xRRGGBB colours, each channel rounded to the nearest 1/255. */
        fun lerpRgb(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
            }
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** ImageColor3 multiplies the image: (a · b) / 255 per channel, 0xRRGGBB. */
        fun multiply(a: Int, b: Int): Int {
            val r = ((a shr 16) and 0xFF) * ((b shr 16) and 0xFF) / 255
            val g = ((a shr 8) and 0xFF) * ((b shr 8) and 0xFF) / 255
            val bl = (a and 0xFF) * (b and 0xFF) / 255
            return (r shl 16) or (g shl 8) or bl
        }
    }
}
