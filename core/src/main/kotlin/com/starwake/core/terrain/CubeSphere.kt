package com.starwake.core.terrain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The cube sphere of the terrain quadtree (§9.3): six faces with frames (N, U, V) in body space,
 * an equi-angular mapping of face coordinates (a, b) ∈ [−1, 1]² to directions (area ratio ~1.4
 * instead of 5.2 for the plain cube), and the inverse.
 *
 * Faces: 0 +X (U −Z, V +Y), 1 −X (+Z, +Y), 2 +Y (+X, −Z), 3 −Y (+X, +Z), 4 +Z (+X, +Y), 5 −Z (−X, +Y).
 */
object CubeSphere {
    /** N, U, V per face, 9 doubles each. */
    private val F = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0,
        -1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0,
        0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0,
        0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
        0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0,
    )

    private const val QUARTER_PI = PI / 4

    fun nx(face: Int) = F[face * 9]
    fun ny(face: Int) = F[face * 9 + 1]
    fun nz(face: Int) = F[face * 9 + 2]

    /**
     * Unit direction of face coordinates (a, b) on [face], written to out[o..o+2]:
     * x = tan(a·π/4), y = tan(b·π/4), n = normalize(N + x·U + y·V).
     */
    fun dir(face: Int, a: Double, b: Double, out: DoubleArray, o: Int = 0) {
        // tan(±π/4) is 0.9999999999999999 in double: snap the face edges to ±1 so both faces of an
        // edge build the same vector, bit for bit (seamless edges)
        val x = if (a == 1.0) 1.0 else if (a == -1.0) -1.0 else tan(a * QUARTER_PI)
        val y = if (b == 1.0) 1.0 else if (b == -1.0) -1.0 else tan(b * QUARTER_PI)
        val f = face * 9
        val dx = F[f] + x * F[f + 3] + y * F[f + 6]
        val dy = F[f + 1] + x * F[f + 4] + y * F[f + 7]
        val dz = F[f + 2] + x * F[f + 5] + y * F[f + 8]
        val inv = 1.0 / sqrt(dx * dx + dy * dy + dz * dz)
        out[o] = dx * inv
        out[o + 1] = dy * inv
        out[o + 2] = dz * inv
    }

    /** The face a direction belongs to: the largest |component| with its sign. */
    fun faceOf(x: Double, y: Double, z: Double): Int {
        val ax = abs(x)
        val ay = abs(y)
        val az = abs(z)
        return if (ax >= ay && ax >= az) {
            if (x >= 0) 0 else 1
        } else if (ay >= az) {
            if (y >= 0) 2 else 3
        } else {
            if (z >= 0) 4 else 5
        }
    }

    /**
     * Face coordinates of a direction on [face] (which need not be its own face, as long as the
     * direction is in front of it): a = atan((n·U)/(n·N))·4/π, b likewise with V. Writes out[o], out[o+1];
     * returns false when the direction is not in front of the face (n·N ≤ 0).
     */
    fun coords(face: Int, x: Double, y: Double, z: Double, out: DoubleArray, o: Int = 0): Boolean {
        val f = face * 9
        val nn = x * F[f] + y * F[f + 1] + z * F[f + 2]
        if (nn <= 1e-12) return false
        val nu = x * F[f + 3] + y * F[f + 4] + z * F[f + 5]
        val nv = x * F[f + 6] + y * F[f + 7] + z * F[f + 8]
        out[o] = atan(nu / nn) / QUARTER_PI
        out[o + 1] = atan(nv / nn) / QUARTER_PI
        return true
    }

    /** Approximate edge length (m) of a node at [level] on a sphere of [radius]: (π/2)·R/2^L. */
    fun edge(radius: Double, level: Int): Double = PI * 0.5 * radius / (1 shl level)

    /**
     * Finest level for a vertex spacing of about [minSpacing] m with 32 quads per tile edge (§9.3):
     * log2((π/2)·R/(32·s)) rounded to the nearest level, which gives the spec's table (Earth 17 / 16,
     * Luna 15 / 14, Phobos 13 / 12 for 2.5 / 5 m; its formula says ceil, its numbers are rounded).
     */
    fun maxLevel(radius: Double, minSpacing: Double): Int {
        val v = kotlin.math.ln(PI * 0.5 * radius / (32.0 * minSpacing)) / kotlin.math.ln(2.0)
        return kotlin.math.round(v).toInt().coerceIn(1, 24)
    }
}
