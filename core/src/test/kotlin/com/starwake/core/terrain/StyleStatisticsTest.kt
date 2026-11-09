package com.starwake.core.terrain

import com.starwake.core.math.Rng
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The styles in plane mode reproduce the statistics computed from the Lua (04 §2.2.6 "Reference
 * statistics": Earth offsets, no image, random points in 80 × 80 km, slope from ±4 m
 * differences); sphere mode matches plane mode (§10.4 StyleStatisticsTest).
 */
class StyleStatisticsTest {
    class Stats(val heights: DoubleArray, val water: Double, val shares: Map<String, Double>) {
        fun pct(p: Double): Double = heights[((heights.size - 1) * p).toInt()]
    }

    private val o = SeedOffsets(3)
    private val salt = o.salt

    private fun plane(style: TerrainStyle, n: Int, seed: Long): Stats {
        val rng = Rng(seed)
        val d = Domain()
        val list = FeatureList(512)
        val s = StyleScratch()
        val rel = Relief()
        val hs = DoubleArray(5)
        val heights = DoubleArray(n)
        val counts = IntArray(style.materials.size)
        var water = 0
        for (k in 0 until n) {
            val u = rng.range(-40_000f, 40_000f).toDouble()
            val v = rng.range(-40_000f, 40_000f).toDouble()
            Features.collectPlane(style.layers, u - 5, v - 5, u + 5, v + 5, salt, list)
            var tag = Tag.NONE
            for (j in 0 until 5) {
                val du = when (j) { 1 -> 4.0; 2 -> -4.0; else -> 0.0 }
                val dv = when (j) { 3 -> 4.0; 4 -> -4.0; else -> 0.0 }
                d.setPlane(u + du, v + dv)
                style.relief(d, list, s, o, 0.0, 0.0, 0.0, rel)
                hs[j] = max(rel.h, style.floor)
                if (j == 0) tag = rel.tag
            }
            val h = hs[0]
            heights[k] = h
            if (style.hasWater && h < style.water) water++
            val gx = (hs[1] - hs[2]) / 8
            val gz = (hs[3] - hs[4]) / 8
            d.setPlane(u, v)
            counts[TerrainStyle.top(style.paint(h, sqrt(gx * gx + gz * gz), tag, d, 0.0, 0.0, 0.0))]++
        }
        heights.sort()
        return Stats(heights, water * 100.0 / n, style.materials.indices.associate { style.materials[it].name to counts[it] * 100.0 / n })
    }

    private fun check(name: String, st: Stats, p50: Double, p99: Double, water: Double?, shares: Map<String, Double>) {
        println("$name: p1 %.1f p50 %.1f p99 %.1f max %.1f water %.1f %% shares %s".format(
            st.pct(0.01), st.pct(0.5), st.pct(0.99), st.heights.last(), st.water,
            st.shares.entries.sortedByDescending { it.value }.joinToString { "%s %.1f".format(it.key, it.value) }))
        assertEquals(p50, st.pct(0.5), 3.0, "$name p50")
        assertEquals(p99, st.pct(0.99), p99 * 0.1, "$name p99")
        if (water != null) assertEquals(water, st.water, 3.0, "$name below water")
        for ((m, share) in shares) assertEquals(share, st.shares.getValue(m), 3.0, "$name share of $m")
    }

    @Test
    fun planeModeReproducesTheLuaStatistics() {
        val n = 20_000
        check(
            "temperate", plane(TemperateStyle(StyleCatalog.EARTH), n, 1), 30.4, 226.8, 11.6,
            mapOf("grass" to 41.7, "lush" to 18.8, "dry" to 14.3, "sand" to 11.7, "dirt" to 5.0, "mud" to 3.4, "rock" to 2.9, "snow" to 1.6, "cliff" to 0.8),
        )
        check(
            "desert (Mars)", plane(DesertStyle(StyleCatalog.MARS, craters = 0.8, mesas = 0.7), n, 2), 22.2, 134.9, null,
            mapOf("dust" to 42.3, "sand" to 34.9, "rock" to 17.2, "pale" to 3.8, "layered" to 1.2, "dark" to 0.7),
        )
        check(
            "cratered (Luna)", plane(CrateredStyle(StyleCatalog.LUNA), n, 3), 18.1, 38.5, null,
            mapOf("regolith" to 64.3, "dust" to 14.6, "bright" to 13.0, "mare" to 7.7, "rock" to 0.5),
        )
        check(
            "ridges", plane(RidgesStyle(StyleCatalog.ridgeSet(Palettes.colours("Rocky Body"))), n, 4), 38.4, 205.6, null,
            mapOf("low" to 68.7, "sand" to 13.6, "scree" to 10.4, "rock" to 7.1, "peak" to 0.2),
        )
        check(
            "ocean (ARCHIPELAGO)", plane(StyleCatalog.chooseStyle("N", "Ocean World", "ARCHIPELAGO WORLD")!!, n, 5), 6.2, 96.8, 58.2,
            mapOf("sand" to 45.0, "grass" to 23.8, "reef" to 19.9, "lush" to 9.7, "rock" to 1.7),
        )
    }

    /** Sphere mode (3-D noise on an Earth-size sphere, chart phases, shell lattice) has the same statistics. */
    @Test
    fun sphereModeMatchesPlaneMode() {
        for ((name, style) in listOf(
            "temperate" to TemperateStyle(StyleCatalog.EARTH),
            "cratered" to CrateredStyle(StyleCatalog.LUNA),
            "desert" to DesertStyle(StyleCatalog.MARS, craters = 0.8, mesas = 0.7),
        )) {
            val n = 20_000
            val p = plane(style, n, 9)
            val s = sphere(style, n, 6.36e6, 10)
            val ks = ks(p.heights, s.heights)
            println("$name: plane p1 %.1f p10 %.1f p50 %.1f p90 %.1f p99 %.1f floor %.3f | sphere p1 %.1f p10 %.1f p50 %.1f p90 %.1f p99 %.1f floor %.3f".format(
                p.pct(0.01), p.pct(0.1), p.pct(0.5), p.pct(0.9), p.pct(0.99), p.heights.count { it <= style.floor + 1e-9 } / n.toDouble(),
                s.pct(0.01), s.pct(0.1), s.pct(0.5), s.pct(0.9), s.pct(0.99), s.heights.count { it <= style.floor + 1e-9 } / n.toDouble()))
            println("$name: KS plane vs sphere %.4f; shares plane %s sphere %s".format(ks, p.shares, s.shares))
            assertTrue(ks < 0.03, "$name: KS distance $ks")
            for (m in p.shares.keys) assertEquals(p.shares.getValue(m), s.shares.getValue(m), 2.5, "$name share of $m")
        }
    }

    private fun sphere(style: TerrainStyle, n: Int, radius: Double, seed: Long): Stats {
        val rng = Rng(seed)
        val d = Domain()
        val list = FeatureList(512)
        val ss = StyleScratch()
        val rel = Relief()
        val heights = DoubleArray(n)
        val counts = IntArray(style.materials.size)
        val hs = DoubleArray(5)
        var water = 0
        val v = com.starwake.core.math.Vec3()
        for (k in 0 until n) {
            // directions below 60° latitude (the directional patterns fade towards the poles)
            var x: Double
            var y: Double
            var z: Double
            do {
                rng.onSphere(v)
                x = v.x.toDouble(); y = v.y.toDouble(); z = v.z.toDouble()
            } while (abs(y) > 0.85)
            val l = sqrt(x * x + y * y + z * z)
            x /= l; y /= l; z /= l
            // tangent frame for the ±4 m slope samples
            var ex = z
            var ez = -x
            val el = sqrt(ex * ex + ez * ez)
            ex /= el; ez /= el
            val nx = y * ez
            val ny = z * ex - x * ez
            val nz = -y * ex
            val px = x * radius
            val py = y * radius
            val pz = z * radius
            Features.collectSphere(style.layers, px - 6, py - 6, pz - 6, px + 6, py + 6, pz + 6, radius, o.salt, list)
            var tag = Tag.NONE
            for (j in 0 until 5) {
                val de = when (j) { 1 -> 4.0; 2 -> -4.0; else -> 0.0 }
                val dn = when (j) { 3 -> 4.0; 4 -> -4.0; else -> 0.0 }
                var qx = px + ex * de + nx * dn
                var qy = py + ny * dn
                var qz = pz + ez * de + nz * dn
                val ql = sqrt(qx * qx + qy * qy + qz * qz)
                qx /= ql; qy /= ql; qz /= ql
                d.setSphere(qx, qy, qz, radius)
                style.relief(d, list, ss, o, 0.0, 0.0, 0.0, rel)
                hs[j] = max(rel.h, style.floor)
                if (j == 0) tag = rel.tag
            }
            val h = hs[0]
            heights[k] = h
            if (style.hasWater && h < style.water) water++
            d.setSphere(x, y, z, radius)
            val gx = (hs[1] - hs[2]) / 8
            val gz = (hs[3] - hs[4]) / 8
            counts[TerrainStyle.top(style.paint(h, sqrt(gx * gx + gz * gz), tag, d, 0.0, 0.0, 0.0))]++
        }
        heights.sort()
        return Stats(heights, water * 100.0 / n, style.materials.indices.associate { style.materials[it].name to counts[it] * 100.0 / n })
    }

    private fun ks(a: DoubleArray, b: DoubleArray): Double {
        var i = 0
        var j = 0
        var d = 0.0
        // step over every value equal to the smallest remaining one in both samples (ties: the floor clamp)
        while (i < a.size && j < b.size) {
            val x = minOf(a[i], b[j])
            while (i < a.size && a[i] == x) i++
            while (j < b.size && b[j] == x) j++
            d = max(d, abs(i.toDouble() / a.size - j.toDouble() / b.size))
        }
        return d
    }

    /** 3-D shell lattice: areal density chance/size² per layer, as Roblox's 2-D cells. */
    @Test
    fun shellLatticeDensity() {
        val radius = 1.74e6
        val layers = CrateredStyle.craterLayers(1.0, 1.0)
        val list = FeatureList(400_000)
        // a 40 km × 40 km × shell box around a point on the sphere (+X)
        val half = 20_000.0
        Features.collectSphere(layers, radius - 3000, -half, -half, radius + 3000, half, half, radius, 94.123, list)
        val counts = IntArray(layers.size)
        for (k in 0 until list.count) {
            // centres strictly inside the 40 km square (on the sphere ≈ the plane x = R)
            if (abs(list.cy[k]) < half - 2000 && abs(list.cz[k]) < half - 2000) counts[list.layer[k]]++
        }
        val area = (2 * (half - 2000)) * (2 * (half - 2000))
        for (i in layers.indices) {
            val l = layers[i]
            val expected = l.chance / (l.size * l.size) * area
            if (expected < 50) continue
            val rel = counts[i] / expected
            println("layer $i (size ${l.size}): ${counts[i]} features, expected %.0f (%.3f)".format(expected, rel))
            assertEquals(1.0, rel, 0.1, "density of layer $i")
        }
        assertEquals(0, list.overflow)
    }
}
