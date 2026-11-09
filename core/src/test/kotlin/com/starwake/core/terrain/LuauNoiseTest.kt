package com.starwake.core.terrain

import com.starwake.core.math.Rng
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LuauNoiseTest {
    @Test
    fun tableIsAPermutation() {
        val p = LuauNoise.permutation()
        assertEquals(256, p.size)
        assertEquals((0 until 256).toList(), p.sorted())
    }

    @Test
    fun zeroOnTheLattice() {
        val rng = Rng(5)
        repeat(2000) {
            val x = (rng.nextInt(2000) - 1000).toDouble()
            val y = (rng.nextInt(2000) - 1000).toDouble()
            val z = (rng.nextInt(2000) - 1000).toDouble()
            assertEquals(0.0, LuauNoise.noisePlane(x, y, z), 0.0)
            assertEquals(0.0, LuauNoise.noise(x, y, z), 0.0)
        }
    }

    /**
     * Golden values from Ken Perlin's reference ImprovedNoise.java (double precision; the first is
     * the value quoted on his page). The float kernel matches them to float precision of the inputs.
     */
    @Test
    fun matchesPerlinsReference() {
        val golden = arrayOf(
            doubleArrayOf(3.14, 42.0, 7.0, 0.13691995878400012),
            doubleArrayOf(0.5, 0.5, 0.5, -0.25),
            doubleArrayOf(1.25, -3.75, 0.37, -0.13539417524098152),
            doubleArrayOf(123.456, 789.012, 9.1, -0.4346995163542383),
            doubleArrayOf(-1000.3, 2000.7, 4.4, 0.2664302764799808),
            doubleArrayOf(255.9, 0.1, 0.7, 0.40843667011737483),
            doubleArrayOf(-0.25, -0.5, -0.75, -0.5119762420654297),
        )
        for (g in golden) {
            val v = LuauNoise.noisePlane(g[0], g[1], g[2])
            // the float cast of the inputs moves large arguments by up to ~6e-5
            val tol = 2e-6 + 8e-7 * (abs(g[0]) + abs(g[1]) + abs(g[2]))
            assertEquals(g[3], v, tol, "noise(${g[0]}, ${g[1]}, ${g[2]})")
        }
    }

    @Test
    fun worldFrontEndIsPeriodicAndMatchesTheKernel() {
        val rng = Rng(11)
        repeat(5000) {
            val x = rng.range(-1000f, 1000f).toDouble()
            val y = rng.range(-1000f, 1000f).toDouble()
            val z = rng.range(-1000f, 1000f).toDouble()
            val n = LuauNoise.noise(x, y, z)
            assertEquals(n, LuauNoise.noise(x + 256.0, y, z), 0.0)
            assertEquals(n, LuauNoise.noise(x, y - 512.0, z), 0.0)
            assertEquals(n, LuauNoise.noise(x, y, z + 256.0 * 1000), 1e-5)
            assertEquals(LuauNoise.noisePlane(x, y, z), n, 1e-4)
        }
        // far from the origin the front end keeps full resolution where the plain float cast loses it
        val big = 6.36e6 / 30.0
        val a = LuauNoise.noise(big + 0.3, 1.7, 2.2)
        val b = LuauNoise.noise(big + 0.3001, 1.7, 2.2)
        assertTrue(a != b, "0.1 mm steps resolved at 200,000 lattice units")
    }

    private fun fbmStd(octaves: Int, n: Int, seed: Long): Double {
        val rng = Rng(seed)
        val d = Domain()
        var sum = 0.0
        var sq = 0.0
        for (i in 0 until n) {
            d.setPlane(rng.range(-1e6f, 1e6f).toDouble(), rng.range(-1e6f, 1e6f).toDouble())
            val v = d.fbm(1000.0, 0.0, 0.0, 0.37, octaves)
            sum += v
            sq += v * v
        }
        val m = sum / n
        return sqrt(sq / n - m * m)
    }

    /** 04 §2.0 (computed with this kernel): fbm std 0.206/0.181/0.170/0.165 for 2..5 octaves, ridged mean 0.40. */
    @Test
    fun fbmAndRidgedStatistics() {
        val expected = doubleArrayOf(0.206, 0.181, 0.170, 0.165)
        for (o in 2..5) {
            val std = fbmStd(o, 400_000, o.toLong())
            assertEquals(expected[o - 2], std, 0.005, "fbm std, $o octaves")
        }
        // the Lua comment: "etwa -0.6..0.6 (Streuung 0.16)"; ridged mean 0.40 (std 0.19–0.23)
        val rng = Rng(77)
        val d = Domain()
        var sum = 0.0
        var lo = 0.0
        var hi = 0.0
        val n = 200_000
        for (i in 0 until n) {
            d.setPlane(rng.range(-1e6f, 1e6f).toDouble(), rng.range(-1e6f, 1e6f).toDouble())
            sum += d.ridged(1000.0, 0.0, 0.0, 0.37, 4)
            val f = d.fbm(1000.0, 0.0, 0.0, 0.37, 5)
            lo = minOf(lo, f)
            hi = maxOf(hi, f)
        }
        // the spec quotes "mean 0.40" (approximate); measured 0.37 on Roblox-style slices, and every
        // octave has the same mean as one octave over random 3-D points
        assertEquals(Domain.RIDGED_OCTAVE_MEAN, sum / n, 0.02, "ridged mean = the octave mean")
        assertEquals(0.39, sum / n, 0.03, "ridged mean ~0.40")
        assertTrue(lo > -0.75 && hi < 0.75 && lo < -0.5 && hi > 0.5, "5-octave fbm range ±0.65: $lo..$hi")
    }

    /** The floes comment: the distribution of fbm·3 + noise·0.12 has median −0.01 and ~0.14 per 10 % of area. */
    @Test
    fun floesDistribution() {
        val rng = Rng(3)
        val d = Domain()
        val n = 100_000
        val v = DoubleArray(n)
        for (i in 0 until n) {
            d.setPlane(rng.range(-1e6f, 1e6f).toDouble(), rng.range(-1e6f, 1e6f).toDouble())
            v[i] = d.fbm(1100.0, 0.0, 0.0, 0.37, 4) * 3 + d.n(260.0, 0.0, 0.0, 3.7) * 0.12
        }
        v.sort()
        val median = v[n / 2]
        assertEquals(-0.01, median, 0.02, "median")
        val d40 = v[(n * 0.4).toInt()]
        val d60 = v[(n * 0.6).toInt()]
        assertEquals(0.14, (d60 - d40) / 2, 0.02, "spread per 10 % of area around the median")
    }

    @Test
    fun bandLimitFadesTermsToTheirMean() {
        val d = Domain()
        d.setPlane(1234.5, 678.9, spacing = 0.0)
        val exact = d.n(48.0, 0.0, 0.0, 9.1)
        d.setPlane(1234.5, 678.9, spacing = 6.0)
        assertEquals(exact, d.n(48.0, 0.0, 0.0, 9.1), 0.0, "resolved at 4 spacings")
        d.setPlane(1234.5, 678.9, spacing = 24.0)
        assertEquals(0.0, d.n(48.0, 0.0, 0.0, 9.1), 0.0, "gone at 2 spacings")
        d.setPlane(1234.5, 678.9, spacing = 1e6)
        assertEquals(Domain.RIDGED_OCTAVE_MEAN, d.ridged(1000.0, 0.0, 0.0, 0.37, 4), 1e-12, "ridged fades to its mean")
        assertEquals(0.0, d.fbm(1000.0, 0.0, 0.0, 0.37, 4), 0.0)
    }
}
