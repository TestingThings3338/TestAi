package com.starwake.core.content

import com.starwake.core.game.ShipSpec
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KiteShipTest {
    private val ship = Soup(KiteShip.build())

    private class Box {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        fun add(p: Vec3) {
            minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
            minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
            minZ = minOf(minZ, p.z); maxZ = maxOf(maxZ, p.z)
        }
        fun contains(p: Vec3) = p.x in minX..maxX && p.y in minY..maxY && p.z in minZ..maxZ
    }

    private fun bounds(filter: (Int) -> Boolean = { true }): Box {
        val b = Box()
        for (t in 0 until ship.triangles) if (filter(t)) for (v in 0 until 3) b.add(ship.pos(t, v))
        return b
    }

    @Test
    fun dataIsWholeTrianglesOfEightFloatVertices() {
        val raw = KiteShip.build()
        assertTrue(raw.isNotEmpty())
        assertEquals(0, raw.size % 24, "size ${raw.size} is not a multiple of 24 floats per triangle")
        assertTrue(ship.triangles in 500..3000, "unexpected triangle count ${ship.triangles}")
    }

    @Test
    fun buildIsDeterministic() {
        assertContentEquals(KiteShip.build(), KiteShip.build())
    }

    @Test
    fun allValuesAreFinite() {
        val raw = KiteShip.build()
        for (i in raw.indices) assertTrue(raw[i].isFinite(), "float $i is ${raw[i]}")
    }

    @Test
    fun normalsAreUnitLengthFlatAndMatchTheWinding() {
        for (t in 0 until ship.triangles) {
            val n = ship.normal(t)
            assertTrue(abs(n.length() - 1f) < 1e-4f, "triangle $t normal $n not unit")
            for (v in 1..2) {
                val nv = ship.normal(t, v)
                assertTrue(nv.x == n.x && nv.y == n.y && nv.z == n.z, "triangle $t is not flat shaded")
            }
            // counter-clockwise front faces: (b-a)×(c-a) points along the stored normal
            val w = ship.windingCross(t).normalize()
            assertTrue(w.dot(n) > 0.999f, "triangle $t winding $w disagrees with normal $n")
        }
    }

    @Test
    fun materialsAreIntegralPaletteIndices() {
        val used = HashSet<Int>()
        for (t in 0 until ship.triangles) for (v in 0 until 3) {
            val m = ship.material(t, v)
            assertEquals(m, m.toInt().toFloat(), "triangle $t material $m is not integral")
            assertTrue(m.toInt() in 0..9, "triangle $t material $m outside 0..9")
            used.add(m.toInt())
        }
        assertEquals((0..9).toSet(), used, "every palette slot should be used by the Kite")
    }

    @Test
    fun shadeJitterStaysNearOne() {
        for (t in 0 until ship.triangles) {
            val s = ship.shade(t)
            assertTrue(s in 0.9f..1.05f, "triangle $t shade $s")
            assertEquals(s, ship.shade(t, 1))
            assertEquals(s, ship.shade(t, 2))
        }
    }

    @Test
    fun boundingBoxMatchesTheDesignDimensions() {
        val b = bounds()
        val length = b.maxZ - b.minZ
        val span = b.maxX - b.minX
        assertTrue(length in 14.5f..16f, "length $length (z ${b.minZ}..${b.maxZ})")
        assertTrue(span in 12f..14f, "span $span (x ${b.minX}..${b.maxX})")
        assertTrue(b.maxY - b.minY in 3f..5f, "height ${b.maxY - b.minY}")
        assertTrue(abs(b.minX + b.maxX) < 1e-4f, "span must be centred on x = 0")
        // nose along -Z: the front is much further from the origin than the tail
        assertTrue(b.minZ < -7.5f && b.maxZ < 7.6f, "nose should be at -Z (z ${b.minZ}..${b.maxZ})")
        assertTrue(abs(length - KiteShip.LENGTH) < 0.5f, "LENGTH ${KiteShip.LENGTH} vs modelled $length")
    }

    @Test
    fun theNoseTipIsOnTheCentreLine() {
        var tip = Vec3(0f, 0f, Float.MAX_VALUE)
        for (t in 0 until ship.triangles) for (v in 0 until 3) {
            val p = ship.pos(t, v)
            if (p.z < tip.z) tip = p
        }
        assertTrue(abs(tip.x) < 0.1f && abs(tip.y) < 0.5f, "nose tip at $tip")
    }

    @Test
    fun collisionRadiusConstantsAgree() {
        assertEquals(KiteShip.COLLISION_RADIUS, ShipSpec.KITE.collisionRadius)
    }

    @Test
    fun everyFacePointsOutOfItsOwnPart() {
        val parts = ship.connectedParts()
        assertTrue(parts.size >= 10, "expected the hull to be built from many parts, got ${parts.size}")
        val bad = ship.inwardFacingTriangles(parts)
        val okPct = 100.0 * (ship.triangles - bad.size) / ship.triangles
        println("KiteShip: ${ship.triangles} triangles in ${parts.size} parts; outward by ray parity: %.2f%%".format(okPct))
        // The smallest part (a box) has 12 triangles = 1.4 % of the hull, so 99.5 % catches any
        // inside-out part while tolerating a couple of ray/edge grazing artefacts.
        assertTrue(okPct >= 99.5, "only %.2f%% of faces point out of their part; first bad: ${bad.take(10)}".format(okPct))
    }

    @Test
    fun mostFacesPointAwayFromTheShipAxis() {
        // Weak global check (the suggested z-axis heuristic). Off-axis parts (wings, fins, nacelles)
        // legitimately have faces looking back at the fuselage, so the real number is only ~66 %;
        // a globally inverted hull would score ~34 %.
        var ok = 0
        for (t in 0 until ship.triangles) {
            val c = ship.centroid(t)
            val n = ship.normal(t)
            if (n.x * c.x + n.y * c.y > 0f) ok++
        }
        val pct = 100.0 * ok / ship.triangles
        println("KiteShip: faces pointing away from the z axis: %.2f%%".format(pct))
        assertTrue(pct > 55.0, "only %.2f%% of faces point away from the z axis".format(pct))
    }

    @Test
    fun hullIsMirrorSymmetric() {
        var left = 0
        var right = 0
        for (t in 0 until ship.triangles) {
            val c = ship.centroid(t)
            if (c.x > 1e-3f) right++ else if (c.x < -1e-3f) left++
        }
        assertEquals(right, left, "mirrored triangle counts differ")
        assertTrue(right > ship.triangles / 3)
        // every vertex has a mirror image
        val keys = HashSet<Long>()
        for (t in 0 until ship.triangles) for (v in 0 until 3) keys.add(ship.key(t, v))
        for (t in 0 until ship.triangles) for (v in 0 until 3) {
            assertTrue(ship.key(t, v, mirrorX = true) in keys, "vertex ${ship.pos(t, v)} has no mirror image")
        }
        // per-material surface area matches left/right (nav lights swap red/green). Lofted quads use
        // the same diagonal on both sides, so allow 1 % for non-planar quads.
        val areaL = DoubleArray(10)
        val areaR = DoubleArray(10)
        for (t in 0 until ship.triangles) {
            val c = ship.centroid(t)
            val m = ship.material(t).toInt()
            if (c.x > 1e-3f) areaR[m] += ship.area(t).toDouble()
            else if (c.x < -1e-3f) {
                val mm = when (m) {
                    KiteShip.GREEN -> KiteShip.RED
                    KiteShip.RED -> KiteShip.GREEN
                    else -> m
                }
                areaL[mm] += ship.area(t).toDouble()
            }
        }
        for (m in 0..9) {
            assertTrue(abs(areaL[m] - areaR[m]) <= 0.01 * maxOf(areaR[m], 1e-3), "material $m area left ${areaL[m]} vs right ${areaR[m]}")
        }
    }

    @Test
    fun navigationLightsFollowAviationConvention() {
        // ship faces -Z with +Y up, so starboard (right) is +X: green right, red left
        val green = bounds { ship.material(it).toInt() == KiteShip.GREEN }
        val red = bounds { ship.material(it).toInt() == KiteShip.RED }
        assertTrue(green.minX > 0f, "green light must be on the right wing")
        assertTrue(red.maxX < 0f, "red light must be on the left wing")
        assertTrue(green.contains(KiteShip.navGreen), "navGreen ${KiteShip.navGreen} outside the green light")
        assertTrue(red.contains(KiteShip.navRed), "navRed ${KiteShip.navRed} outside the red light")
        val white = bounds { ship.material(it).toInt() == KiteShip.WHITE }
        for (s in KiteShip.strobes) assertTrue(white.contains(s), "strobe $s outside the white strobe housings")
    }

    @Test
    fun nozzlesSitBehindGlowingEngineCores() {
        for (nz in KiteShip.nozzles) {
            var found = false
            for (t in 0 until ship.triangles) {
                if (ship.material(t).toInt() != KiteShip.ENGINE) continue
                val c = ship.centroid(t)
                val r = Soup.len(c.x - nz.x, c.y - nz.y, 0f)
                if (r < KiteShip.NOZZLE_RADIUS && c.z < nz.z && nz.z - c.z < 0.6f && ship.normal(t).z > 0.9f) found = true
            }
            assertTrue(found, "no rear-facing ENGINE face just in front of nozzle $nz")
            // nothing of the hull sticks out behind the plume origin inside the nozzle radius
            for (t in 0 until ship.triangles) for (v in 0 until 3) {
                val p = ship.pos(t, v)
                if (Soup.len(p.x - nz.x, p.y - nz.y, 0f) < KiteShip.NOZZLE_RADIUS) {
                    assertTrue(p.z <= nz.z, "vertex $p blocks the exhaust plume of nozzle $nz")
                }
            }
        }
    }

    @Test
    fun noTriangleIsDuplicated() {
        val seen = HashMap<List<Long>, Int>()
        val dups = ArrayList<String>()
        for (t in 0 until ship.triangles) {
            val k = ship.triangleKey(t)
            val first = seen.putIfAbsent(k, t)
            if (first != null) dups.add("$t duplicates $first at ${ship.centroid(t)}")
        }
        assertTrue(dups.isEmpty(), "${dups.size} duplicated triangles: $dups")
    }
}
