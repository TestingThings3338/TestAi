package com.starwake.core.content

import com.starwake.core.math.Noise
import com.starwake.core.math.Rng
import com.starwake.core.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Procedural asteroid meshes: displaced, stretched icospheres with faceted shading. */
object AsteroidMesh {
    const val ROCK = 0
    const val DARK_ROCK = 1
    const val MINERAL = 2

    fun build(seed: Int, subdivisions: Int = 2): FloatArray {
        val (verts, faces) = icosphere(subdivisions)
        val rng = Rng(seed.toLong() * 7919L + 13L)
        val sx = rng.range(0.75f, 1.3f)
        val sy = rng.range(0.65f, 1.1f)
        val sz = rng.range(0.8f, 1.35f)
        val norm = 1f / maxOf(sx, sy, sz)
        // craters: random directions with radius
        val craterDirs = Array(6) { rng.onSphere(Vec3()) }
        val craterSize = FloatArray(6) { rng.range(0.25f, 0.55f) }
        val displaced = Array(verts.size) { i ->
            val p = verts[i]
            var r = 1f + (Noise.fbm(p.x * 1.7f + seed, p.y * 1.7f, p.z * 1.7f, 4, seed) - 0.5f) * 0.75f
            for (c in craterDirs.indices) {
                val d = 1f - p.dot(craterDirs[c])
                val cs = craterSize[c] * 0.3f
                if (d < cs) {
                    val t = d / cs
                    r -= (1f - t * t) * 0.12f
                }
            }
            Vec3(p.x * r * sx * norm, p.y * r * sy * norm, p.z * r * sz * norm)
        }
        val b = MeshBuilder(seed.toLong())
        b.shadeJitter = 0.12f
        val origin = Vec3()
        for (f in faces) {
            val a = displaced[f[0]]
            val c = displaced[f[2]]
            val bb = displaced[f[1]]
            val cx = (a.x + bb.x + c.x) / 3f
            val cy = (a.y + bb.y + c.y) / 3f
            val cz = (a.z + bb.z + c.z) / 3f
            val n = Noise.fbm(cx * 3.1f, cy * 3.1f, cz * 3.1f, 3, seed + 99)
            val mat = when {
                n > 0.66f -> MINERAL
                n < 0.40f -> DARK_ROCK
                else -> ROCK
            }
            b.tri(a, bb, c, mat, origin)
        }
        return b.build()
    }

    /** Median distance of the mesh's vertices from its origin (8 floats per vertex). */
    fun medianRadius(mesh: FloatArray): Float {
        val n = mesh.size / 8
        val r = FloatArray(n) { val o = it * 8; sqrt(mesh[o] * mesh[o] + mesh[o + 1] * mesh[o + 1] + mesh[o + 2] * mesh[o + 2]) }
        r.sort()
        return r[n / 2]
    }

    /** Unit icosphere: returns vertices and triangle index triples. */
    fun icosphere(subdivisions: Int): Pair<List<Vec3>, List<IntArray>> {
        val t = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
        val verts = arrayListOf(
            Vec3(-1f, t, 0f), Vec3(1f, t, 0f), Vec3(-1f, -t, 0f), Vec3(1f, -t, 0f),
            Vec3(0f, -1f, t), Vec3(0f, 1f, t), Vec3(0f, -1f, -t), Vec3(0f, 1f, -t),
            Vec3(t, 0f, -1f), Vec3(t, 0f, 1f), Vec3(-t, 0f, -1f), Vec3(-t, 0f, 1f),
        )
        verts.forEach { it.normalize() }
        var faces = listOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
            intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
            intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
        )
        repeat(subdivisions) {
            val cache = HashMap<Long, Int>()
            fun mid(a: Int, b: Int): Int {
                val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
                return cache.getOrPut(key) {
                    verts.add(Vec3().set(verts[a]).add(verts[b]).normalize())
                    verts.size - 1
                }
            }
            val next = ArrayList<IntArray>(faces.size * 4)
            for (f in faces) {
                val ab = mid(f[0], f[1])
                val bc = mid(f[1], f[2])
                val ca = mid(f[2], f[0])
                next.add(intArrayOf(f[0], ab, ca))
                next.add(intArrayOf(f[1], bc, ab))
                next.add(intArrayOf(f[2], ca, bc))
                next.add(intArrayOf(ab, bc, ca))
            }
            faces = next
        }
        return verts to faces
    }
}

/** Background star points: direction xyz, colour rgb (display space), size. */
object StarField {
    const val FLOATS = 7
    val galacticNormal: Vec3 = Vec3(0.28f, 0.9f, 0.33f).normalize()

    private val tints = arrayOf(
        floatArrayOf(0.70f, 0.82f, 1.00f),
        floatArrayOf(0.88f, 0.93f, 1.00f),
        floatArrayOf(1.00f, 1.00f, 1.00f),
        floatArrayOf(1.00f, 0.93f, 0.78f),
        floatArrayOf(1.00f, 0.78f, 0.55f),
        floatArrayOf(1.00f, 0.62f, 0.45f),
    )

    fun build(count: Int, seed: Long = 42L): FloatArray {
        val rng = Rng(seed)
        val out = FloatArray(count * FLOATS)
        val d = Vec3()
        val g = galacticNormal
        for (i in 0 until count) {
            rng.onSphere(d)
            if (rng.nextFloat() < 0.55f) {
                // pull towards the galactic plane
                val k = rng.nextFloat().pow(0.5f) * 0.92f
                val h = d.dot(g) * k
                d.addScaled(g, -h).normalize()
            }
            val b = rng.nextFloat().pow(7f)
            val tint = tints[rng.nextInt(tints.size)]
            val inten = 0.22f + 1.6f * b
            val o = i * FLOATS
            out[o] = d.x; out[o + 1] = d.y; out[o + 2] = d.z
            out[o + 3] = tint[0] * inten; out[o + 4] = tint[1] * inten; out[o + 5] = tint[2] * inten
            out[o + 6] = 1.1f + b * 3.2f
        }
        return out
    }
}

/** Space dust particles that wrap around the camera to visualise velocity. */
object DustField {
    const val BOX = 90f

    /** Vertex data: seed xyz + corner (along, side); 4 vertices per particle. */
    fun build(count: Int, seed: Long = 9L): Pair<FloatArray, ShortArray> {
        val rng = Rng(seed)
        val v = FloatArray(count * 4 * 5)
        val idx = ShortArray(count * 6)
        for (i in 0 until count) {
            val x = rng.nextFloat() * BOX
            val y = rng.nextFloat() * BOX
            val z = rng.nextFloat() * BOX
            for (c in 0 until 4) {
                val o = (i * 4 + c) * 5
                v[o] = x; v[o + 1] = y; v[o + 2] = z
                v[o + 3] = if (c < 2) 0f else 1f
                v[o + 4] = if (c % 2 == 0) -1f else 1f
            }
            val b = i * 4
            idx[i * 6] = b.toShort(); idx[i * 6 + 1] = (b + 1).toShort(); idx[i * 6 + 2] = (b + 2).toShort()
            idx[i * 6 + 3] = (b + 2).toShort(); idx[i * 6 + 4] = (b + 1).toShort(); idx[i * 6 + 5] = (b + 3).toShort()
        }
        return v to idx
    }
}

/** Simple position-only primitives for the planet and its rings. */
object Primitives {
    fun uvSphere(segments: Int, rings: Int): Pair<FloatArray, ShortArray> {
        val verts = FloatArray((segments + 1) * (rings + 1) * 3)
        var o = 0
        for (r in 0..rings) {
            val lat = (r.toDouble() / rings - 0.5) * PI
            for (s in 0..segments) {
                val lon = s.toDouble() / segments * 2 * PI
                verts[o++] = (cos(lat) * cos(lon)).toFloat()
                verts[o++] = sin(lat).toFloat()
                verts[o++] = (cos(lat) * sin(lon)).toFloat()
            }
        }
        val idx = ShortArray(segments * rings * 6)
        o = 0
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = r * (segments + 1) + s
            val b = a + segments + 1
            idx[o++] = a.toShort(); idx[o++] = b.toShort(); idx[o++] = (a + 1).toShort()
            idx[o++] = (a + 1).toShort(); idx[o++] = b.toShort(); idx[o++] = (b + 1).toShort()
        }
        return verts to idx
    }

    fun annulus(segments: Int, inner: Float, outer: Float): Pair<FloatArray, ShortArray> {
        val verts = FloatArray((segments + 1) * 2 * 3)
        var o = 0
        for (s in 0..segments) {
            val a = s.toDouble() / segments * 2 * PI
            val c = cos(a).toFloat()
            val sn = sin(a).toFloat()
            verts[o++] = c * inner; verts[o++] = 0f; verts[o++] = sn * inner
            verts[o++] = c * outer; verts[o++] = 0f; verts[o++] = sn * outer
        }
        val idx = ShortArray(segments * 6)
        o = 0
        for (s in 0 until segments) {
            val a = s * 2
            idx[o++] = a.toShort(); idx[o++] = (a + 1).toShort(); idx[o++] = (a + 2).toShort()
            idx[o++] = (a + 2).toShort(); idx[o++] = (a + 1).toShort(); idx[o++] = (a + 3).toShort()
        }
        return verts to idx
    }

    /** Fullscreen triangle in NDC. */
    val fullscreenTriangle = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
}
