package com.starwake.core.math

import com.starwake.core.TestAsserts.assertUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RngTest {
    @Test
    fun matchesTheSplitMix64ReferenceSequence() {
        // Reference outputs of SplitMix64 seeded with 0 (Vigna's splitmix64.c).
        val r = Rng(0L)
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), r.nextLong())
        assertEquals(0x6E789E6AA1B965F4uL.toLong(), r.nextLong())
        assertEquals(0x06C45D188009454FuL.toLong(), r.nextLong())
    }

    @Test
    fun sameSeedSameSequence() {
        val a = Rng(12345L)
        val b = Rng(12345L)
        val sa = LongArray(1000) { a.nextLong() }
        val sb = LongArray(1000) { b.nextLong() }
        assertContentEquals(sa, sb)
        val fa = FloatArray(100) { Rng(7).range(-3f, 3f) }
        val fb = FloatArray(100) { Rng(7).range(-3f, 3f) }
        assertContentEquals(fa, fb)
    }

    @Test
    fun differentSeedsDiffer() {
        val a = LongArray(16) { 0L }.also { val r = Rng(1L); for (i in it.indices) it[i] = r.nextLong() }
        val b = LongArray(16) { 0L }.also { val r = Rng(2L); for (i in it.indices) it[i] = r.nextLong() }
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun valuesStayInTheirRanges() {
        val r = Rng(99L)
        var sum = 0.0
        val n = 100_000
        var minF = 1f
        var maxF = 0f
        repeat(n) {
            val f = r.nextFloat()
            assertTrue(f >= 0f && f < 1f, "nextFloat out of [0,1): $f")
            minF = minOf(minF, f); maxF = maxOf(maxF, f)
            sum += f
            val g = r.range(-5f, 2f)
            assertTrue(g >= -5f && g < 2f, "range out of bounds: $g")
            val s = r.signed()
            assertTrue(s >= -1f && s < 1f, "signed out of bounds: $s")
            val i = r.nextInt(7)
            assertTrue(i in 0 until 7, "nextInt out of bounds: $i")
        }
        assertTrue(abs(sum / n - 0.5) < 0.01, "nextFloat mean ${sum / n}")
        assertTrue(minF < 0.001f && maxF > 0.999f, "nextFloat should cover the range [$minF, $maxF]")
    }

    @Test
    fun nextIntIsRoughlyUniform() {
        val r = Rng(3L)
        val counts = IntArray(6)
        val n = 60_000
        repeat(n) { counts[r.nextInt(6)]++ }
        for (c in counts) assertTrue(abs(c - n / 6) < n / 6 * 0.05, "bucket count $c, expected ~${n / 6}")
        repeat(100) { assertEquals(0, r.nextInt(1)) }
    }

    @Test
    fun gaussianHasUnitVariance() {
        val r = Rng(11L)
        val n = 50_000
        var sum = 0.0
        var sq = 0.0
        repeat(n) {
            val g = r.gaussian().toDouble()
            assertTrue(g.isFinite())
            sum += g
            sq += g * g
        }
        val mean = sum / n
        val variance = sq / n - mean * mean
        assertTrue(abs(mean) < 0.03, "gaussian mean $mean")
        assertTrue(abs(variance - 1.0) < 0.05, "gaussian variance $variance")
    }

    @Test
    fun onSphereIsUnitLengthAndCoversTheSphere() {
        val r = Rng(17L)
        val v = Vec3()
        val mean = Vec3()
        var upper = 0
        val n = 20_000
        repeat(n) {
            r.onSphere(v)
            assertUnit(v, 1e-5f)
            mean.add(v)
            if (v.y > 0f) upper++
        }
        mean.scale(1f / n)
        assertTrue(mean.length() < 0.03f, "directions should average to ~0, got $mean")
        assertTrue(abs(upper - n / 2) < n * 0.02, "hemisphere balance $upper / $n")
    }

    @Test
    fun noiseIsDeterministicBoundedAndContinuous() {
        var prev = Noise.value(0f, 0.3f, 0.7f, 5)
        assertEquals(prev, Noise.value(0f, 0.3f, 0.7f, 5))
        var x = 0f
        while (x < 20f) {
            val v = Noise.value(x, 0.3f, 0.7f, 5)
            assertTrue(v in 0f..1f, "value noise out of range: $v")
            assertTrue(abs(v - prev) < 0.05f, "value noise must be continuous at x=$x")
            prev = v
            x += 0.01f
        }
        // integer lattice points equal the raw hash, so different seeds give different fields
        assertNotEquals(Noise.value(1f, 2f, 3f, 1), Noise.value(1f, 2f, 3f, 2))
        val r = Rng(1)
        repeat(2000) {
            val f = Noise.fbm(r.signed() * 50f, r.signed() * 50f, r.signed() * 50f, 4, 3)
            assertTrue(f in 0f..1f, "fbm out of range: $f")
        }
    }
}
