package com.starwake.core.terrain

import com.starwake.core.terrain.TerrainMath.smooth
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The point the terrain style functions see (04-planet-surface.md §9.4): the styles are written
 * once against this class, which has two modes.
 *
 * - **Plane mode** (Roblox-exact): metres (u, v) on the tangent plane. `n(λ, a, b, c)` is
 *   `noise(u/λ + a, v/λ + b, c)` with Roblox's float cast; phases are `(α·u + β·v)/λ`; features
 *   use 2-D cells.
 * - **Sphere mode** (the game): P = n·R in body space on the reference sphere. Isotropic terms are
 *   the same 3-D Perlin field sampled on the sphere, `noise(P.x/λ + a, P.y/λ + b, P.z/λ + c)`,
 *   reduced modulo 256 in double before the float kernel (§9.4.1); directional terms use the
 *   Mercator chart (lonE east, isometric latitude north) with an integer wrap at lonE = ±π and a
 *   polar fade [dirWeight] (§9.4.2); features use the 3-D shell lattice (§9.4.3).
 *
 * **Band limit.** With [spacing] > 0 (a tile's vertex spacing) every noise term or octave whose
 * wavelength λ is below 4·spacing is faded towards its mean, `weight = smooth(2s, 4s, λ)` (§9.5
 * step 4); 0 = exact (the finest tiles and every physics query). The smallest Roblox wavelength is
 * 30 m, so tiles at ≤ 7.5 m spacing are exact.
 *
 * Mutable, one instance per thread.
 */
class Domain {
    var sphere = false
        private set

    // plane mode
    var u = 0.0
        private set
    var v = 0.0
        private set

    // sphere mode
    var px = 0.0
        private set
    var py = 0.0
        private set
    var pz = 0.0
        private set
    var radius = 1.0
        private set

    /** Mercator chart metres: east along the parallel, north (isometric latitude), §9.4.2. */
    var chartX = 0.0
        private set
    var chartY = 0.0
        private set
    var lonE = 0.0
        private set
    var cosLat = 1.0
        private set

    /** Amplitude weight of directional patterns (dunes, grooves): 1 below 63°, 0 above 75.5° latitude. */
    var dirWeight = 1.0
        private set

    /** Vertex spacing of the band limit (m), 0 = exact. */
    var spacing = 0.0
        private set

    fun setPlane(u: Double, v: Double, spacing: Double = 0.0) {
        sphere = false
        this.u = u
        this.v = v
        this.spacing = spacing
        dirWeight = 1.0
    }

    /** Sphere mode at the unit direction (nx, ny, nz) on a sphere of [radius] metres. */
    fun setSphere(nx: Double, ny: Double, nz: Double, radius: Double, spacing: Double = 0.0) {
        sphere = true
        this.radius = radius
        this.spacing = spacing
        px = nx * radius
        py = ny * radius
        pz = nz * radius
        lonE = atan2(-nz, nx)
        val s = TerrainMath.clamp(ny, -LAT_CLAMP, LAT_CLAMP)
        cosLat = sqrt(max(0.0, 1.0 - ny * ny))
        chartX = radius * lonE
        // asinh(tan(lat)) = atanh(sin(lat)) = 0.5·ln((1 + s)/(1 − s))
        chartY = radius * 0.5 * ln((1.0 + s) / (1.0 - s))
        dirWeight = smooth(0.25, 0.45, cosLat)
    }

    /** Band-limit weight of a term with wavelength [lambda] (m): 1 = resolved. */
    fun fade(lambda: Double): Double {
        val s = spacing
        if (s <= 0.0) return 1.0
        return smooth(2.0 * s, 4.0 * s, lambda)
    }

    private fun raw(x: Double, y: Double, z: Double): Double =
        if (sphere) LuauNoise.noise(x, y, z) else LuauNoise.noisePlane(x, y, z)

    /** `noise(u/λ + a, v/λ + b, c)`, band-limited (mean 0). */
    fun n(lambda: Double, a: Double, b: Double, c: Double): Double {
        val w = fade(lambda)
        if (w <= 0.0) return 0.0
        val inv = 1.0 / lambda
        val x = if (sphere) px * inv + a else u / lambda + a
        val y = if (sphere) py * inv + b else v / lambda + b
        val z = if (sphere) pz * inv + c else c
        return raw(x, y, z) * w
    }

    /**
     * Roblox `fbm(u/λ + a, v/λ + b, c, octaves)`: amplitude ×0.5 and frequency ×2.07 per octave,
     * divided by the full norm; octaves below the band limit fade to 0 (their mean).
     */
    fun fbm(lambda: Double, a: Double, b: Double, c: Double, octaves: Int): Double {
        val x = if (sphere) px / lambda + a else u / lambda + a
        val y = if (sphere) py / lambda + b else v / lambda + b
        val z = if (sphere) pz / lambda + c else c
        var total = 0.0
        var amplitude = 1.0
        var frequency = 1.0
        var norm = 0.0
        for (k in 0 until octaves) {
            val w = fade(lambda / frequency)
            if (w > 0.0) total += raw(x * frequency, y * frequency, z * frequency) * amplitude * w
            norm += amplitude
            amplitude *= 0.5
            frequency *= 2.07
        }
        return total / norm
    }

    /**
     * Roblox `ridged(…)`: each octave adds `max(0, 1 − 2.2|noise|)²·amplitude`, frequency ×2.03;
     * octaves below the band limit fade to their mean [RIDGED_OCTAVE_MEAN].
     */
    fun ridged(lambda: Double, a: Double, b: Double, c: Double, octaves: Int): Double {
        val x = if (sphere) px / lambda + a else u / lambda + a
        val y = if (sphere) py / lambda + b else v / lambda + b
        val z = if (sphere) pz / lambda + c else c
        var total = 0.0
        var amplitude = 1.0
        var frequency = 1.0
        var norm = 0.0
        for (k in 0 until octaves) {
            val w = fade(lambda / frequency)
            var value = RIDGED_OCTAVE_MEAN
            if (w > 0.0) {
                var n = 1.0 - abs(raw(x * frequency, y * frequency, z * frequency)) * 2.2
                if (n < 0.0) n = 0.0
                value = RIDGED_OCTAVE_MEAN + (n * n - RIDGED_OCTAVE_MEAN) * w
            }
            total += value * amplitude
            norm += amplitude
            amplitude *= 0.5
            frequency *= 2.03
        }
        return total / norm
    }

    /**
     * A directional phase `(α·u + β·v)/λ` (dune crests, grooves). In sphere mode it runs in the
     * Mercator chart with λ adjusted so that it wraps by an integer at lonE = ±π: the bearing is
     * Roblox's everywhere, the spacing is λ·cos(lat).
     */
    fun phase(alpha: Double, beta: Double, lambda: Double): Double {
        if (!sphere) return (u * alpha + v * beta) / lambda
        var l = lambda
        if (abs(alpha) > 1e-9) {
            val turns = abs(alpha) * 2.0 * PI * radius / lambda
            l = abs(alpha) * 2.0 * PI * radius / max(1.0, round(turns))
        }
        return (alpha * chartX + beta * chartY) / l
    }

    /**
     * The nitrogen streak noise `noise((0.9u + 0.43v)/900 + ox, (0.9v − 0.43u)/90 + oy, 4.4)`
     * (10:1 stretched dark plumes). Sphere mode: in the chart, cross-faded with the same expression
     * one turn over within 5° of the seam so it is continuous everywhere.
     */
    fun streak(ox: Double, oy: Double): Double {
        val w = fade(90.0)
        if (w <= 0.0) return 0.0
        if (!sphere) return LuauNoise.noisePlane((u * 0.9 + v * 0.43) / 900.0 + ox, (v * 0.9 - u * 0.43) / 90.0 + oy, 4.4) * w
        val g0 = streakAt(chartX, chartY, ox, oy)
        val al = abs(lonE)
        if (al < PI - SEAM_BAND) return g0 * w
        val shifted = chartX - (if (lonE > 0.0) 1.0 else -1.0) * 2.0 * PI * radius
        val k = 0.5 * smooth(PI - SEAM_BAND, PI, al)
        return (g0 * (1.0 - k) + streakAt(shifted, chartY, ox, oy) * k) * w
    }

    private fun streakAt(x: Double, y: Double, ox: Double, oy: Double): Double =
        LuauNoise.noise((x * 0.9 + y * 0.43) / 900.0 + ox, (y * 0.9 - x * 0.43) / 90.0 + oy, 4.4)

    companion object {
        /** |sin(lat)| limit of the chart (±85°). */
        const val LAT_CLAMP = 0.9962

        /** 5° crossfade band at the chart seam. */
        private const val SEAM_BAND = 5.0 * PI / 180.0

        /** Mean of one ridged octave, max(0, 1 − 2.2|noise|)², over the noise distribution (computed). */
        val RIDGED_OCTAVE_MEAN: Double

        /** Standard deviation of one noise octave over random positions (computed). */
        val NOISE_STD: Double

        init {
            // a fixed low-discrepancy set of points: deterministic constants on every platform
            var sum = 0.0
            var sumSq = 0.0
            var sumR = 0.0
            val count = 40_000
            for (i in 0 until count) {
                val x = (i * 0.618033988749895) % 1.0 * 251.0
                val y = (i * 0.754877666246693) % 1.0 * 241.0
                val z = (i * 0.569840290998053) % 1.0 * 239.0
                val n = LuauNoise.noise(x, y, z)
                sum += n
                sumSq += n * n
                var r = 1.0 - abs(n) * 2.2
                if (r < 0.0) r = 0.0
                sumR += r * r
            }
            val mean = sum / count
            NOISE_STD = sqrt(max(0.0, sumSq / count - mean * mean))
            RIDGED_OCTAVE_MEAN = sumR / count
        }
    }
}
