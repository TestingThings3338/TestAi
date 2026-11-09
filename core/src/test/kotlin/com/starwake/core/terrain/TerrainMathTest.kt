package com.starwake.core.terrain

import com.starwake.core.terrain.TerrainMath.dune
import com.starwake.core.terrain.TerrainMath.smooth
import com.starwake.core.terrain.TerrainMath.terrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainMathTest {
    @Test
    fun smoothRisingAndFalling() {
        assertEquals(0.0, smooth(0.0, 1.0, -1.0))
        assertEquals(1.0, smooth(0.0, 1.0, 2.0))
        assertEquals(0.5, smooth(0.0, 1.0, 0.5), 1e-12)
        // falling form: 1 below b, 0 above a
        assertEquals(1.0, smooth(0.55, 0.4, 0.3))
        assertEquals(0.0, smooth(0.55, 0.4, 0.6))
        assertEquals(0.5, smooth(0.55, 0.4, 0.475), 1e-12)
    }

    @Test
    fun duneAndTerrace() {
        assertEquals(0.0, dune(0.0), 1e-12)
        assertEquals(1.0, dune(0.78), 1e-12)
        assertTrue(dune(0.999999) < 1e-9)
        assertEquals(dune(0.3), dune(5.3), 1e-12, "period 1")
        assertEquals(dune(0.3), dune(-0.7), 1e-12)
        // flat tables with steep edges
        assertEquals(1.0 / 3, terrace(0.2, 3.0, 0.08), 1e-12)
        assertEquals(0.0, terrace(0.05, 3.0, 0.08), 1e-12)
        assertEquals(2.0 / 3, terrace(0.55, 3.0, 0.08), 1e-12)
    }

    @Test
    fun robloxHash() {
        assertEquals(0.020163, TerrainMath.robloxHash(1.0, 2.0, 3.0), 5e-7)
        for (i in 0 until 1000) {
            val h = TerrainMath.robloxHash(i.toDouble(), (i * 7).toDouble(), 0.5)
            assertTrue(h >= 0.0 && h < 1.0)
        }
    }

    @Test
    fun hash4IsUniform() {
        val bins = IntArray(10)
        val key = TerrainMath.keyBits(3.123 + 7.31)
        for (i in -50 until 50) for (j in -50 until 50) for (k in 0 until 10) bins[(TerrainMath.hash4(i, j, k, key) * 10).toInt()]++
        for (b in bins) assertEquals(10_000.0, b.toDouble(), 400.0)
        assertTrue(TerrainMath.hash4(1, 2, 3, key) != TerrainMath.hash4(1, 2, 3, TerrainMath.keyBits(3.123 + 7.31 + 1.7)))
    }

    @Test
    fun toneOfCorners() {
        val t = Tone()
        TerrainMath.toneOf(0.05, 0.15, 0.35, t) // dark blue: sea
        assertTrue(t.wet > 0.99 && t.cold == 0.0 && t.dry == 0.0)
        TerrainMath.toneOf(0.95, 0.96, 0.98, t) // white: ice or painted cloud
        assertTrue(t.cold > 0.99 && t.wet == 0.0)
        TerrainMath.toneOf(0.80, 0.70, 0.50, t) // sand
        assertTrue(t.dry > 0.99 && t.wet == 0.0 && t.cold == 0.0)
        TerrainMath.toneOf(0.30, 0.45, 0.20, t) // green land
        assertTrue(t.dry == 0.0 && t.wet == 0.0 && t.cold == 0.0)
    }

    /** 04 §3.6: seed-derived constants of every Sol body. */
    @Test
    fun seedConstants() {
        val table = arrayOf(
            doubleArrayOf(1.0, 631.615, 533.871, 2.8422, 1.123),
            doubleArrayOf(2.0, -59.070, -570.725, 0.5776, 2.123),
            doubleArrayOf(3.0, 827.928, 310.175, 2.7345, 3.123),
            doubleArrayOf(4.0, 662.315, -820.613, 2.3100, 4.123),
            doubleArrayOf(94.0, -755.157, 138.002, 1.3425, 94.123),
            doubleArrayOf(125.0, 745.097, -555.511, 0.9751, 125.123),
            doubleArrayOf(126.0, -28.976, 587.860, 1.3902, 126.123),
            doubleArrayOf(156.0, 911.935, -293.594, 2.3079, 156.123),
            doubleArrayOf(157.0, -238.573, 728.810, 2.2383, 157.123),
            doubleArrayOf(158.0, -986.524, 192.288, 2.8203, 158.123),
            doubleArrayOf(159.0, 165.586, -703.469, 2.5134, 159.123),
            doubleArrayOf(187.0, 579.357, 501.044, 0.9286, 187.123),
            doubleArrayOf(249.0, 13.231, 678.195, 1.8974, 249.123),
        )
        for (row in table) {
            val o = SeedOffsets(row[0].toInt())
            assertEquals(row[1], o.x, 0.002, "o.x seed ${row[0]}")
            assertEquals(row[2], o.y, 0.002, "o.y seed ${row[0]}")
            assertEquals(row[3], o.angle, 0.0002, "angle seed ${row[0]}")
            assertEquals(row[4], o.salt, 1e-9, "salt seed ${row[0]}")
        }
        val earth = SeedOffsets(3)
        assertEquals(-0.9183, earth.ca, 1e-4)
        assertEquals(0.3959, earth.sa, 1e-4)
        assertEquals(997.0 - 5 + 0.123, SeedOffsets(-5).salt, 1e-9, "Lua's floored modulo")
    }
}
