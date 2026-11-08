package com.starwake.core.content

import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.TestAsserts.assertVec
import com.starwake.core.math.Vec3
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshBuilderTest {
    private fun assertWindingMatchesNormals(s: Soup) {
        for (t in 0 until s.triangles) {
            val w = s.windingCross(t).normalize()
            assertTrue(w.dot(s.normal(t)) > 0.9999f, "triangle $t winding $w vs normal ${s.normal(t)}")
        }
    }

    @Test
    fun degenerateTrianglesAreSkipped() {
        val b = MeshBuilder()
        val ref = Vec3(0f, -1f, 0f)
        b.tri(Vec3(1f, 2f, 3f), Vec3(1f, 2f, 3f), Vec3(1f, 2f, 3f), 0, ref) // a point
        b.tri(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f), Vec3(2f, 2f, 2f), 0, ref) // collinear
        b.tri(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), 0, ref) // repeated vertex
        assertEquals(0, b.triangleCount)
        assertEquals(0, b.build().size)
        b.tri(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f), 0, ref)
        assertEquals(1, b.triangleCount)
        assertEquals(24, b.build().size)
    }

    @Test
    fun normalPointsAwayFromTheReferenceWhicheverWayTheInputIsWound() {
        val a = Vec3(0f, 0f, 0f)
        val bb = Vec3(1f, 0f, 0f)
        val c = Vec3(0f, 0f, 1f) // (b-a)×(c-a) = -Y
        for ((ref, expectedY) in listOf(Vec3(0.2f, -1f, 0.2f) to 1f, Vec3(0.2f, 1f, 0.2f) to -1f)) {
            for (order in listOf(listOf(a, bb, c), listOf(a, c, bb))) {
                val m = MeshBuilder()
                m.tri(order[0], order[1], order[2], 3, ref)
                val s = Soup(m.build())
                assertVec(0f, expectedY, 0f, s.normal(0), 1e-6f, "ref $ref")
                assertWindingMatchesNormals(s)
                assertEquals(3f, s.material(0))
                // the input vertices are all present
                val keys = (0 until 3).map { s.key(0, it) }.toSet()
                assertEquals(order.map { Soup.key(it.x, it.y, it.z) }.toSet(), keys)
            }
        }
    }

    @Test
    fun quadEmitsTwoConsistentTriangles() {
        val m = MeshBuilder()
        m.quad(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 1f, 0f), Vec3(0f, 1f, 0f), 1, Vec3(0.5f, 0.5f, -1f))
        val s = Soup(m.build())
        assertEquals(2, s.triangles)
        for (t in 0 until 2) assertVec(0f, 0f, 1f, s.normal(t), 1e-6f)
        assertWindingMatchesNormals(s)
        assertNear(1f, s.area(0) + s.area(1), 1e-6f)
    }

    @Test
    fun boxHasTwelveOutwardAxisAlignedFaces() {
        val m = MeshBuilder()
        m.box(1f, 2f, 3f, 2f, 4f, 7f, 5)
        val s = Soup(m.build())
        assertEquals(12, s.triangles)
        assertWindingMatchesNormals(s)
        val centre = Vec3(1.5f, 3f, 5f)
        val counts = HashMap<Triple<Float, Float, Float>, Int>()
        var area = 0f
        for (t in 0 until 12) {
            val n = s.normal(t)
            assertTrue(Vec3().set(s.centroid(t)).sub(centre).dot(n) > 0f, "box face $t points inwards")
            counts.merge(Triple(n.x, n.y, n.z), 1, Int::plus)
            area += s.area(t)
        }
        assertEquals(6, counts.size, "six distinct face directions: $counts")
        assertTrue(counts.values.all { it == 2 })
        assertNear(2f * (1f * 2f + 1f * 4f + 2f * 4f), area, 1e-4f)
    }

    @Test
    fun capFansAroundTheRingCentroid() {
        val ring = Array(6) { i ->
            val a = i * Math.PI.toFloat() / 3f
            Vec3(kotlin.math.cos(a), kotlin.math.sin(a), 2f)
        }
        val m = MeshBuilder()
        m.cap(ring, 2, Vec3(0f, 0f, 0f))
        val s = Soup(m.build())
        assertEquals(6, s.triangles)
        for (t in 0 until 6) assertVec(0f, 0f, 1f, s.normal(t), 1e-5f)
        assertWindingMatchesNormals(s)
        assertVec(0f, 0f, 2f, MeshBuilder.centroid(ring), 1e-6f)
    }

    @Test
    fun loftConnectsRingsWithQuads() {
        fun ring(z: Float, r: Float) = Array(5) { i ->
            val a = i * 2f * Math.PI.toFloat() / 5f
            Vec3(kotlin.math.cos(a) * r, kotlin.math.sin(a) * r, z)
        }
        val rings = listOf(ring(0f, 1f), ring(1f, 1.5f), ring(3f, 1f))
        val closed = MeshBuilder()
        closed.loft(rings, closed = true, ref = { Vec3(0f, 0f, 1f) }) { s, e -> s * 10 + e }
        val sc = Soup(closed.build())
        assertEquals(2 * 2 * 5, sc.triangles)
        assertWindingMatchesNormals(sc)
        for (t in 0 until sc.triangles) {
            val c = sc.centroid(t)
            assertTrue(sc.normal(t).dot(c.x, c.y, 0f) > 0f, "loft face $t should face away from the axis")
        }
        // material callback receives (segment, edge)
        val mats = (0 until sc.triangles).map { sc.material(it).toInt() }.toSet()
        assertEquals(setOf(0, 1, 2, 3, 4, 10, 11, 12, 13, 14), mats)
        val open = MeshBuilder()
        open.loft(rings, closed = false, ref = { Vec3(0f, 0f, 1f) }) { _, _ -> 0 }
        assertEquals(2 * 2 * 4, open.triangleCount)
    }

    @Test
    fun bufferGrowsPastItsInitialCapacity() {
        val m = MeshBuilder()
        val n = 3000
        for (i in 0 until n) {
            val x = i.toFloat()
            m.tri(x, 0f, 0f, x + 1f, 0f, 0f, x, 0f, 1f, i % 10, x, -1f, 0f)
        }
        assertEquals(n, m.triangleCount)
        val s = Soup(m.build())
        assertEquals(n, s.triangles)
        for (i in 0 until n) {
            assertEquals((i % 10).toFloat(), s.material(i))
            assertEquals(i.toFloat(), s.x(i, 0))
        }
    }

    @Test
    fun shadeJitterRangeAndDeterminism() {
        fun make(seed: Long, jitter: Float): FloatArray {
            val m = MeshBuilder(seed)
            m.shadeJitter = jitter
            for (i in 0 until 500) m.tri(0f, 0f, i.toFloat(), 1f, 0f, i.toFloat(), 0f, 1f, i.toFloat(), 0, 0f, 0f, -1f)
            return m.build()
        }
        val a = Soup(make(7, 0.1f))
        for (t in 0 until a.triangles) assertTrue(a.shade(t) >= 0.9f && a.shade(t) < 1.04f, "shade ${a.shade(t)}")
        assertContentEquals(make(7, 0.1f), make(7, 0.1f))
        assertTrue(!make(7, 0.1f).contentEquals(make(8, 0.1f)))
        val flat = Soup(make(7, 0f))
        for (t in 0 until flat.triangles) assertEquals(1f, flat.shade(t))
    }

    @Test
    fun mirrorXNegatesXAndKeepsFacesOutward() {
        val m = MeshBuilder()
        m.box(1f, 0f, 0f, 3f, 1f, 2f, 4)
        m.tri(1f, 2f, 0f, 2f, 3f, 0f, 1f, 3f, 1f, 6, 0f, 0f, 0f)
        val src = m.build()
        val mirrored = MeshBuilder.mirrorX(src)
        assertEquals(src.size, mirrored.size)
        val a = Soup(src)
        val b = Soup(mirrored)
        assertWindingMatchesNormals(b)
        val centre = Vec3(-2f, 0.5f, 1f)
        for (t in 0 until a.triangles) {
            assertEquals(a.triangleKey(t, mirrorX = true), b.triangleKey(t))
            val na = a.normal(t)
            assertVec(-na.x, na.y, na.z, b.normal(t), 0f)
            assertEquals(a.material(t), b.material(t))
            assertEquals(a.shade(t), b.shade(t))
            if (t < 12) assertTrue(Vec3().set(b.centroid(t)).sub(centre).dot(b.normal(t)) > 0f, "mirrored box face $t points inwards")
        }
        // mirroring twice restores the original exactly
        assertContentEquals(src, MeshBuilder.mirrorX(mirrored))
    }
}
