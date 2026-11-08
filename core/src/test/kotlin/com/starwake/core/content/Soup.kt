package com.starwake.core.content

import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Read-only view of a lit-vertex triangle soup (8 floats per vertex: xyz, normal xyz, material,
 * shade; 3 vertices per triangle) with the geometric queries the content tests need.
 */
class Soup(val d: FloatArray) {
    val triangles = d.size / 24

    fun x(t: Int, v: Int) = d[t * 24 + v * 8]
    fun y(t: Int, v: Int) = d[t * 24 + v * 8 + 1]
    fun z(t: Int, v: Int) = d[t * 24 + v * 8 + 2]
    fun pos(t: Int, v: Int) = Vec3(x(t, v), y(t, v), z(t, v))
    fun normal(t: Int, v: Int = 0) = Vec3(d[t * 24 + v * 8 + 3], d[t * 24 + v * 8 + 4], d[t * 24 + v * 8 + 5])
    fun material(t: Int, v: Int = 0) = d[t * 24 + v * 8 + 6]
    fun shade(t: Int, v: Int = 0) = d[t * 24 + v * 8 + 7]

    fun centroid(t: Int) = Vec3(
        (x(t, 0) + x(t, 1) + x(t, 2)) / 3f,
        (y(t, 0) + y(t, 1) + y(t, 2)) / 3f,
        (z(t, 0) + z(t, 1) + z(t, 2)) / 3f,
    )

    /** Unnormalised (b - a) × (c - a): the counter-clockwise front-face normal times 2·area. */
    fun windingCross(t: Int): Vec3 {
        val a = pos(t, 0)
        val e1 = pos(t, 1).sub(a)
        val e2 = pos(t, 2).sub(a)
        return Vec3().cross(e1, e2)
    }

    fun area(t: Int) = windingCross(t).length() * 0.5f

    /** Quantised vertex position key, so exactly shared vertices compare equal. */
    fun key(t: Int, v: Int, mirrorX: Boolean = false): Long = key(if (mirrorX) -x(t, v) else x(t, v), y(t, v), z(t, v))

    /** Order independent key of the triangle's three vertex positions. */
    fun triangleKey(t: Int, mirrorX: Boolean = false): List<Long> =
        listOf(key(t, 0, mirrorX), key(t, 1, mirrorX), key(t, 2, mirrorX)).sorted()

    /** Groups triangles that share vertex positions (= the separately modelled parts). */
    fun connectedParts(): List<List<Int>> {
        val parent = IntArray(triangles) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) {
                val n = parent[j]; parent[j] = r; j = n
            }
            return r
        }
        val owner = HashMap<Long, Int>()
        for (t in 0 until triangles) for (v in 0 until 3) {
            val k = key(t, v)
            val o = owner[k]
            if (o == null) owner[k] = t else {
                val a = find(o)
                val b = find(t)
                if (a != b) parent[a] = b
            }
        }
        return (0 until triangles).groupBy { find(it) }.values.toList()
    }

    /** Möller–Trumbore: does the ray (o, dir) hit triangle t at a distance > 1e-6? (doubles) */
    fun rayHits(t: Int, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Boolean {
        val ax = x(t, 0).toDouble()
        val ay = y(t, 0).toDouble()
        val az = z(t, 0).toDouble()
        val e1x = x(t, 1) - ax
        val e1y = y(t, 1) - ay
        val e1z = z(t, 1) - az
        val e2x = x(t, 2) - ax
        val e2y = y(t, 2) - ay
        val e2z = z(t, 2) - az
        val px = dy * e2z - dz * e2y
        val py = dz * e2x - dx * e2z
        val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (abs(det) < 1e-12) return false
        val inv = 1.0 / det
        val tx = ox - ax
        val ty = oy - ay
        val tz = oz - az
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < 0.0 || u > 1.0) return false
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inv
        if (v < 0.0 || u + v > 1.0) return false
        return (e2x * qx + e2y * qy + e2z * qz) * inv > 1e-6
    }

    /**
     * Ray-parity orientation test: a ray leaving a face along its normal crosses its own part's
     * surface an even number of times iff the face points out of that part. Returns the triangles
     * whose normal points into their part.
     */
    fun inwardFacingTriangles(parts: List<List<Int>>): List<Int> {
        val bad = ArrayList<Int>()
        for (part in parts) for (t in part) {
            val c = centroid(t)
            val n = normal(t)
            val ox = c.x + n.x * 1e-4
            val oy = c.y + n.y * 1e-4
            val oz = c.z + n.z * 1e-4
            var hits = 0
            for (u in part) if (u != t && rayHits(u, ox, oy, oz, n.x.toDouble(), n.y.toDouble(), n.z.toDouble())) hits++
            if (hits % 2 != 0) bad.add(t)
        }
        return bad
    }

    companion object {
        /** Exact packing of 0.1 mm quantised coordinates (21 bits each, valid within ±104 m). */
        fun key(x: Float, y: Float, z: Float): Long {
            fun q(v: Float): Long {
                val r = (v * 1e4f).roundToLong()
                require(r > -(1L shl 20) && r < (1L shl 20)) { "coordinate $v out of key range" }
                return r + (1L shl 20)
            }
            return (q(x) shl 42) or (q(y) shl 21) or q(z)
        }

        fun len(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
    }
}
