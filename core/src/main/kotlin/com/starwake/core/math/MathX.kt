package com.starwake.core.math

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

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
