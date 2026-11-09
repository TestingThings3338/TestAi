package com.starwake.core.game

import com.starwake.core.content.Soup
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Exact geometric queries on a lit-vertex triangle soup (station-local metres) for the collision
 * tests: distance to the nearest triangle through a uniform grid, and inside / outside by ray parity
 * per closed part.
 */
class MeshField(private val mesh: FloatArray, private val cell: Double = 24.0) {
    val triangles = mesh.size / 24
    private val tri = DoubleArray(triangles * 9) { mesh[(it / 9) * 24 + ((it % 9) / 3) * 8 + it % 3].toDouble() }
    private val grid = HashMap<Long, IntArray>()
    private val stamp = IntArray(triangles)
    private var query = 0

    /** Connected parts (computed on a 1/10 copy: Soup's vertex keys only span ±104 m) with their bounds. */
    private val parts: List<IntArray>
    private val partBox: List<DoubleArray>

    init {
        val lists = HashMap<Long, ArrayList<Int>>()
        for (t in 0 until triangles) {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var z0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var z1 = -Double.MAX_VALUE
            for (v in 0 until 3) {
                val x = tri[t * 9 + v * 3]; val y = tri[t * 9 + v * 3 + 1]; val z = tri[t * 9 + v * 3 + 2]
                x0 = minOf(x0, x); y0 = minOf(y0, y); z0 = minOf(z0, z)
                x1 = maxOf(x1, x); y1 = maxOf(y1, y); z1 = maxOf(z1, z)
            }
            for (i in c(x0)..c(x1)) for (j in c(y0)..c(y1)) for (k in c(z0)..c(z1)) {
                lists.getOrPut(key(i, j, k)) { ArrayList() }.add(t)
            }
        }
        for ((k, v) in lists) grid[k] = v.toIntArray()
        val scaled = mesh.copyOf()
        var i = 0
        while (i < scaled.size) {
            scaled[i] *= 0.1f; scaled[i + 1] *= 0.1f; scaled[i + 2] *= 0.1f
            i += 8
        }
        parts = Soup(scaled).connectedParts().map { it.toIntArray() }
        partBox = parts.map { p ->
            val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
            for (t in p) for (v in 0 until 3) for (a in 0 until 3) {
                val x = tri[t * 9 + v * 3 + a]
                b[a] = minOf(b[a], x); b[a + 3] = maxOf(b[a + 3], x)
            }
            b
        }
    }

    private fun c(x: Double) = floor(x / cell).toInt()
    private fun key(i: Int, j: Int, k: Int): Long = ((i + 4096).toLong() shl 26) or ((j + 4096).toLong() shl 13) or (k + 4096).toLong()

    /** Distance from p to the nearest triangle, or [maxR] when none is closer. */
    fun distance(px: Double, py: Double, pz: Double, maxR: Double): Double {
        query++
        var best = maxR
        for (i in c(px - maxR)..c(px + maxR)) for (j in c(py - maxR)..c(py + maxR)) for (k in c(pz - maxR)..c(pz + maxR)) {
            val list = grid[key(i, j, k)] ?: continue
            for (t in list) {
                if (stamp[t] == query) continue
                stamp[t] = query
                val d = triangleDistance(t, px, py, pz)
                if (d < best) best = d
            }
        }
        return best
    }

    /** Is p inside any closed part (odd number of crossings along a fixed, skewed ray)? */
    fun inside(px: Double, py: Double, pz: Double): Boolean {
        val dx = 0.5713
        val dy = 0.6219
        val dz = 0.5357
        for (n in parts.indices) {
            val b = partBox[n]
            if (px < b[0] || py < b[1] || pz < b[2] || px > b[3] || py > b[4] || pz > b[5]) continue
            var hits = 0
            for (t in parts[n]) if (rayHits(t, px, py, pz, dx, dy, dz)) hits++
            if (hits % 2 == 1) return true
        }
        return false
    }

    /** Vertex [v] (0..2), axis [a] (0..2) of triangle [t]. */
    fun v(t: Int, v: Int, a: Int) = tri[t * 9 + v * 3 + a]

    private fun rayHits(t: Int, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Boolean {
        val o = t * 9
        val ax = tri[o]; val ay = tri[o + 1]; val az = tri[o + 2]
        val e1x = tri[o + 3] - ax; val e1y = tri[o + 4] - ay; val e1z = tri[o + 5] - az
        val e2x = tri[o + 6] - ax; val e2y = tri[o + 7] - ay; val e2z = tri[o + 8] - az
        val px = dy * e2z - dz * e2y
        val py = dz * e2x - dx * e2z
        val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (kotlin.math.abs(det) < 1e-12) return false
        val inv = 1.0 / det
        val tx = ox - ax; val ty = oy - ay; val tz = oz - az
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < 0.0 || u > 1.0) return false
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val w = (dx * qx + dy * qy + dz * qz) * inv
        if (w < 0.0 || u + w > 1.0) return false
        return (e2x * qx + e2y * qy + e2z * qz) * inv > 1e-9
    }

    /** Exact point-triangle distance (closest point by Voronoi region, after Ericson). */
    private fun triangleDistance(t: Int, px: Double, py: Double, pz: Double): Double {
        val o = t * 9
        val ax = tri[o]; val ay = tri[o + 1]; val az = tri[o + 2]
        val bx = tri[o + 3]; val by = tri[o + 4]; val bz = tri[o + 5]
        val cx = tri[o + 6]; val cy = tri[o + 7]; val cz = tri[o + 8]
        val abx = bx - ax; val aby = by - ay; val abz = bz - az
        val acx = cx - ax; val acy = cy - ay; val acz = cz - az
        val apx = px - ax; val apy = py - ay; val apz = pz - az
        val d1 = abx * apx + aby * apy + abz * apz
        val d2 = acx * apx + acy * apy + acz * apz
        if (d1 <= 0 && d2 <= 0) return len(apx, apy, apz)
        val bpx = px - bx; val bpy = py - by; val bpz = pz - bz
        val d3 = abx * bpx + aby * bpy + abz * bpz
        val d4 = acx * bpx + acy * bpy + acz * bpz
        if (d3 >= 0 && d4 <= d3) return len(bpx, bpy, bpz)
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            val v = d1 / (d1 - d3)
            return len(apx - abx * v, apy - aby * v, apz - abz * v)
        }
        val cpx = px - cx; val cpy = py - cy; val cpz = pz - cz
        val d5 = abx * cpx + aby * cpy + abz * cpz
        val d6 = acx * cpx + acy * cpy + acz * cpz
        if (d6 >= 0 && d5 <= d6) return len(cpx, cpy, cpz)
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            val w = d2 / (d2 - d6)
            return len(apx - acx * w, apy - acy * w, apz - acz * w)
        }
        val va = d3 * d6 - d5 * d4
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
            return len(bpx - (cx - bx) * w, bpy - (cy - by) * w, bpz - (cz - bz) * w)
        }
        val denom = 1.0 / (va + vb + vc)
        val v = vb * denom
        val w = vc * denom
        return len(apx - abx * v - acx * w, apy - aby * v - acy * w, apz - abz * v - acz * w)
    }

    private fun len(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
}
