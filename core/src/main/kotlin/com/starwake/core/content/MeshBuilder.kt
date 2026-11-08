package com.starwake.core.content

import com.starwake.core.math.Rng
import com.starwake.core.math.Vec3
import kotlin.math.sqrt

/**
 * Builds flat-shaded triangle soups in the lit-vertex format
 * (position xyz, normal xyz, material index, shade) = 8 floats per vertex.
 *
 * Every triangle is oriented automatically: its normal is flipped to point away from the supplied
 * reference point (a point inside the part), so procedural parts never end up inside-out.
 */
class MeshBuilder(seed: Long = 1L) {
    private var data = FloatArray(8 * 3 * 512)
    private var size = 0
    private val rng = Rng(seed)

    /** Random per-face brightness variation that gives low-poly hulls a hand-built panel look. */
    var shadeJitter = 0.07f

    val triangleCount get() = size / 24

    fun tri(a: Vec3, b: Vec3, c: Vec3, mat: Int, ref: Vec3) =
        tri(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, mat, ref.x, ref.y, ref.z)

    fun tri(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        mat: Int, rx: Float, ry: Float, rz: Float,
    ) {
        val e1x = bx - ax
        val e1y = by - ay
        val e1z = bz - az
        val e2x = cx - ax
        val e2y = cy - ay
        val e2z = cz - az
        var nx = e1y * e2z - e1z * e2y
        var ny = e1z * e2x - e1x * e2z
        var nz = e1x * e2y - e1y * e2x
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-7f) return // degenerate
        nx /= len; ny /= len; nz /= len
        val mx = (ax + bx + cx) / 3f - rx
        val my = (ay + by + cy) / 3f - ry
        val mz = (az + bz + cz) / 3f - rz
        val shade = 1f - shadeJitter + rng.nextFloat() * shadeJitter * 1.4f
        ensure(24)
        if (nx * mx + ny * my + nz * mz >= 0f) {
            put(ax, ay, az, nx, ny, nz, mat, shade)
            put(bx, by, bz, nx, ny, nz, mat, shade)
            put(cx, cy, cz, nx, ny, nz, mat, shade)
        } else {
            put(ax, ay, az, -nx, -ny, -nz, mat, shade)
            put(cx, cy, cz, -nx, -ny, -nz, mat, shade)
            put(bx, by, bz, -nx, -ny, -nz, mat, shade)
        }
    }

    fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, mat: Int, ref: Vec3) {
        tri(a, b, c, mat, ref)
        tri(a, c, d, mat, ref)
    }

    /**
     * Connects consecutive rings (all with the same point count) with quads.
     * [closed] = ring is a loop. [material] chooses a material per (segment, edge).
     */
    fun loft(rings: List<Array<Vec3>>, closed: Boolean, ref: (Int) -> Vec3, material: (Int, Int) -> Int) {
        for (s in 0 until rings.size - 1) {
            val r0 = rings[s]
            val r1 = rings[s + 1]
            val n = r0.size
            val edges = if (closed) n else n - 1
            val rp = ref(s)
            for (e in 0 until edges) {
                val e1 = (e + 1) % n
                quad(r0[e], r0[e1], r1[e1], r1[e], material(s, e), rp)
            }
        }
    }

    /** Closes a ring with a fan around its centroid; [inside] is a point inside the part. */
    fun cap(ring: Array<Vec3>, mat: Int, inside: Vec3) {
        val c = centroid(ring)
        for (i in ring.indices) tri(c, ring[i], ring[(i + 1) % ring.size], mat, inside)
    }

    /** Axis aligned box given by min/max corners. */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, mat: Int) {
        val c = Vec3((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
        val p = arrayOf(
            Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x0, y1, z0),
            Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x0, y1, z1),
        )
        quad(p[0], p[1], p[2], p[3], mat, c)
        quad(p[4], p[5], p[6], p[7], mat, c)
        quad(p[0], p[1], p[5], p[4], mat, c)
        quad(p[3], p[2], p[6], p[7], mat, c)
        quad(p[0], p[3], p[7], p[4], mat, c)
        quad(p[1], p[2], p[6], p[5], mat, c)
    }

    fun build(): FloatArray = data.copyOf(size)

    private fun ensure(n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
    }

    private fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, mat: Int, shade: Float) {
        data[size++] = x; data[size++] = y; data[size++] = z
        data[size++] = nx; data[size++] = ny; data[size++] = nz
        data[size++] = mat.toFloat(); data[size++] = shade
    }

    companion object {
        fun centroid(ring: Array<Vec3>): Vec3 {
            val c = Vec3()
            for (p in ring) c.add(p)
            return c.scale(1f / ring.size)
        }

        /** Mirrors a mesh across the X=0 plane (flips winding to keep faces outward). */
        fun mirrorX(src: FloatArray): FloatArray {
            val out = FloatArray(src.size)
            var t = 0
            while (t < src.size) {
                // copy vertices in order 0, 2, 1 to flip winding
                for ((dst, srcV) in intArrayOf(0, 2, 1).withIndex()) {
                    val so = t + srcV * 8
                    val o = t + dst * 8
                    out[o] = -src[so]; out[o + 1] = src[so + 1]; out[o + 2] = src[so + 2]
                    out[o + 3] = -src[so + 3]; out[o + 4] = src[so + 4]; out[o + 5] = src[so + 5]
                    out[o + 6] = src[so + 6]; out[o + 7] = src[so + 7]
                }
                t += 24
            }
            return out
        }
    }
}
