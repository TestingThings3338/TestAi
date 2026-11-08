package com.starwake.core.gfx

import com.starwake.core.math.Rng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyRendererMathTest {
    private val r = 6.0e6

    /** Camera at the origin, planet centre straight below at [altitude] metres under the camera's feet. */
    private fun above(altitude: Double, out: Impostor = Impostor()): Impostor {
        val d = r + altitude
        BodyMath.impostor(0.0, -d, 0.0, d, r, 1e-4, out)
        return out
    }

    @Test
    fun ccIsExactAtOneKilometreAltitude() {
        val imp = above(1000.0)
        // |c|²/r² - 1 = h (2r + h) / r²: a float of the naive difference would keep ~2 digits
        val exact = 1000.0 * (2 * r + 1000.0) / (r * r)
        assertTrue(abs(imp.cc.toFloat() - exact) / exact < 1e-6, "cc ${imp.cc} vs $exact")
        assertEquals(1.0 + 1000.0 / r, imp.d, 1e-12)
        assertEquals(r, imp.rEff, 0.0)
    }

    /** Float replica of the fragment shader's rayInfo + near hit: returns (t, normal) in radii or null on a miss. */
    private fun castLikeShader(imp: Impostor, dx: Float, dy: Float, dz: Float): FloatArray? {
        val dd = imp.d.toFloat()
        val cx = imp.dirX
        val cy = imp.dirY
        val cz = imp.dirZ
        val crx = dy * cz - dz * cy
        val cry = dz * cx - dx * cz
        val crz = dx * cy - dy * cx
        val b = dd * (dx * cx + dy * cy + dz * cz)
        val cc = imp.cc.toFloat()
        val disc = if (dd < 4f) b * b - cc else 1f - dd * dd * (crx * crx + cry * cry + crz * crz)
        if (b <= 0f || disc <= 0f) return null
        val sq = sqrt(disc)
        val t = cc / (b + sq)
        // pca = D * cross(d, cr); normal = pca - d * sq
        val px = dd * (dy * crz - dz * cry)
        val py = dd * (dz * crx - dx * crz)
        val pz = dd * (dx * cry - dy * crx)
        return floatArrayOf(t, px - dx * sq, py - dy * sq, pz - dz * sq)
    }

    @Test
    fun shaderRayCastIsAccurateJustAboveTheSurface() {
        for (alt in doubleArrayOf(20.0, 1000.0, 20_000.0)) {
            val imp = above(alt)
            val dip = Math.acos(r / (r + alt))
            // straight down ... just below the horizon
            for (depression in doubleArrayOf(PI / 2, PI / 4, 0.2, 3 * dip, 1.2 * dip, 1.02 * dip)) {
                val dx = cos(depression).toFloat()
                val dy = (-sin(depression)).toFloat()
                val hit = castLikeShader(imp, dx, dy, 0f) ?: error("alt $alt, depression $depression: missed")
                // double reference with the float direction, centre at (0, -(r + alt), 0)
                val l = sqrt(dx.toDouble() * dx + dy.toDouble() * dy)
                val ux = dx / l
                val uy = dy / l
                val cyc = -(r + alt)
                val b = uy * cyc
                val c = alt * (2 * r + alt)
                val tRef = c / (b + sqrt(b * b - c))
                val tGot = hit[0] * r
                assertTrue(abs(tGot - tRef) <= maxOf(0.05, 2e-5 * tRef), "alt $alt, depression $depression: t $tGot m vs $tRef m")
                // the hit point lies on the sphere and the normal points along it
                val hx = ux * tRef / r
                val hy = (uy * tRef - cyc) / r
                val nl = sqrt(hit[1].toDouble() * hit[1] + hit[2].toDouble() * hit[2] + hit[3].toDouble() * hit[3])
                assertEquals(1.0, nl, 1e-5, "normal length")
                val err = sqrt((hit[1] - hx) * (hit[1] - hx) + (hit[2] - hy) * (hit[2] - hy) + hit[3].toDouble() * hit[3])
                assertTrue(err * r < 2.0, "alt $alt, depression $depression: normal off by ${err * r} m on the surface")
            }
            // just above the horizon: a miss
            assertEquals(null, castLikeShader(imp, cos(0.9 * dip).toFloat(), (-sin(0.9 * dip)).toFloat(), 0f), "above the horizon at $alt m")
        }
    }

    @Test
    fun farBodiesAreEnlargedToTheMinimumApparentSize() {
        val minAngle = 1.5 * 0.0011
        val imp = Impostor()
        val dist = 3.0e9
        BodyMath.impostor(dist, 0.0, 0.0, dist, 2.2e6, minAngle, imp)
        assertTrue(imp.trueAngle < minAngle)
        assertEquals(minAngle, asin(imp.rEff / dist), 1e-12)
        assertEquals(1.0 / sin(minAngle), imp.d, 1e-6)
        assertEquals(imp.d * imp.d - 1.0, imp.cc, 1e-6)
        assertEquals(1f, imp.dirX)
        // big enough bodies keep their real radius
        BodyMath.impostor(dist, 0.0, 0.0, dist, 6.0e7, minAngle, imp)
        assertEquals(6.0e7, imp.rEff, 0.0)
    }

    @Test
    fun cameraInsideABodyIsPutOnItsSurface() {
        val imp = Impostor()
        BodyMath.impostor(0.0, 0.0, -1000.0, 1000.0, 6.0e6, 1e-4, imp)
        assertTrue(imp.cc > 0.0 && imp.cc < 1e-6, "cc ${imp.cc}")
        assertTrue(imp.d > 1.0 && imp.d < 1.0 + 1e-6, "d ${imp.d}")
        assertEquals(-1f, imp.dirZ)
        assertEquals(PI / 2, imp.trueAngle, 0.0)
        // degenerate: camera exactly at the centre still gives a usable direction
        BodyMath.impostor(0.0, 0.0, 0.0, 0.0, 6.0e6, 1e-4, imp)
        assertEquals(-1f, imp.dirZ)
        assertTrue(imp.cc > 0.0)
    }

    @Test
    fun sortsFarToNear() {
        val rng = Rng(7L)
        for (n in intArrayOf(0, 1, 2, 6, 17)) {
            val dist = DoubleArray(n) { rng.nextFloat() * 1e9 }
            if (n > 3) dist[2] = dist[1] // ties
            val order = IntArray(n) { it }
            BodyMath.sortFarToNear(order, dist, n)
            assertEquals((0 until n).toSet(), order.toSet(), "permutation")
            for (i in 1 until n) assertTrue(dist[order[i - 1]] >= dist[order[i]], "descending at $i")
        }
    }
}
