package com.starwake.core.math

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

const val PI_F = 3.1415927f
const val TAU_F = 6.2831855f
const val DEG2RAD = PI_F / 180f

fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
fun clamp01(v: Float): Float = clamp(v, 0f, 1f)
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3f - 2f * t)
}

/** Frame-rate independent smoothing factor: use as `x += (target - x) * expApproach(rate, dt)`. */
fun expApproach(rate: Float, dt: Float): Float = 1f - exp(-rate * dt)

/**
 * Frame-rate independent exponential smoothing (Roblox ShipMath.smooth): [tau] is the time to cover
 * 63 % of the gap; `tau <= 0` jumps to [target]. Config time constants port verbatim through this.
 */
fun smooth(current: Float, target: Float, tau: Float, dt: Float): Float =
    if (tau <= 0f) target else current + (target - current) * (1f - exp(-dt / tau))

/**
 * Radial dead zone on a 2D input (Roblox ShipMath.applyDeadzone): magnitude m <= [dz] gives 0,
 * otherwise the direction is kept and the magnitude rescaled to s = clamp((m - dz) / (1 - dz), 0, 1),
 * raised to [curve]. Radial, not per axis (a square dead zone feels wrong on the diagonal), and the
 * rescale starts the output at 0 at the edge instead of jumping. Writes (x, y) into [out].x/.y.
 */
fun applyDeadzone(x: Float, y: Float, dz: Float, curve: Float, out: Vec3): Vec3 {
    val m = kotlin.math.sqrt(x * x + y * y)
    if (m <= dz || m < 1e-6f) return out.set(0f, 0f, 0f)
    var s = clamp((m - dz) / max(1f - dz, 1e-6f), 0f, 1f)
    if (curve != 1f) s = s.toDouble().pow(curve.toDouble()).toFloat()
    return out.set(x / m * s, y / m * s, 0f)
}

/** Luau's math.round: halves away from zero (2.5 → 3, −2.5 → −3). */
fun roundHalfAway(x: Double): Double = if (x < 0.0) -kotlin.math.floor(-x + 0.5) else kotlin.math.floor(x + 0.5)

/** Moves [current] towards [target] by at most [maxDelta]. */
fun approach(current: Float, target: Float, maxDelta: Float): Float =
    if (current < target) min(current + maxDelta, target) else max(current - maxDelta, target)

fun easeInOutCubic(t: Float): Float {
    val x = clamp01(t)
    return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
}

fun easeOutCubic(t: Float): Float {
    val x = 1f - clamp01(t)
    return 1f - x * x * x
}

/** Symmetric dead zone with re-scaling so output still reaches ±1. */
fun deadZone(v: Float, dz: Float): Float {
    val a = if (v < 0f) -v else v
    if (a <= dz) return 0f
    val s = (a - dz) / (1f - dz)
    return if (v < 0f) -s else s
}

/** Blend between linear and cubic response (expo = 0 linear, 1 fully cubic). */
fun expoCurve(v: Float, expo: Float): Float = v * (1f - expo) + v * v * v * expo

/** Packs a color into ARGB. Components 0..1. */
fun argb(a: Float, r: Float, g: Float, b: Float): Int =
    ((clamp01(a) * 255f + 0.5f).toInt() shl 24) or
        ((clamp01(r) * 255f + 0.5f).toInt() shl 16) or
        ((clamp01(g) * 255f + 0.5f).toInt() shl 8) or
        (clamp01(b) * 255f + 0.5f).toInt()

/** Multiplies the alpha channel of an ARGB color. */
fun withAlpha(color: Int, alpha: Float): Int {
    val a = ((color ushr 24) * clamp01(alpha) + 0.5f).toInt()
    return (a shl 24) or (color and 0x00FFFFFF)
}

fun lerpColor(c0: Int, c1: Int, t: Float): Int {
    val k = clamp01(t)
    val a = lerp((c0 ushr 24).toFloat(), (c1 ushr 24).toFloat(), k).toInt()
    val r = lerp(((c0 shr 16) and 0xFF).toFloat(), ((c1 shr 16) and 0xFF).toFloat(), k).toInt()
    val g = lerp(((c0 shr 8) and 0xFF).toFloat(), ((c1 shr 8) and 0xFF).toFloat(), k).toInt()
    val b = lerp((c0 and 0xFF).toFloat(), (c1 and 0xFF).toFloat(), k).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
