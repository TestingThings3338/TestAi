package com.starwake.core.terrain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The shared helpers of ShipSurfaceTerrain.lua (:163-252), transcribed literally, plus the port's
 * integer hash for the 3-D feature lattice (§9.4.3). All pure, allocation free.
 */
object TerrainMath {
    /**
     * Roblox `smooth(a, b, x)`: t = (x − a)/(b − a); 0 for t ≤ 0, 1 for t ≥ 1, else t²(3 − 2t).
     * With a > b it is a falling step (1 below b, 0 above a).
     */
    fun smooth(a: Double, b: Double, x: Double): Double {
        val t = (x - a) / (b - a)
        if (t <= 0.0) return 0.0
        if (t >= 1.0) return 1.0
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * Roblox `hash(i, j, k)`: frac(sin(i·12.9898 + j·78.233 + k·37.719)·43758.5453), in double with
     * fdlibm's sin (StrictMath) so every platform gives the same bits. Plane mode only: it loses
     * quality for large arguments (sphere mode uses [hash4]).
     */
    fun robloxHash(i: Double, j: Double, k: Double): Double {
        val n = StrictMath.sin(i * 12.9898 + j * 78.233 + k * 37.719) * 43758.5453
        return n - floor(n)
    }

    private const val K1 = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    private const val K2 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
    private const val K3 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB

    private fun mix64(v: Long): Long {
        var z = v
        z = (z xor (z ushr 30)) * K2
        z = (z xor (z ushr 27)) * K3
        return z xor (z ushr 31)
    }

    /**
     * The port's lattice hash: a 64-bit integer mix of the cell (i, j, k) and a key (the bits of a
     * Roblox-style double key such as `salt + index·7.31 + 2.9`), mapped to [0, 1).
     */
    fun hash4(i: Int, j: Int, k: Int, key: Long): Double {
        var z = mix64(key + i.toLong() * K1)
        z = mix64(z + j.toLong() * K2 + 0x632BE59BD9B4E019L)
        z = mix64(z + k.toLong() * K3 + 0x2545F4914F6CDD1DL)
        return (z ushr 11).toDouble() * (1.0 / (1L shl 53))
    }

    /** Key bits of a Roblox-style double key for [hash4]. */
    fun keyBits(key: Double): Long = java.lang.Double.doubleToRawLongBits(key)

    /** Dune cross-section, period 1: long windward slope (smoothstep over 0.78), short steep lee. */
    fun dune(t0: Double): Double {
        val t = t0 - floor(t0)
        if (t < 0.78) {
            val a = t / 0.78
            return a * a * (3.0 - 2.0 * a)
        }
        val a = (t - 0.78) / 0.22
        return 1.0 - a * a * (3.0 - 2.0 * a)
    }

    /** Mean of [dune] over a period (what an unresolved dune field averages to). */
    const val DUNE_MEAN = 0.5

    /** Flat tables with steep edges. */
    fun terrace(v: Double, steps: Double, sharp: Double): Double {
        val s = v * steps
        val base = floor(s)
        return (base + smooth(0.5 - sharp, 0.5 + sharp, s - base)) / steps
    }

    /** Bilinear: a at (0,0), b at (1,0), c at (0,1), d at (1,1). */
    fun mix4(a: Double, b: Double, c: Double, d: Double, fx: Double, fz: Double): Double {
        val near = a + (b - a) * fx
        val far = c + (d - c) * fx
        return near + (far - near) * fz
    }

    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    /** What a planet image shows at a point (`toneOf`, colour 0..1): writes wet, cold, dry. */
    fun toneOf(r: Double, g: Double, b: Double, out: Tone) {
        val light = (r + g + b) / 3.0
        out.wet = smooth(0.04, 0.14, b - max(r, g * 0.92)) * smooth(0.55, 0.4, light)
        out.cold = smooth(0.58, 0.75, min(r, min(g, b)))
        out.dry = smooth(0.06, 0.16, r - b) * smooth(0.3, 0.45, light) * smooth(0.06, 0.0, g - r)
    }

    /** Roblox `shade(c, k)` per channel, clamped to [0, 1]. */
    fun shade(c: Double, k: Double): Double = clamp(c * k, 0.0, 1.0)

    /** The per-channel tint factor of `applyTone` (§2.2.8). */
    fun tintFactor(want: Double, have: Double): Double = clamp(1.0 + 0.65 * (want / max(have, 0.03) - 1.0), 0.55, 1.8)
}

/** wet / cold / dry of [TerrainMath.toneOf]. */
class Tone {
    @JvmField var wet = 0.0
    @JvmField var cold = 0.0
    @JvmField var dry = 0.0
}

/**
 * Per-body offsets of the noise space and the feature salt (ShipSurfaceTerrain:begin, :1280):
 * `o.x = (hash(seed, 1, 0.5) − 0.5)·2000`, `o.y` likewise with 2, `angle = hash(seed, 3, 0.5)·π`,
 * `salt = (seed mod 997) + 0.123` (Lua's floored modulo).
 */
class SeedOffsets(val seed: Int) {
    val x: Double = (TerrainMath.robloxHash(seed.toDouble(), 1.0, 0.5) - 0.5) * 2000.0
    val y: Double = (TerrainMath.robloxHash(seed.toDouble(), 2.0, 0.5) - 0.5) * 2000.0
    val angle: Double = TerrainMath.robloxHash(seed.toDouble(), 3.0, 0.5) * PI
    val ca: Double = cos(angle)
    val sa: Double = sin(angle)
    val salt: Double = Math.floorMod(seed, 997).toDouble() + 0.123
}
