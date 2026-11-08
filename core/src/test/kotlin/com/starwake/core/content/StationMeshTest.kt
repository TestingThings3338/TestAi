package com.starwake.core.content

import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StationMeshTest {
    private val meshes = Array(StationMesh.MODELS) { StationMesh.build(it) }
    private val soups = Array(StationMesh.MODELS) { Soup(meshes[it]) }

    /**
     * Soup's vertex keys cover ±104 m at 0.1 mm, so the topology tests run on a copy scaled by
     * 1/10 (keys then quantise to 1 mm; orientation and ray parity are scale invariant).
     */
    private val scaled = Array(StationMesh.MODELS) { m ->
        val d = meshes[m].copyOf()
        var i = 0
        while (i < d.size) {
            d[i] *= 0.1f; d[i + 1] *= 0.1f; d[i + 2] *= 0.1f
            i += 8
        }
        Soup(d)
    }
    private val parts = Array(StationMesh.MODELS) { scaled[it].connectedParts() }

    private val budgets = intArrayOf(7000, 2500)
    private val emissive = setOf(StationMesh.WINDOW, StationMesh.SLOT_GREEN, StationMesh.LIGHT_WHITE, StationMesh.BEACON, StationMesh.BAY_GLOW)

    private class Box {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        fun add(x: Float, y: Float, z: Float) {
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        }
        fun contains(p: Vec3, slack: Float = 0f) =
            p.x in minX - slack..maxX + slack && p.y in minY - slack..maxY + slack && p.z in minZ - slack..maxZ + slack
        override fun toString() = "x $minX..$maxX, y $minY..$maxY, z $minZ..$maxZ"
    }

    private fun bounds(m: Int): Box {
        val s = soups[m]
        val b = Box()
        for (t in 0 until s.triangles) for (v in 0 until 3) b.add(s.x(t, v), s.y(t, v), s.z(t, v))
        return b
    }

    @Test
    fun vertexFormatIsWholeTrianglesOfEightFloats() {
        for (m in 0 until StationMesh.MODELS) {
            val raw = meshes[m]
            assertTrue(raw.isNotEmpty(), "model $m is empty")
            assertEquals(0, raw.size % 24, "model $m: ${raw.size} floats is not whole triangles of 8-float vertices")
            for (i in raw.indices) assertTrue(raw[i].isFinite(), "model $m float $i is ${raw[i]}")
        }
    }

    @Test
    fun triangleBudgets() {
        for (m in 0 until StationMesh.MODELS) {
            val n = soups[m].triangles
            println("StationMesh model $m: $n triangles in ${parts[m].size} parts")
            assertTrue(n <= budgets[m], "model $m has $n triangles, budget ${budgets[m]}")
            assertTrue(n >= budgets[m] / 5, "model $m has only $n triangles - suspiciously little geometry")
        }
    }

    @Test
    fun buildIsDeterministic() {
        for (m in 0 until StationMesh.MODELS) assertContentEquals(meshes[m], StationMesh.build(m), "model $m")
    }

    @Test
    fun normalsAreUnitFlatAndMatchTheWinding() {
        for (m in 0 until StationMesh.MODELS) {
            val s = soups[m]
            for (t in 0 until s.triangles) {
                val n = s.normal(t)
                assertTrue(abs(n.length() - 1f) < 1e-4f, "model $m triangle $t normal $n not unit")
                for (v in 1..2) {
                    val nv = s.normal(t, v)
                    assertTrue(nv.x == n.x && nv.y == n.y && nv.z == n.z, "model $m triangle $t is not flat shaded")
                }
                val w = s.windingCross(t).normalize()
                assertTrue(w.dot(n) > 0.999f, "model $m triangle $t winding $w disagrees with normal $n")
            }
        }
    }

    @Test
    fun materialsAreIntegralPaletteIndicesAndAllAreUsed() {
        val used = HashSet<Int>()
        for (m in 0 until StationMesh.MODELS) {
            val s = soups[m]
            for (t in 0 until s.triangles) for (v in 0 until 3) {
                val mat = s.material(t, v)
                assertEquals(mat, mat.toInt().toFloat(), "model $m triangle $t material $mat is not integral")
                assertTrue(mat.toInt() in 0..9, "model $m triangle $t material $mat outside 0..9")
                used.add(mat.toInt())
            }
        }
        assertEquals((0..9).toSet(), used, "every palette slot should be used by some station")
    }

    @Test
    fun shadeJitterStaysNearOne() {
        for (m in 0 until StationMesh.MODELS) {
            val s = soups[m]
            for (t in 0 until s.triangles) {
                val sh = s.shade(t)
                assertTrue(sh in 0.85f..1.1f, "model $m triangle $t shade $sh")
                assertEquals(sh, s.shade(t, 1))
                assertEquals(sh, s.shade(t, 2))
            }
        }
    }

    @Test
    fun paletteAndParamsAreSane() {
        val pal = StationMesh.palette
        val par = StationMesh.params
        assertEquals(40, pal.size)
        assertEquals(40, par.size)
        for (i in 0 until 10) {
            for (c in 0..2) assertTrue(pal[i * 4 + c] in 0f..1f, "palette $i channel $c = ${pal[i * 4 + c]}")
            assertTrue(pal[i * 4 + 3] > 0f && pal[i * 4 + 3] <= 1f, "palette $i roughness ${pal[i * 4 + 3]}")
            assertTrue(par[i * 4] in 0f..1f, "material $i metallic ${par[i * 4]}")
            val e = par[i * 4 + 1]
            if (i in emissive) assertTrue(e > 0f, "material $i should glow") else assertEquals(0f, e, "material $i should not glow")
            assertEquals(0f, par[i * 4 + 2], "material $i: no engine-driven emission on stations")
            assertEquals(0f, par[i * 4 + 3])
        }
    }

    @Test
    fun boundingRadiusMatchesRadius() {
        for (m in 0 until StationMesh.MODELS) {
            val s = soups[m]
            var maxR = 0f
            for (t in 0 until s.triangles) for (v in 0 until 3) maxR = maxOf(maxR, Soup.len(s.x(t, v), s.y(t, v), s.z(t, v)))
            val r = StationMesh.radius(m)
            println("StationMesh model $m: furthest vertex %.2f m, radius() %.1f m".format(maxR, r))
            assertTrue(maxR <= r, "model $m: vertex at $maxR m outside radius() $r")
            assertTrue(r <= maxR * 1.01f, "model $m: radius() $r is loose (furthest vertex $maxR)")
        }
    }

    @Test
    fun dimensionsMatchTheDesign() {
        val ring = bounds(StationMesh.RING_STATION)
        val ringSpan = ring.maxX - ring.minX
        assertTrue(ringSpan in 1750f..1850f, "ring station should be ~1.8 km across: $ring")
        assertTrue(abs(ring.maxY - ring.minY - ringSpan) < 1f, "the ring is round: $ring")
        val outpost = bounds(StationMesh.OUTPOST)
        val size = maxOf(outpost.maxX - outpost.minX, outpost.maxY - outpost.minY, outpost.maxZ - outpost.minZ)
        assertTrue(size in 400f..560f, "outpost should be ~500 m: $outpost")
    }

    @Test
    fun everyPartIsAClosedSurface() {
        for (m in 0 until StationMesh.MODELS) {
            val s = scaled[m]
            for (part in parts[m]) {
                val edges = HashMap<Pair<Long, Long>, Int>()
                for (t in part) for (e in 0 until 3) {
                    val a = s.key(t, e)
                    val b = s.key(t, (e + 1) % 3)
                    val k = if (a < b) a to b else b to a
                    edges[k] = (edges[k] ?: 0) + 1
                }
                val open = edges.filterValues { it != 2 }
                assertTrue(open.isEmpty(), "model $m: part at ${soups[m].centroid(part[0])} has ${open.size} edges not shared by exactly two faces")
            }
        }
    }

    @Test
    fun everyFacePointsOutOfItsOwnPart() {
        for (m in 0 until StationMesh.MODELS) {
            val bad = inwardFacing(scaled[m], parts[m])
            val okPct = 100.0 * (scaled[m].triangles - bad.size) / scaled[m].triangles
            println("StationMesh model $m: outward by ray parity %.2f%%".format(okPct))
            assertTrue(bad.isEmpty(), "model $m: ${bad.size} faces point into their part, e.g. ${bad.take(5).map { soups[m].centroid(it) }}")
        }
    }

    @Test
    fun soupParityHelperAgrees() {
        // the shared helper casts exactly along the normal, so a few edge-grazing rays are tolerated
        for (m in 0 until StationMesh.MODELS) {
            val bad = scaled[m].inwardFacingTriangles(parts[m])
            val okPct = 100.0 * (scaled[m].triangles - bad.size) / scaled[m].triangles
            println("StationMesh model $m: outward by Soup.inwardFacingTriangles %.2f%%".format(okPct))
            assertTrue(okPct >= 99.5, "model $m: only %.2f%% outward".format(okPct))
        }
    }

    @Test
    fun parityCheckFlagsAnInsideOutPart() {
        // self-test of the check above: turn one closed part inside out and expect all of it flagged
        val d = scaled[StationMesh.OUTPOST].d.copyOf()
        val part = parts[StationMesh.OUTPOST].maxBy { it.size }
        for (t in part) {
            val o = t * 24
            for (k in 0 until 8) {
                val tmp = d[o + 8 + k]; d[o + 8 + k] = d[o + 16 + k]; d[o + 16 + k] = tmp
            }
            for (v in 0 until 3) for (c in 3..5) d[o + v * 8 + c] = -d[o + v * 8 + c]
        }
        val flipped = Soup(d)
        val bad = inwardFacing(flipped, listOf(part)).toSet()
        assertEquals(part.toSet(), bad, "every face of the inverted part (${part.size} faces) must be flagged")
    }

    @Test
    fun noTriangleIsDuplicated() {
        for (m in 0 until StationMesh.MODELS) {
            val s = scaled[m]
            val seen = HashMap<List<Long>, Int>()
            val dups = ArrayList<Int>()
            for (t in 0 until s.triangles) if (seen.putIfAbsent(s.triangleKey(t), t) != null) dups.add(t)
            assertTrue(dups.isEmpty(), "model $m: ${dups.size} duplicated triangles, e.g. ${dups.take(5).map { soups[m].centroid(it) }}")
        }
    }

    @Test
    fun dockPortSitsInTheSlotEntrance() {
        val pos = Vec3()
        val dir = Vec3()
        val slot = Vec3()
        for (m in 0 until StationMesh.MODELS) {
            StationMesh.dockPort(m, pos, dir)
            StationMesh.slotSize(m, slot)
            assertTrue(abs(dir.length() - 1f) < 1e-5f, "model $m dock direction $dir not unit")
            val b = bounds(m)
            assertTrue(b.contains(pos), "model $m dock port $pos outside the hull box $b")
            // the entrance is on the hull's front: nothing but trim sticks out further along dir
            val front = -(b.minZ)
            val ahead = -pos.z
            assertTrue(front - ahead in 0f..15f, "model $m: dock port ${pos.z} is not at the front face (hull min z ${b.minZ})")
            assertTrue(slot.x >= 20f && slot.y >= 10f && slot.z >= 40f, "model $m slot too small for ships: $slot")
            assertTrue(slot.z < StationMesh.radius(m), "model $m slot depth")
        }
    }

    @Test
    fun theSlotIsARecessedOpeningWithAGlowingBackWall() {
        val pos = Vec3()
        val dir = Vec3()
        val slot = Vec3()
        for (m in 0 until StationMesh.MODELS) {
            val s = soups[m]
            StationMesh.dockPort(m, pos, dir)
            StationMesh.slotSize(m, slot)
            val start = 2000.0
            // rays flown in along the approach path, anywhere across the inner 80 % x 60 % of the opening
            for (fx in floatArrayOf(0f, 0.8f, -0.8f)) for (fy in floatArrayOf(0f, 0.6f, -0.6f)) {
                val ox = pos.x + fx * slot.x + dir.x * start
                val oy = pos.y + fy * slot.y + dir.y * start
                val oz = pos.z + dir.z * start
                val (t, tri) = firstHit(s, ox, oy, oz, -dir.x.toDouble(), -dir.y.toDouble(), -dir.z.toDouble())
                assertTrue(tri >= 0, "model $m: ray at ($fx, $fy) of the opening hits nothing")
                assertEquals(StationMesh.BAY_GLOW, s.material(tri).toInt(), "model $m: ray at ($fx, $fy) of the opening must reach the back wall, hit ${s.centroid(tri)}")
                val depth = t - start
                assertTrue(abs(depth - slot.z) < 0.5, "model $m: back wall at depth $depth, expected ${slot.z}")
                assertTrue(s.normal(tri).dot(dir) > 0.99f, "model $m: the back wall faces the approach")
            }
            // just outside the opening the ray hits the solid front plate or its trim, at the entrance
            for ((fx, fy) in listOf(1.4f to 0f, -1.4f to 0f, 0f to 1.6f, 0f to -1.6f)) {
                val (t, tri) = firstHit(s, pos.x + fx * slot.x + dir.x * start.toFloat(), pos.y + fy * slot.y + dir.y * start.toFloat(), pos.z + dir.z * start.toFloat(), -dir.x.toDouble(), -dir.y.toDouble(), -dir.z.toDouble())
                assertTrue(tri >= 0 && abs(t - start) < 16.0, "model $m: no hull around the opening at ($fx, $fy): t=$t")
                assertTrue(s.material(tri).toInt() != StationMesh.BAY_GLOW, "model $m: back wall visible outside the opening")
            }
            // the tunnel has walls: a sideways ray from the tunnel axis hits a wall at the slot's half width
            for (sx in floatArrayOf(1f, -1f)) {
                val (t, tri) = firstHit(s, pos.x.toDouble(), pos.y.toDouble(), (pos.z - dir.z * slot.z * 0.5f).toDouble(), sx.toDouble(), 0.0, 0.0)
                assertTrue(tri >= 0 && t <= slot.x + 0.01, "model $m: no tunnel wall at x ${sx * slot.x} (t=$t)")
            }
        }
    }

    @Test
    fun lightsSitOnGlowingParts() {
        for (m in 0 until StationMesh.MODELS) {
            val l = StationMesh.lights(m)
            val s = soups[m]
            assertTrue(l.isNotEmpty() && l.size % 6 == 0, "model $m lights: ${l.size} floats")
            assertTrue(l.size / 6 in 4..16, "model $m: ${l.size / 6} lights")
            var red = 0
            var green = 0
            for (i in 0 until l.size / 6) {
                val p = Vec3(l[i * 6], l[i * 6 + 1], l[i * 6 + 2])
                for (c in 3..5) assertTrue(l[i * 6 + c] in 0f..1f, "model $m light $i colour")
                assertTrue(p.length() <= StationMesh.radius(m), "model $m light $i at $p outside radius")
                val r = l[i * 6 + 3]
                val g = l[i * 6 + 4]
                // each sprite sits in (or on) a glowing housing of its own colour
                val housing = when {
                    r > 0.9f && g < 0.3f -> StationMesh.BEACON.also { red++ }
                    g > 0.9f && r < 0.5f -> StationMesh.SLOT_GREEN.also { green++ }
                    r > 0.9f && g > 0.9f -> StationMesh.LIGHT_WHITE
                    else -> -1
                }
                assertTrue(housing >= 0, "model $m light $i has an unexpected colour")
                var best = Float.MAX_VALUE
                for (t in 0 until s.triangles) {
                    if (s.material(t).toInt() == housing) best = minOf(best, pointTriangleDistance(p, s, t))
                }
                assertTrue(best <= 8f, "model $m light $i at $p is $best m from the nearest housing of material $housing")
            }
            assertTrue(red >= 2, "model $m needs red navigation beacons")
            assertEquals(4, green, "model $m: four green slot corner lights")
        }
    }

    @Test
    fun outpostHasALandingPadFacingUp() {
        val pos = Vec3()
        val up = Vec3()
        assertEquals(0f, StationMesh.landingPad(StationMesh.RING_STATION, pos, up))
        val r = StationMesh.landingPad(StationMesh.OUTPOST, pos, up)
        assertTrue(r > 30f, "pad radius $r")
        assertTrue(abs(up.length() - 1f) < 1e-5f)
        val s = soups[StationMesh.OUTPOST]
        // rays dropped onto the pad hit its upward facing top surface at the given height
        for ((dx, dz) in listOf(0f to 0f, 0.7f to 0f, -0.7f to 0f, 0f to 0.7f, 0f to -0.7f)) {
            val ox = pos.x + dx * r + up.x * 500f
            val oy = pos.y + up.y * 500f
            val oz = pos.z + dz * r + up.z * 500f
            val (t, tri) = firstHit(s, ox, oy, oz, -up.x.toDouble(), -up.y.toDouble(), -up.z.toDouble())
            assertTrue(tri >= 0 && abs(t - 500.0) < 0.01, "pad surface at ($dx, $dz): t=$t")
            assertTrue(s.normal(tri).dot(up) > 0.999f)
        }
        // the pad's rim carries a lit band
        var lit = 0
        for (t in 0 until s.triangles) {
            if (s.material(t).toInt() != StationMesh.LIGHT_WHITE) continue
            val c = s.centroid(t)
            val rr = Soup.len(c.x - pos.x, 0f, c.z - pos.z)
            if (rr > r && abs(c.y - pos.y) < 4f) lit++
        }
        assertTrue(lit >= 16, "pad rim should be lit all the way round ($lit lit faces)")
    }

    // ------------------------------------------------------------------ helpers

    /** Nearest intersection along the ray (any facing): (distance, triangle) or (inf, -1). */
    private fun firstHit(s: Soup, ox: Float, oy: Float, oz: Float, dx: Double, dy: Double, dz: Double) =
        firstHit(s, ox.toDouble(), oy.toDouble(), oz.toDouble(), dx, dy, dz)

    private fun firstHit(s: Soup, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Pair<Double, Int> {
        var best = Double.POSITIVE_INFINITY
        var bestTri = -1
        for (t in 0 until s.triangles) {
            val d = rayT(s, t, ox, oy, oz, dx, dy, dz)
            if (d < best) {
                best = d; bestTri = t
            }
        }
        return best to bestTri
    }

    /** Möller–Trumbore distance to triangle t, or +inf. */
    private fun rayT(s: Soup, t: Int, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double {
        val ax = s.x(t, 0).toDouble()
        val ay = s.y(t, 0).toDouble()
        val az = s.z(t, 0).toDouble()
        val e1x = s.x(t, 1) - ax
        val e1y = s.y(t, 1) - ay
        val e1z = s.z(t, 1) - az
        val e2x = s.x(t, 2) - ax
        val e2y = s.y(t, 2) - ay
        val e2z = s.z(t, 2) - az
        val px = dy * e2z - dz * e2y
        val py = dz * e2x - dx * e2z
        val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (abs(det) < 1e-12) return Double.POSITIVE_INFINITY
        val inv = 1.0 / det
        val tx = ox - ax
        val ty = oy - ay
        val tz = oz - az
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < 0.0 || u > 1.0) return Double.POSITIVE_INFINITY
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inv
        if (v < 0.0 || u + v > 1.0) return Double.POSITIVE_INFINITY
        val d = (e2x * qx + e2y * qy + e2z * qz) * inv
        return if (d > 1e-6) d else Double.POSITIVE_INFINITY
    }

    /**
     * Ray-parity orientation test like [Soup.inwardFacingTriangles], but the ray leaves along a
     * slightly tilted normal so it never runs exactly through the shared edges of the opposite
     * wall's quads (the station's tunnels and boxes are full of axis-aligned, mirrored quads).
     */
    private fun inwardFacing(s: Soup, parts: List<List<Int>>): List<Int> {
        val bad = ArrayList<Int>()
        for (part in parts) for (t in part) {
            val c = s.centroid(t)
            val n = s.normal(t)
            val a = if (abs(n.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
            val p1 = Vec3().cross(n, a).normalize()
            val p2 = Vec3().cross(n, p1).normalize()
            val dx = n.x + 0.0137 * p1.x + 0.0071 * p2.x
            val dy = n.y + 0.0137 * p1.y + 0.0071 * p2.y
            val dz = n.z + 0.0137 * p1.z + 0.0071 * p2.z
            val ox = c.x + n.x * 1e-4
            val oy = c.y + n.y * 1e-4
            val oz = c.z + n.z * 1e-4
            var hits = 0
            for (u in part) if (u != t && rayT(s, u, ox, oy, oz, dx, dy, dz) < Double.POSITIVE_INFINITY) hits++
            if (hits % 2 != 0) bad.add(t)
        }
        return bad
    }

    /** Distance from p to triangle t (closest point by region tests, Ericson 5.1.5). */
    private fun pointTriangleDistance(p: Vec3, s: Soup, t: Int): Float {
        val a = s.pos(t, 0)
        val b = s.pos(t, 1)
        val c = s.pos(t, 2)
        val ab = Vec3().set(b).sub(a)
        val ac = Vec3().set(c).sub(a)
        val ap = Vec3().set(p).sub(a)
        val d1 = ab.dot(ap)
        val d2 = ac.dot(ap)
        if (d1 <= 0f && d2 <= 0f) return p.distance(a)
        val bp = Vec3().set(p).sub(b)
        val d3 = ab.dot(bp)
        val d4 = ac.dot(bp)
        if (d3 >= 0f && d4 <= d3) return p.distance(b)
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0f && d1 >= 0f && d3 <= 0f) return p.distance(Vec3().set(a).addScaled(ab, d1 / (d1 - d3)))
        val cp = Vec3().set(p).sub(c)
        val d5 = ab.dot(cp)
        val d6 = ac.dot(cp)
        if (d6 >= 0f && d5 <= d6) return p.distance(c)
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0f && d2 >= 0f && d6 <= 0f) return p.distance(Vec3().set(a).addScaled(ac, d2 / (d2 - d6)))
        val va = d3 * d6 - d5 * d4
        if (va <= 0f && d4 - d3 >= 0f && d5 - d6 >= 0f) {
            val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
            return p.distance(Vec3().set(b).addScaled(Vec3().set(c).sub(b), w))
        }
        val denom = 1f / (va + vb + vc)
        val v = vb * denom
        val w = vc * denom
        return p.distance(Vec3().set(a).addScaled(ab, v).addScaled(ac, w))
    }
}
