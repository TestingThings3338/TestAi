package com.starwake.core.terrain

/**
 * Luau's `math.noise` (04-planet-surface.md §2.0 and Appendix A): Ken Perlin's 2002 *improved
 * noise* with the reference permutation table, the 16-case gradient selection and the quintic
 * fade, evaluated in 32-bit float on float-cast arguments, period 256 in each axis, exactly 0 at
 * integer lattice points.
 *
 * What is verified here (unit tests, `LuauNoiseTest`): the table is a permutation of 0..255, the
 * kernel is 0 on the lattice and periodic with 256, matches Perlin's own reference implementation
 * (`ImprovedNoise.java`, double precision) to float precision, and reproduces the statistics the
 * Lua comments quote (5-octave fbm spread 0.16, the floes distribution). What could not be
 * verified: bit-exactness against Roblox Studio (no golden values from Studio are in the repo,
 * Appendix B step 1 is still open). Luau's VM source evaluates exactly this kernel; Roblox runs
 * that VM, so a difference would be a surprise, but it is unproven.
 *
 * Two front ends:
 * - [noisePlane]: Roblox semantics, the double arguments are cast to float first (plane mode, golden tests);
 * - [noise]: the world front end, each coordinate is reduced modulo 256 in double before the float
 *   kernel, so precision never degrades with planet size (sphere mode, §9.4.1).
 *
 * Pure and thread-safe (the tables are read-only).
 */
object LuauNoise {
    private val PERM = intArrayOf(
        151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225, 140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23,
        190, 6, 148, 247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32, 57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125,
        136, 171, 168, 68, 175, 74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122, 60, 211, 133, 230, 220, 105, 92,
        41, 55, 46, 245, 40, 244, 102, 143, 54, 65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169, 200, 196, 135, 130,
        116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64, 52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
        207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213, 119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155,
        167, 43, 172, 9, 129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104, 218, 246, 97, 228, 251, 34, 242, 193,
        238, 210, 144, 12, 191, 179, 162, 241, 81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157, 184, 84, 204,
        176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93, 222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180,
    )

    /** The permutation repeated once (512 entries), as in the reference implementation. */
    private val P = IntArray(512) { PERM[it and 255] }

    /** A copy of the 256-entry table (tests). */
    fun permutation(): IntArray = PERM.copyOf()

    private fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    private fun lerp(t: Float, a: Float, b: Float): Float = a + t * (b - a)

    private fun grad(hash: Int, x: Float, y: Float, z: Float): Float {
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if (h and 1 != 0) -u else u) + (if (h and 2 != 0) -v else v)
    }

    private fun floorF(x: Float): Float {
        val i = x.toInt().toFloat()
        return if (x < i) i - 1f else i
    }

    /** The float kernel (Luau `perlin`), on float arguments. */
    fun perlinF(x: Float, y: Float, z: Float): Float {
        val xflr = floorF(x)
        val yflr = floorF(y)
        val zflr = floorF(z)
        val xi = xflr.toInt() and 255
        val yi = yflr.toInt() and 255
        val zi = zflr.toInt() and 255
        val xf = x - xflr
        val yf = y - yflr
        val zf = z - zflr
        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)
        val p = P
        val a = p[xi] + yi
        val aa = p[a] + zi
        val ab = p[a + 1] + zi
        val b = p[xi + 1] + yi
        val ba = p[b] + zi
        val bb = p[b + 1] + zi
        return lerp(
            w,
            lerp(
                v,
                lerp(u, grad(p[aa], xf, yf, zf), grad(p[ba], xf - 1f, yf, zf)),
                lerp(u, grad(p[ab], xf, yf - 1f, zf), grad(p[bb], xf - 1f, yf - 1f, zf)),
            ),
            lerp(
                v,
                lerp(u, grad(p[aa + 1], xf, yf, zf - 1f), grad(p[ba + 1], xf - 1f, yf, zf - 1f)),
                lerp(u, grad(p[ab + 1], xf, yf - 1f, zf - 1f), grad(p[bb + 1], xf - 1f, yf - 1f, zf - 1f)),
            ),
        )
    }

    /** Roblox semantics: `math.noise(x, y, z)` with the double arguments cast to float. */
    fun noisePlane(x: Double, y: Double, z: Double): Double = perlinF(x.toFloat(), y.toFloat(), z.toFloat()).toDouble()

    /** The world front end: every coordinate reduced modulo the period in double, then the float kernel. */
    fun noise(x: Double, y: Double, z: Double): Double =
        perlinF(mod256(x).toFloat(), mod256(y).toFloat(), mod256(z).toFloat()).toDouble()

    /** v mod 256 in [0, 256] (256 itself only by rounding, which the kernel treats as 0). */
    fun mod256(v: Double): Double {
        if (v >= 0.0 && v < 256.0) return v
        return v - 256.0 * kotlin.math.floor(v * (1.0 / 256.0))
    }
}
