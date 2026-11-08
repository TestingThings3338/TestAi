package com.starwake.core.content

import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProceduralTest {
    /** The rock variants Content builds (seeds it * 17 + 5), plus a few others. */
    private val rockSeeds = listOf(5, 22, 39, 0, 1, 1000)

    @Test
    fun icosphereHasTheExpectedTopology() {
        for ((sub, v, f) in listOf(Triple(0, 12, 20), Triple(1, 42, 80), Triple(2, 162, 320), Triple(3, 642, 1280))) {
            val (verts, faces) = AsteroidMesh.icosphere(sub)
            assertEquals(v, verts.size, "vertices at subdivision $sub")
            assertEquals(f, faces.size, "faces at subdivision $sub")
            for (p in verts) assertTrue(abs(p.length() - 1f) < 1e-5f, "vertex $p not on the unit sphere")
            // closed 2-manifold: every edge used exactly twice, once in each direction
            val directed = HashSet<Long>()
            for (face in faces) for (i in 0 until 3) {
                val a = face[i]
                val b = face[(i + 1) % 3]
                assertTrue(directed.add((a.toLong() shl 32) or b.toLong()), "directed edge $a->$b used twice (inconsistent winding)")
            }
            for (e in directed) {
                val a = (e ushr 32).toInt()
                val b = (e and 0xFFFFFFFFL).toInt()
                assertTrue(((b.toLong() shl 32) or a.toLong()) in directed, "edge $a-$b is a border")
            }
            // counter-clockwise seen from outside
            for (face in faces) {
                val a = verts[face[0]]
                val n = Vec3().cross(Vec3().set(verts[face[1]]).sub(a), Vec3().set(verts[face[2]]).sub(a))
                assertTrue(n.dot(a) > 0f, "icosphere face ${face.toList()} is wound inwards")
            }
        }
    }

    @Test
    fun asteroidMeshHas320OutwardTrianglesAtTwoSubdivisions() {
        for (seed in rockSeeds) {
            val s = Soup(AsteroidMesh.build(seed, 2))
            assertEquals(0, s.d.size % 24)
            assertEquals(320, s.triangles, "seed $seed")
            for (t in 0 until s.triangles) {
                val n = s.normal(t)
                assertTrue(abs(n.length() - 1f) < 1e-4f, "seed $seed triangle $t normal not unit")
                assertTrue(n.dot(s.centroid(t)) > 0f, "seed $seed triangle $t faces the origin")
                assertTrue(s.windingCross(t).normalize().dot(n) > 0.999f, "seed $seed triangle $t winding")
                val m = s.material(t).toInt()
                assertTrue(m in AsteroidMesh.ROCK..AsteroidMesh.MINERAL, "material $m")
            }
        }
        assertEquals(80, Soup(AsteroidMesh.build(5, 1)).triangles)
        assertEquals(20, Soup(AsteroidMesh.build(5, 0)).triangles)
    }

    @Test
    fun asteroidMeshIsDeterministicAndVariesWithSeed() {
        assertContentEquals(AsteroidMesh.build(22), AsteroidMesh.build(22))
        assertTrue(!AsteroidMesh.build(5).contentEquals(AsteroidMesh.build(22)))
    }

    @Test
    fun asteroidMeshFitsAroundTheUnitCollisionSphere() {
        // AsteroidField uses the instance scale as the collision radius, so the mesh should stay
        // close to radius 1. Report the actual envelope.
        for (seed in rockSeeds) {
            val s = Soup(AsteroidMesh.build(seed, 2))
            var min = Float.MAX_VALUE
            var max = 0f
            for (t in 0 until s.triangles) for (v in 0 until 3) {
                val r = s.pos(t, v).length()
                min = minOf(min, r); max = maxOf(max, r)
            }
            println("AsteroidMesh seed $seed: vertex radius %.3f .. %.3f".format(min, max))
            assertTrue(min > 0.4f && max < 1.4f, "seed $seed radius envelope $min..$max")
        }
    }

    @Test
    fun starFieldLayoutAndRanges() {
        val n = 6500
        val stars = StarField.build(n)
        assertEquals(n * StarField.FLOATS, stars.size)
        assertContentEquals(stars, StarField.build(n))
        assertTrue(!stars.contentEquals(StarField.build(n, seed = 43L)))
        var nearPlane = 0.0
        var minC = Float.MAX_VALUE
        var maxC = 0f
        var minSize = Float.MAX_VALUE
        var maxSize = 0f
        val d = Vec3()
        for (i in 0 until n) {
            val o = i * StarField.FLOATS
            d.set(stars[o], stars[o + 1], stars[o + 2])
            assertTrue(abs(d.length() - 1f) < 1e-4f, "star $i direction $d not unit")
            for (c in 3..5) {
                assertTrue(stars[o + c].isFinite() && stars[o + c] > 0f, "star $i colour ${stars[o + c]}")
                minC = minOf(minC, stars[o + c]); maxC = maxOf(maxC, stars[o + c])
            }
            val size = stars[o + 6]
            assertTrue(size >= 1.1f && size < 4.3f + 1e-4f, "star $i size $size")
            minSize = minOf(minSize, size); maxSize = maxOf(maxSize, size)
            nearPlane += abs(d.dot(StarField.galacticNormal))
        }
        println("StarField: colour %.3f..%.3f size %.2f..%.2f".format(minC, maxC, minSize, maxSize))
        assertTrue(minC >= 0.22f * 0.45f - 1e-4f && maxC <= 1.82f + 1e-4f, "colour range $minC..$maxC")
        // uniform directions average |cos| = 0.5; the galactic band pulls it down noticeably
        val meanCos = nearPlane / n
        assertTrue(meanCos < 0.42, "stars should concentrate in the galactic plane (mean |cos| = $meanCos)")
        assertTrue(abs(StarField.galacticNormal.length() - 1f) < 1e-6f)
    }

    @Test
    fun dustFieldQuadsAndIndices() {
        val n = 700
        val (v, idx) = DustField.build(n)
        assertEquals(n * 4 * 5, v.size)
        assertEquals(n * 6, idx.size)
        for (i in 0 until n) {
            val corners = HashSet<Pair<Float, Float>>()
            for (c in 0 until 4) {
                val o = (i * 4 + c) * 5
                for (k in 0 until 3) {
                    assertTrue(v[o + k] >= 0f && v[o + k] < DustField.BOX, "particle $i coordinate ${v[o + k]}")
                    assertEquals(v[i * 20 + k], v[o + k], "all corners of particle $i share its seed position")
                }
                corners.add(v[o + 3] to v[o + 4])
            }
            assertEquals(setOf(0f to -1f, 0f to 1f, 1f to -1f, 1f to 1f), corners)
            // both triangles reference only this particle's vertices and have the same winding
            var sign = 0f
            for (tri in 0 until 2) {
                val p = IntArray(3) { idx[i * 6 + tri * 3 + it].toInt() and 0xFFFF }
                for (k in p) assertTrue(k in i * 4 until i * 4 + 4, "index $k outside particle $i")
                val ax = v[p[0] * 5 + 3]; val ay = v[p[0] * 5 + 4]
                val cross = (v[p[1] * 5 + 3] - ax) * (v[p[2] * 5 + 4] - ay) - (v[p[1] * 5 + 4] - ay) * (v[p[2] * 5 + 3] - ax)
                assertTrue(cross != 0f)
                if (sign == 0f) sign = cross else assertTrue(sign * cross > 0f, "particle $i triangles wound differently")
            }
        }
        assertContentEquals(v, DustField.build(n).first)
    }

    @Test
    fun uvSphereIsAUnitSphereWithOutwardTriangles() {
        val seg = 72
        val rings = 44
        val (v, idx) = Primitives.uvSphere(seg, rings)
        val vertexCount = (seg + 1) * (rings + 1)
        assertEquals(vertexCount * 3, v.size)
        assertEquals(seg * rings * 6, idx.size)
        assertTrue(vertexCount <= 65536, "indices must fit 16 bits")
        for (i in 0 until vertexCount) {
            val l = sqrt(v[i * 3] * v[i * 3] + v[i * 3 + 1] * v[i * 3 + 1] + v[i * 3 + 2] * v[i * 3 + 2])
            assertTrue(abs(l - 1f) < 1e-5f, "vertex $i radius $l")
        }
        var degenerate = 0
        for (t in 0 until idx.size / 3) {
            val p = IntArray(3) { idx[t * 3 + it].toInt() and 0xFFFF }
            for (k in p) assertTrue(k < vertexCount, "index $k out of range")
            val a = Vec3(v[p[0] * 3], v[p[0] * 3 + 1], v[p[0] * 3 + 2])
            val b = Vec3(v[p[1] * 3], v[p[1] * 3 + 1], v[p[1] * 3 + 2]).sub(a)
            val c = Vec3(v[p[2] * 3], v[p[2] * 3 + 1], v[p[2] * 3 + 2]).sub(a)
            val n = Vec3().cross(b, c)
            if (n.length() < 1e-7f) { degenerate++; continue } // pole triangles
            val centroid = Vec3().set(a).scale(3f).add(b).add(c).scale(1f / 3f)
            assertTrue(n.dot(centroid) > 0f, "uv sphere triangle $t wound inwards")
        }
        assertTrue(degenerate <= 2 * seg, "only pole triangles may be degenerate ($degenerate)")
    }

    @Test
    fun annulusSpansInnerToOuterRadiusInTheXzPlane() {
        val seg = 180
        val (v, idx) = Primitives.annulus(seg, 1.4f, 2.34f)
        assertEquals((seg + 1) * 2 * 3, v.size)
        assertEquals(seg * 6, idx.size)
        for (i in 0 until (seg + 1) * 2) {
            assertEquals(0f, v[i * 3 + 1])
            val r = sqrt(v[i * 3] * v[i * 3] + v[i * 3 + 2] * v[i * 3 + 2])
            val expected = if (i % 2 == 0) 1.4f else 2.34f
            assertTrue(abs(r - expected) < 1e-5f, "vertex $i radius $r")
        }
        var sign = 0f
        for (t in 0 until idx.size / 3) {
            val p = IntArray(3) { idx[t * 3 + it].toInt() }
            for (k in p) assertTrue(k in 0 until (seg + 1) * 2)
            // y component of the winding normal
            val e1x = v[p[1] * 3] - v[p[0] * 3]; val e1z = v[p[1] * 3 + 2] - v[p[0] * 3 + 2]
            val e2x = v[p[2] * 3] - v[p[0] * 3]; val e2z = v[p[2] * 3 + 2] - v[p[0] * 3 + 2]
            val ny = e1z * e2x - e1x * e2z
            assertTrue(ny != 0f)
            if (sign == 0f) sign = ny else assertTrue(sign * ny > 0f, "annulus triangle $t wound differently")
        }
    }

    @Test
    fun fullscreenTriangleCoversNdc() {
        val t = Primitives.fullscreenTriangle
        assertEquals(6, t.size)
        fun inside(px: Float, py: Float): Boolean {
            fun edge(ax: Float, ay: Float, bx: Float, by: Float) = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
            val e0 = edge(t[0], t[1], t[2], t[3])
            val e1 = edge(t[2], t[3], t[4], t[5])
            val e2 = edge(t[4], t[5], t[0], t[1])
            return (e0 >= 0 && e1 >= 0 && e2 >= 0) || (e0 <= 0 && e1 <= 0 && e2 <= 0)
        }
        for ((x, y) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f, 0f to 0f)) assertTrue(inside(x, y), "($x, $y) not covered")
    }
}
