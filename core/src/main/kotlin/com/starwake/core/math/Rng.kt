package com.starwake.core.math

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Small deterministic SplitMix64 RNG so procedural content is identical on every device. */
class Rng(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() * (1f / (1 shl 24))

    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * nextFloat()

    fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()

    fun signed(): Float = nextFloat() * 2f - 1f

    fun gaussian(): Float {
        val u1 = 1f - nextFloat()
        val u2 = nextFloat()
        return sqrt(-2f * ln(u1)) * cos(TAU_F * u2)
    }

    /** Uniform random direction on the unit sphere. */
    fun onSphere(out: Vec3): Vec3 {
        val z = signed()
        val a = TAU_F * nextFloat()
        val r = sqrt(1f - z * z)
        return out.set(r * cos(a), r * sin(a), z)
    }

    private companion object {
        const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val MIX1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        const val MIX2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
    }
}

/** Integer-hash value noise used by the CPU content generators. */
object Noise {
    private fun hash(x: Int, y: Int, z: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + z * 1274126177 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x00FFFFFF).toFloat() / 0x00FFFFFF.toFloat()
    }

    fun value(x: Float, y: Float, z: Float, seed: Int): Float {
        val xf = kotlin.math.floor(x)
        val yf = kotlin.math.floor(y)
        val zf = kotlin.math.floor(z)
        val xi = xf.toInt()
        val yi = yf.toInt()
        val zi = zf.toInt()
        val fx = x - xf
        val fy = y - yf
        val fz = z - zf
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val uz = fz * fz * (3f - 2f * fz)
        val c000 = hash(xi, yi, zi, seed)
        val c100 = hash(xi + 1, yi, zi, seed)
        val c010 = hash(xi, yi + 1, zi, seed)
        val c110 = hash(xi + 1, yi + 1, zi, seed)
        val c001 = hash(xi, yi, zi + 1, seed)
        val c101 = hash(xi + 1, yi, zi + 1, seed)
        val c011 = hash(xi, yi + 1, zi + 1, seed)
        val c111 = hash(xi + 1, yi + 1, zi + 1, seed)
        val x00 = lerp(c000, c100, ux)
        val x10 = lerp(c010, c110, ux)
        val x01 = lerp(c001, c101, ux)
        val x11 = lerp(c011, c111, ux)
        return lerp(lerp(x00, x10, uy), lerp(x01, x11, uy), uz)
    }

    fun fbm(x: Float, y: Float, z: Float, octaves: Int, seed: Int): Float {
        var sum = 0f
        var amp = 0.5f
        var freq = 1f
        var norm = 0f
        for (i in 0 until octaves) {
            sum += value(x * freq, y * freq, z * freq, seed + i * 31) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.03f
        }
        return sum / norm
    }
}
