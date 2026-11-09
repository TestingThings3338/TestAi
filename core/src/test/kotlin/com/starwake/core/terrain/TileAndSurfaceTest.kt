package com.starwake.core.terrain

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Rng
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tiles (continuity, geomorphing, skirts) and the ground queries against the drawn surface (§9.6, §9.10). */
class TileAndSurfaceTest {
    private val earthR = 2120.0 * 3000
    private val lunaR = 580.0 * 3000

    private fun earth(): SurfaceField {
        val p = SurfaceProfiles.forBody("Earth", "Terrestrial World", 3, "Sol")
        return SurfaceField.create(earthR, StyleCatalog.chooseStyle(p)!!, 3, "Terrestrial World").also {
            it.buildMeanColours()
            it.macroLevel = CubeSphere.maxLevel(earthR, 2.5) - 2
        }
    }

    private fun luna(): SurfaceField {
        val p = SurfaceProfiles.forBody("Luna", "Moon", 94, "Sol")
        return SurfaceField.create(lunaR, StyleCatalog.chooseStyle(p)!!, 94, "Moon").also {
            it.buildMeanColours()
            it.macroLevel = CubeSphere.maxLevel(lunaR, 2.5) - 2
        }
    }

    /** Land with hills and mountains on the test Earth (the harness's descent target). */
    private val earthLand = normalize(doubleArrayOf(-0.4729, 0.6775, 0.5634))

    private fun normalize(v: DoubleArray): DoubleArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    /** (face, i, j) of the level-[level] tile containing direction [d]. */
    private fun tileAt(d: DoubleArray, level: Int): IntArray {
        val f = CubeSphere.faceOf(d[0], d[1], d[2])
        val ab = DoubleArray(2)
        CubeSphere.coords(f, d[0], d[1], d[2], ab)
        val m = 1 shl level
        val i = floor((ab[0] + 1) / 2 * m).toInt().coerceIn(0, m - 1)
        val j = floor((ab[1] + 1) / 2 * m).toInt().coerceIn(0, m - 1)
        return intArrayOf(f, i, j)
    }

    private val staging = TileStaging(0, TileFormat.TILE_BYTES)

    @Test
    fun neighbouringTilesShareTheirEdges() {
        val field = earth()
        val a = TileBuilder(field)
        val b = TileBuilder(field)
        val da = DoubleArray(3)
        val db = DoubleArray(3)
        for (level in intArrayOf(9, 12, 15, 16, 17)) {
            val t = tileAt(earthLand, level)
            a.build(t[0], level, t[1], t[2], staging)
            b.build(t[0], level, t[1] + 1, t[2], staging)
            var worst = 0.0
            for (j in 0..32) {
                a.vertexDir(32, j, da)
                b.vertexDir(0, j, db)
                assertTrue(da.contentEquals(db), "L$level shared edge direction $j")
                worst = max(worst, abs(a.vertexFloor(32, j) - b.vertexFloor(0, j)))
            }
            assertTrue(worst < 1e-6, "L$level: shared edge heights differ by $worst m")
        }
    }

    /** Across a cube-face edge: the tiles on both faces meet (below the lattice levels: to the µm). */
    @Test
    fun cubeFaceEdgesAreSeamless() {
        val field = earth()
        val a = TileBuilder(field)
        val b = TileBuilder(field)
        val da = DoubleArray(3)
        val db = DoubleArray(3)
        for (level in intArrayOf(6, 10, 14, 17)) {
            val m = 1 shl level
            // face 0 (+X) at a = 1 meets face 5 (−Z) at a = −1, same b (both V = +Y)
            val j = m / 2 + 3
            a.build(0, level, m - 1, j, staging)
            b.build(5, level, 0, j, staging)
            var worst = 0.0
            for (k in 0..32) {
                a.vertexDir(32, k, da)
                b.vertexDir(0, k, db)
                assertTrue(da.contentEquals(db), "L$level face edge direction $k")
                worst = max(worst, abs(a.vertexFloor(32, k) - b.vertexFloor(0, k)))
            }
            // the planet-scale lattice is per face: on the two finest levels the faces may differ by its
            // interpolation error (a few cm); elsewhere the edge is exact
            val tol = if (level > field.macroLevel) 0.05 else 1e-6
            assertTrue(worst < tol, "L$level: face edge heights differ by $worst m")
        }
    }

    /** At the finest level every vertex is exactly what the ground queries return (§9.1 invariant 3). */
    @Test
    fun finestVerticesEqualTheQueries() {
        for (field in listOf(earth(), luna())) {
            val surface = PlanetSurface(field)
            val lmax = CubeSphere.maxLevel(field.radius, 2.5)
            val b = TileBuilder(field)
            val d = DoubleArray(3)
            val t = tileAt(if (field.radius == earthR) earthLand else normalize(doubleArrayOf(0.13, 0.71, 0.69)), lmax)
            b.build(t[0], lmax, t[1], t[2], staging)
            var worst = 0.0
            for (j in 0..32) for (i in 0..32) {
                b.vertexDir(i, j, d)
                worst = max(worst, abs(b.vertexFloor(i, j) - surface.floorAt(d[0], d[1], d[2])))
            }
            assertTrue(worst < 1e-3, "${field.style.key}: vertex vs query $worst m")
        }
    }

    /**
     * Geomorphing: a child's morph targets are its parent's surface (the parent's own vertices at the
     * even positions, the parent's edge or diagonal midpoints at the odd ones), so at morph 1 the child
     * is the parent; at the finest level the even vertices do not move at all.
     */
    @Test
    fun geomorphTargetsAreTheParentSurface() {
        for (field in listOf(earth(), luna())) {
            val lmax = CubeSphere.maxLevel(field.radius, 2.5)
            val parent = TileBuilder(field)
            val child = TileBuilder(field)
            for (level in intArrayOf(8, 12, lmax - 2, lmax - 1, lmax)) {
                val pt = tileAt(if (field.radius == earthR) earthLand else normalize(doubleArrayOf(0.13, 0.71, 0.69)), level - 1)
                parent.build(pt[0], level - 1, pt[1], pt[2], staging)
                // the child in the parent's upper-right quadrant
                val ci = pt[1] * 2 + 1
                val cj = pt[2] * 2 + 1
                child.build(pt[0], level, ci, cj, staging)
                var worst = 0.0
                for (j in 0..32) for (i in 0..32) {
                    val pi = 16 + i / 2
                    val pj = 16 + j / 2
                    val expect = when {
                        i % 2 == 0 && j % 2 == 0 -> parent.vertexFloor(pi, pj)
                        i % 2 == 1 && j % 2 == 0 -> 0.5 * (parent.vertexFloor(pi, pj) + parent.vertexFloor(pi + 1, pj))
                        i % 2 == 0 -> 0.5 * (parent.vertexFloor(pi, pj) + parent.vertexFloor(pi, pj + 1))
                        else -> 0.5 * (parent.vertexFloor(pi, pj) + parent.vertexFloor(pi + 1, pj + 1))
                    }
                    worst = max(worst, abs(child.vertexTarget(i, j) - expect))
                }
                assertTrue(worst < 1e-3, "${field.style.key} L$level: morph target vs parent $worst m")
                if (level == lmax) {
                    for (j in 0..32 step 2) for (i in 0..32 step 2) {
                        assertEquals(child.vertexFloor(i, j), child.vertexTarget(i, j), 1e-9, "even vertex moves at L$lmax")
                    }
                }
            }
        }
    }

    /** The packed 24-byte vertices decode to the built positions; skirts hang below the edges. */
    @Test
    fun packedVerticesAndSkirts() {
        val field = earth()
        val b = TileBuilder(field)
        val t = tileAt(earthLand, 14)
        b.build(t[0], 14, t[1], t[2], staging)
        val buf = staging.buffer
        val d = DoubleArray(3)
        var worst = 0.0
        for (j in 0..32) for (i in 0..32) {
            val o = (j * 33 + i) * TileFormat.STRIDE
            val x = staging.cx + buf.getShort(o) / 32767.0 * staging.hx
            val y = staging.cy + buf.getShort(o + 2) / 32767.0 * staging.hy
            val z = staging.cz + buf.getShort(o + 4) / 32767.0 * staging.hz
            b.vertexDir(i, j, d)
            val r = b.vertexFloor(i, j)
            worst = max(worst, sqrt((x - d[0] * r).let { it * it } + (y - d[1] * r).let { it * it } + (z - d[2] * r).let { it * it }))
        }
        val quantum = max(staging.hx, max(staging.hy, staging.hz)) / 32767.0
        assertTrue(worst < 2 * quantum * sqrt(3.0), "decoded vertex error $worst m (quantum $quantum m)")
        for (k in 0..32) {
            val e = k * TileFormat.STRIDE
            val s = TileFormat.skirtBottom(k) * TileFormat.STRIDE
            val ex = staging.cx + buf.getShort(e) / 32767.0 * staging.hx
            val ey = staging.cy + buf.getShort(e + 2) / 32767.0 * staging.hy
            val ez = staging.cz + buf.getShort(e + 4) / 32767.0 * staging.hz
            val sx = staging.cx + buf.getShort(s) / 32767.0 * staging.hx
            val sy = staging.cy + buf.getShort(s + 2) / 32767.0 * staging.hy
            val sz = staging.cz + buf.getShort(s + 4) / 32767.0 * staging.hz
            assertTrue(sqrt(sx * sx + sy * sy + sz * sz) < sqrt(ex * ex + ey * ey + ez * ez) - 1.0, "skirt $k below its edge")
        }
        // the index buffer references every vertex and only valid ones
        val idx = TileFormat.indices()
        assertEquals(TileFormat.INDICES, idx.size)
        val used = BooleanArray(TileFormat.VERTICES)
        for (v in idx) used[v.toInt()] = true
        assertTrue(used.all { it })
    }

    /**
     * The queries equal the drawn surface: between the finest vertices the drawn floor is the
     * triangle; the analytic floor differs from it only by the interpolation error of 2.5 m spacing.
     */
    @Test
    fun queriesMatchTheDrawnSurface() {
        for ((field, dir, meanTol, maxTol) in listOf(
            Quad(earth(), earthLand, 0.05, 0.6),
            Quad(luna(), normalize(doubleArrayOf(0.13, 0.71, 0.69)), 0.08, 2.0),
        )) {
            val surface = PlanetSurface(field)
            val lmax = CubeSphere.maxLevel(field.radius, 2.5)
            val b = TileBuilder(field)
            val t = tileAt(dir, lmax)
            b.build(t[0], lmax, t[1], t[2], staging)
            val rng = Rng(7)
            val m = 1 shl lmax
            val d = DoubleArray(3)
            var sum = 0.0
            var worst = 0.0
            val n = 2000
            val p00 = DoubleArray(3)
            val p10 = DoubleArray(3)
            val p01 = DoubleArray(3)
            val p11 = DoubleArray(3)
            for (k in 0 until n) {
                val i = rng.nextInt(32)
                val j = rng.nextInt(32)
                val fu = rng.nextFloat().toDouble()
                val fv = rng.nextFloat().toDouble()
                // the point's face coordinates and direction
                val a = 2.0 * (t[1] * 32 + i + fu) / (32.0 * m) - 1.0
                val bb = 2.0 * (t[2] * 32 + j + fv) / (32.0 * m) - 1.0
                CubeSphere.dir(t[0], a, bb, d)
                // the drawn triangle: (0,0)-(1,0)-(1,1) when fu ≥ fv, else (0,0)-(1,1)-(0,1)
                pos(b, i, j, p00); pos(b, i + 1, j, p10); pos(b, i, j + 1, p01); pos(b, i + 1, j + 1, p11)
                val drawn = if (fu >= fv) rayTriangle(d, p00, p10, p11) else rayTriangle(d, p00, p11, p01)
                val diff = abs(drawn - surface.floorAt(d[0], d[1], d[2]))
                sum += diff
                worst = max(worst, diff)
            }
            println("${field.style.key}: query vs drawn floor: mean %.3f m, max %.3f m".format(sum / n, worst))
            assertTrue(sum / n < meanTol, "${field.style.key}: mean ${sum / n}")
            assertTrue(worst < maxTol, "${field.style.key}: max $worst")
        }
    }

    private data class Quad(val field: SurfaceField, val dir: DoubleArray, val meanTol: Double, val maxTol: Double)

    private fun pos(b: TileBuilder, i: Int, j: Int, out: DoubleArray) {
        b.vertexDir(i, j, out)
        val r = b.vertexFloor(i, j)
        out[0] *= r; out[1] *= r; out[2] *= r
    }

    /** Distance along the unit ray [d] from the centre to the plane of triangle (a, b, c). */
    private fun rayTriangle(d: DoubleArray, a: DoubleArray, b: DoubleArray, c: DoubleArray): Double {
        val e1 = doubleArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val e2 = doubleArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
        val n = doubleArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
        return (n[0] * a[0] + n[1] * a[1] + n[2] * a[2]) / (n[0] * d[0] + n[1] * d[1] + n[2] * d[2])
    }

    /** floorHit's normal (±2 m differences) agrees with the finest triangles' normal within 2°. */
    @Test
    fun floorHitNormalMatchesTheMesh() {
        val field = earth()
        val surface = PlanetSurface(field)
        surface.setPose(DVec3(), Quat())
        val lmax = CubeSphere.maxLevel(field.radius, 2.5)
        val b = TileBuilder(field)
        val t = tileAt(earthLand, lmax)
        b.build(t[0], lmax, t[1], t[2], staging)
        val hit = FloorHit()
        val p00 = DoubleArray(3)
        val p10 = DoubleArray(3)
        val p01 = DoubleArray(3)
        val p11 = DoubleArray(3)
        val d = DoubleArray(3)
        var sum = 0.0
        var worst = 0.0
        var n = 0
        for (j in 2 until 30 step 3) for (i in 2 until 30 step 3) {
            pos(b, i, j, p00); pos(b, i + 1, j, p10); pos(b, i, j + 1, p01); pos(b, i + 1, j + 1, p11)
            // the quad's mean normal (both triangles)
            val nx = (p10[1] - p00[1]) * (p01[2] - p00[2]) - (p10[2] - p00[2]) * (p01[1] - p00[1])
            val ny = (p10[2] - p00[2]) * (p01[0] - p00[0]) - (p10[0] - p00[0]) * (p01[2] - p00[2])
            val nz = (p10[0] - p00[0]) * (p01[1] - p00[1]) - (p10[1] - p00[1]) * (p01[0] - p00[0])
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            val m = 1 shl lmax
            CubeSphere.dir(t[0], 2.0 * (t[1] * 32 + i + 0.5) / (32.0 * m) - 1.0, 2.0 * (t[2] * 32 + j + 0.5) / (32.0 * m) - 1.0, d)
            val r = surface.floorAt(d[0], d[1], d[2]) + 30
            assertTrue(surface.floorHit(DVec3(d[0] * r, d[1] * r, d[2] * r), 400.0, hit))
            val dot = (hit.normal.x * nx + hit.normal.y * ny + hit.normal.z * nz) / nl
            val ang = Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
            sum += ang
            worst = max(worst, ang)
            n++
            assertEquals(30.0, hit.altitude, 1e-6)
        }
        println("floorHit normal vs mesh: mean %.2f°, max %.2f°".format(sum / n, worst))
        assertTrue(sum / n < 2.0, "mean angle ${sum / n}")
    }

    /** The Roblox contact rule: crash above 38 m/s on solid ground only; slide and snap otherwise. */
    @Test
    fun contactRule() {
        val field = earth()
        val surface = PlanetSurface(field)
        surface.setPose(DVec3(), Quat())
        val d = earthLand
        val floor = surface.floorAt(d[0], d[1], d[2])
        val c = PlanetSurface.Contact()
        val pos = DVec3(d[0] * (floor + 5), d[1] * (floor + 5), d[2] * (floor + 5))
        val down = Vec3((-d[0]).toFloat(), (-d[1]).toFloat(), (-d[2]).toFloat())
        // gentle: 10 m/s down → no crash, snapped to the clearance, sink removed
        assertTrue(surface.contact(pos, Vec3().set(down).scale(10f), 12.0, c))
        assertTrue(!c.crashed && c.landed)
        assertEquals(12.0, surface.altitudeAt(c.position), 0.5)
        assertTrue(c.velocity.dot(down) < 1f)
        // hard: 45 m/s down on solid ground (no quadtree: every ground is solid) → crash
        assertTrue(surface.contact(pos, Vec3().set(down).scale(45f), 12.0, c))
        assertTrue(c.crashed)
        // far above → no contact
        val high = DVec3(d[0] * (floor + 500), d[1] * (floor + 500), d[2] * (floor + 500))
        assertTrue(!surface.contact(high, Vec3().set(down).scale(45f), 12.0, c))
        // gravity at 35 % of 9.81 m/s², towards the centre
        val g = surface.gravityAt(pos, Vec3())
        assertEquals(9.81 * 0.35, g.length().toDouble(), 1e-4)
        assertTrue(g.dot(down) > 0.999f * g.length())
    }
}
