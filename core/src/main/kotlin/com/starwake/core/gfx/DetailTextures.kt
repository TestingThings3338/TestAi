package com.starwake.core.gfx

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The terrain's detail texture (§9.5): one tileable RGBA8 square generated at startup, one
 * luminance pattern per detail class (R soil, G rock, B granular, A ice), each with mean 0.5 so the
 * shader's `2·dot(texel, classWeights)` modulates the material colour around 1 without shifting it.
 * Original procedural patterns (periodic value noise, ridges, ripples); nothing from Roblox's
 * material textures (they cannot ship, Q7).
 */
object DetailTextures {
    fun build(size: Int): ByteArray {
        val px = ByteArray(size * size * 4)
        val ch = Array(4) { FloatArray(size * size) }
        for (y in 0 until size) for (x in 0 until size) {
            val u = x.toFloat() / size
            val v = y.toFloat() / size
            val i = y * size + x
            // soil: clumps and grains
            ch[0][i] = 0.55f * fbm(u, v, 8, 5, 11) + 0.45f * fbm(u, v, 64, 2, 23)
            // rock: cracked plates and faint strata
            val plates = fbm(u, v, 6, 4, 37)
            val crack = 1f - abs(fbm(u, v, 12, 3, 41) * 2f - 1f)
            ch[1][i] = 0.6f * plates + 0.4f * fbm(u, v, 48, 2, 53) - 0.35f * smooth(0.82f, 0.97f, crack)
            // granular: fine speckle over soft drifts
            ch[2][i] = 0.45f * fbm(u, v, 64, 2, 71) + 0.55f * fbm(u, v, 8, 4, 79)
            // ice: smooth sheets, thin cracks
            val icecrack = 1f - abs(fbm(u, v, 5, 3, 83) * 2f - 1f)
            ch[3][i] = 0.75f * fbm(u, v, 3, 3, 89) + 0.25f * fbm(u, v, 32, 2, 97) - 0.25f * smooth(0.9f, 0.98f, icecrack)
        }
        // contrast per class around a mean of exactly 0.5
        val contrast = floatArrayOf(0.26f, 0.24f, 0.16f, 0.10f)
        for (c in 0 until 4) {
            val a = ch[c]
            var mean = 0.0
            var sq = 0.0
            for (f in a) {
                mean += f
                sq += f * f
            }
            mean /= a.size
            val sd = sqrt(max(1e-9, sq / a.size - mean * mean)).toFloat()
            for (i in a.indices) a[i] = 0.5f + (a[i] - mean.toFloat()) / sd * contrast[c] * 0.5f
        }
        for (i in 0 until size * size) {
            for (c in 0 until 4) px[i * 4 + c] = (min(1f, max(0f, ch[c][i])) * 255f).roundToInt().toByte()
        }
        return px
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = min(1f, max(0f, (x - a) / (b - a)))
        return t * t * (3f - 2f * t)
    }

    /** Periodic fbm on the unit square: [cells] lattice cells per period, [octaves], seed [s]. */
    private fun fbm(u: Float, v: Float, cells: Int, octaves: Int, s: Int): Float {
        var sum = 0f
        var amp = 0.5f
        var norm = 0f
        var c = cells
        for (o in 0 until octaves) {
            sum += value(u * c, v * c, c, s + o * 101) * amp
            norm += amp
            amp *= 0.5f
            c *= 2
        }
        return sum / norm
    }

    private fun value(x: Float, y: Float, period: Int, seed: Int): Float {
        val xf = floor(x)
        val yf = floor(y)
        val xi = xf.toInt()
        val yi = yf.toInt()
        val fx = x - xf
        val fy = y - yf
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val a = hash(xi, yi, period, seed)
        val b = hash(xi + 1, yi, period, seed)
        val c = hash(xi, yi + 1, period, seed)
        val d = hash(xi + 1, yi + 1, period, seed)
        return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy
    }

    private fun hash(x: Int, y: Int, period: Int, seed: Int): Float {
        val px = Math.floorMod(x, period)
        val py = Math.floorMod(y, period)
        var h = px * 374761393 + py * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x00FFFFFF).toFloat() / 0x00FFFFFF.toFloat()
    }
}
