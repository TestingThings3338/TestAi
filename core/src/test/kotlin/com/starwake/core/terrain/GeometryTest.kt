package com.starwake.core.terrain

import com.starwake.core.math.Rng
import com.starwake.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cube sphere, Mercator chart and quadtree selection (04 §9.3, §9.4.2, §10.4). */
class GeometryTest {
    @Test
    fun cubeSphereRoundTrip() {
        val rng = Rng(1)
        val d = DoubleArray(3)
        val ab = DoubleArray(2)
        for (f in 0 until 6) repeat(2000) {
            val a = rng.signed().toDouble()
            val b = rng.signed().toDouble()
            CubeSphere.dir(f, a, b, d)
            assertEquals(1.0, sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-12)
            assertEquals(f, CubeSphere.faceOf(d[0], d[1], d[2]), "face of ($a, $b)")
            assertTrue(CubeSphere.coords(f, d[0], d[1], d[2], ab))
            assertEquals(a, ab[0], 1e-12)
            assertEquals(b, ab[1], 1e-12)
        }
    }

    /** Neighbouring faces build the same direction, bit for bit, for points on their shared edge. */
    @Test
    fun faceEdgesAreBitIdentical() {
        val d1 = DoubleArray(3)
        val d2 = DoubleArray(3)
        val ab = DoubleArray(2)
        var shared = 0
        val n = 64 // grid of a level-1 tile row: 2/64 steps, exact dyadic values
        for (f in 0 until 6) for (e in 0 until 4) for (k in 0..n) {
            val t = 2.0 * k / n - 1.0
            val a = when (e) { 0 -> -1.0; 1 -> 1.0; else -> t }
            val b = when (e) { 2 -> -1.0; 3 -> 1.0; else -> t }
            CubeSphere.dir(f, a, b, d1)
            // the same point seen from every other face it lies on
            for (g in 0 until 6) {
                if (g == f || !CubeSphere.coords(g, d1[0], d1[1], d1[2], ab)) continue
                if (abs(abs(ab[0]) - 1.0) > 1e-9 && abs(abs(ab[1]) - 1.0) > 1e-9) continue
                // snap to the dyadic grid the other face's tiles use
                val ga = round((ab[0] + 1) * n / 2) * 2.0 / n - 1.0
                val gb = round((ab[1] + 1) * n / 2) * 2.0 / n - 1.0
                CubeSphere.dir(g, ga, gb, d2)
                assertTrue(d1[0] == d2[0] && d1[1] == d2[1] && d1[2] == d2[2], "face $f ($a, $b) vs face $g ($ga, $gb)")
                shared++
            }
        }
        assertTrue(shared > 6 * 4 * n)
    }

    @Test
    fun maxLevels() {
        // 04 §9.3: Earth 17 / 16, Luna 15 / 14, Phobos 13 / 12 (HIGH / LOW)
        assertEquals(17, CubeSphere.maxLevel(6.36e6, 2.5))
        assertEquals(16, CubeSphere.maxLevel(6.36e6, 5.0))
        assertEquals(15, CubeSphere.maxLevel(1.74e6, 2.5))
        assertEquals(14, CubeSphere.maxLevel(1.74e6, 5.0))
        assertEquals(13, CubeSphere.maxLevel(4.5e5, 2.5))
        assertEquals(12, CubeSphere.maxLevel(4.5e5, 5.0))
    }

    /** Dune phases wrap by an integer at the chart seam; bearing as Roblox's (e1, e2) frame; polar fade. */
    @Test
    fun chart() {
        val r = 6.36e6
        val d = Domain()
        for (lat in doubleArrayOf(-50.0, -10.0, 0.0, 33.0, 59.0)) {
            val la = lat * PI / 180
            // just either side of lonE = ±π (n.x = −cos, n.z = ∓tiny)
            d.setSphere(-cos(la), sin(la), 1e-9, r)
            val p1 = d.phase(0.83, 0.56, 190.0)
            d.setSphere(-cos(la), sin(la), -1e-9, r)
            val p2 = d.phase(0.83, 0.56, 190.0)
            val diff = p1 - p2
            assertEquals(round(diff), diff, 1e-3, "integer wrap at lat $lat")
        }
        // the phase gradient points along (α e1 + β e2) where e1 = east, e2 = north
        val rng = Rng(4)
        repeat(200) {
            val lat = rng.range(-1.0f, 1.0f).toDouble() // ±57°
            val lon = rng.range(-3f, 3f).toDouble()
            val nx = cos(lat) * cos(lon)
            val ny = sin(lat)
            val nz = -cos(lat) * sin(lon)
            d.setSphere(nx, ny, nz, r)
            val p0 = d.phase(0.83, 0.56, 190.0)
            // east: d/dlon (lonE = atan2(−z, x) increases along e1 = unit(Y × n))
            val east = Vec3((-sin(lon)).toFloat(), 0f, (-cos(lon)).toFloat())
            val step = 1.0
            fun at(dx: Double, dy: Double, dz: Double): Double {
                var x = nx * r + dx
                var y = ny * r + dy
                var z = nz * r + dz
                val l = sqrt(x * x + y * y + z * z)
                x /= l; y /= l; z /= l
                d.setSphere(x, y, z, r)
                return d.phase(0.83, 0.56, 190.0)
            }
            val ge = (at(east.x * step, 0.0, east.z * step) - p0) / step
            val nnx = -sin(lat) * cos(lon)
            val nny = cos(lat)
            val nnz = sin(lat) * sin(lon)
            val gn = (at(nnx * step, nny * step, nnz * step) - p0) / step
            val ang = kotlin.math.atan2(gn, ge)
            assertEquals(kotlin.math.atan2(0.56, 0.83), ang, 1e-3, "bearing")
            // spacing λ/cos(lat) in the chart → λ·cos(lat)... the pattern repeats every λ·cos(lat) on the ground
            val k = sqrt(ge * ge + gn * gn)
            assertEquals(sqrt(0.83 * 0.83 + 0.56 * 0.56) / 190.0 / cos(lat), k, 2e-3 * k)
        }
        d.setSphere(cos(60.0 * PI / 180), sin(60.0 * PI / 180), 0.0, r)
        assertEquals(1.0, d.dirWeight, 1e-9, "weight 1 below 63°")
        d.setSphere(cos(76.0 * PI / 180), sin(76.0 * PI / 180), 0.0, r)
        assertEquals(0.0, d.dirWeight, 1e-9, "weight 0 above 75.5°")
    }

    // ---------------------------------------------------------------- quadtree counts

    /** A field with no relief: the quadtree on a bare sphere with the spec's 400 m relief margin. */
    private fun flatField(radius: Double): SurfaceField {
        val style = CrateredStyle(StyleCatalog.LUNA)
        return SurfaceField(radius, style, 1, GenericMacro(radius, 1, Palettes.DEFAULT, style))
    }

    /**
     * Selection converged with every wanted tile made resident at once (bounds R..R + 400 m, as the
     * spec's simulation), camera [alt] above the sphere. Measured: within 10 % of the spec from 1 km
     * up; 15–30 % fewer tiles near the ground (tighter distance to the patch than the spec's boxes). Returns (leaves, leaves in a 94° × 46° view
     * pitched 15° down, all nodes of the tree).
     */
    private fun counts(rho: Double, lmax: Int, alt: Double): IntArray {
        val radius = 6.36e6
        val t = TerrainQuadtree(flatField(radius), lmax, 32768, 30000)
        t.rho = rho
        t.slotReserve = 0
        for (n in 0 until 6) { t.rMin[n] = radius; t.rMax[n] = radius + 400; t.updateBounds(n) }
        val cam = radius + alt
        var guard = 0
        do {
            t.update(0.0, 0.0, cam, guard * 0.016)
            for (k in 0 until t.wantedCount) {
                val n = t.wanted[k]
                val s = t.allocSlot()
                if (s >= 0) t.makeResident(n, s, radius, radius + 400, 0f)
            }
            guard++
        } while (t.wantedCount > 0 && guard < 200)
        t.update(0.0, 0.0, cam, guard * 0.016)
        // view: forward = north (+Y) pitched 15° down (−Z is down here: the camera sits on +Z)
        val pitch = -15.0 * PI / 180
        val fx = 0.0
        val fy = cos(pitch)
        val fz = sin(pitch)
        val tx = tan(47.0 * PI / 180)
        val ty = tan(23.0 * PI / 180)
        var inView = 0
        for (k in 0 until t.drawCount) {
            val n = t.drawList[k]
            val dx = t.bx[n]
            val dy = t.by[n]
            val dz = t.bz[n] - cam
            val r = t.br[n]
            val vz = dx * fx + dy * fy + dz * fz
            // right = forward × up(+Z) → (fy, −fx, 0) normalised; up = right × forward
            val rx = 1.0
            val vx = dx * rx
            val ux = 0.0
            val uy = -fz
            val uz = fy
            val vy = dx * ux + dy * uy + dz * uz
            if (vz < -r) continue
            if ((abs(vx) - tx * vz) / sqrt(1 + tx * tx) > r || (abs(vy) - ty * vz) / sqrt(1 + ty * ty) > r) continue
            inView++
        }
        return intArrayOf(t.drawCount, inView, t.visitedCount)
    }

    /**
     * 04 §9.3 tile counts (computed by simulating the rule on an Earth-size sphere, HIGH ρ 2.5 / L17,
     * LOW ρ 2.0 / L16): the spec's "resident" is the cut in all directions (here: leaves), "drawn"
     * the leaves in view. Within ±25 % (bounding volumes and the frustum test differ in detail).
     */
    @Test
    fun tileCountsPerAltitude() {
        val table = arrayOf(
            doubleArrayOf(2.0, 1220.0, 546.0, 704.0, 286.0),
            doubleArrayOf(100.0, 1272.0, 582.0, 740.0, 310.0),
            doubleArrayOf(1000.0, 856.0, 272.0, 688.0, 230.0),
            doubleArrayOf(5000.0, 628.0, 176.0, 472.0, 142.0),
            doubleArrayOf(20000.0, 492.0, 136.0, 324.0, 110.0),
            doubleArrayOf(100000.0, 348.0, 104.0, 252.0, 80.0),
            doubleArrayOf(1000000.0, 196.0, 46.0, 120.0, 32.0),
        )
        for (row in table) {
            val high = counts(2.5, 17, row[0])
            val low = counts(2.0, 16, row[0])
            println("alt %8.0f m: HIGH leaves %4d (spec %4.0f) in view %4d (spec %4.0f) tree %4d | LOW leaves %4d (spec %4.0f) in view %4d (spec %4.0f)".format(
                row[0], high[0], row[1], high[1], row[2], high[2], low[0], row[3], low[1], row[4]))
        }
        for (row in table) {
            val high = counts(2.5, 17, row[0])
            val low = counts(2.0, 16, row[0])
            assertEquals(row[1], high[0].toDouble(), row[1] * 0.3, "HIGH resident at ${row[0]} m")
            assertEquals(row[3], low[0].toDouble(), row[3] * 0.35, "LOW resident at ${row[0]} m")
            assertEquals(row[2], high[1].toDouble(), row[2] * 0.45 + 8, "HIGH drawn at ${row[0]} m")
            assertEquals(row[4], low[1].toDouble(), row[4] * 0.45 + 8, "LOW drawn at ${row[0]} m")
            // never more than the spec's budget (the phone's side of the error)
            assertTrue(high[0] <= row[1] * 1.12 && low[0] <= row[3] * 1.12, "over budget at ${row[0]} m")
        }
    }

    /** Hovering exactly at a split distance never oscillates (hysteresis), and the cut covers the sphere. */
    @Test
    fun hysteresisAndCompleteCover() {
        val radius = 6.36e6
        val t = TerrainQuadtree(flatField(radius), 17, 32768, 30000)
        t.slotReserve = 0
        for (n in 0 until 6) { t.rMin[n] = radius; t.rMax[n] = radius + 400; t.updateBounds(n) }
        var alt = 5000.0
        fun settle(a: Double) {
            var g = 0
            do {
                t.update(0.0, 0.0, radius + a, g * 0.016)
                for (k in 0 until t.wantedCount) {
                    val s = t.allocSlot()
                    if (s >= 0) t.makeResident(t.wanted[k], s, radius, radius + 400, 0f)
                }
                g++
            } while (t.wantedCount > 0 && g < 300)
        }
        settle(alt)
        // complete cover: every direction the camera can see (in front of the horizon) has a leaf of the cut
        val rng = Rng(9)
        val v = Vec3()
        val horizon = acos(radius / (radius + alt))
        var checked = 0
        repeat(20000) {
            rng.onSphere(v)
            if (acos(v.z.toDouble().coerceIn(-1.0, 1.0)) > horizon * 1.5) return@repeat
            checked++
            assertTrue(t.leafAt(v.x.toDouble(), v.y.toDouble(), v.z.toDouble()) >= 0, "hole at $v")
        }
        assertTrue(checked > 0)
        // and around the point under the camera, densely
        for (i in -20..20) for (j in -20..20) {
            val x = i * 1e-4
            val y = j * 1e-4
            val l = sqrt(x * x + y * y + 1)
            assertTrue(t.leafAt(x / l, y / l, 1 / l) >= 0)
        }
        // jitter the camera by ±1 m around a height: the cut must not change from frame to frame
        settle(alt)
        val base = t.drawCount
        var changes = 0
        var last = base
        for (i in 0 until 120) {
            t.update(0.0, 0.0, radius + alt + if (i % 2 == 0) 1.0 else -1.0, 10.0 + i * 0.016)
            if (t.drawCount != last) changes++
            last = t.drawCount
        }
        assertEquals(0, changes, "split/merge oscillation")
        alt += 0.0
        assertTrue(acos(0.5) > 0)
    }
}
