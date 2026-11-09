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
    fun spinAngleIsWrappedInDouble() {
        val tau = 2 * PI
        // a long session: float spin * time would keep ~3 digits of the angle
        val a = BodyMath.spinAngle(0.0025f, 3.0e6f)
        assertTrue(a >= 0.0 && a < tau, "angle $a")
        assertEquals((0.0025f.toDouble() * 3.0e6f.toDouble()).mod(tau), a, 1e-9)
        val n = BodyMath.spinAngle(-0.01f, 1234.5f)
        assertTrue(n >= 0.0 && n < tau, "negative spin $n")
        assertEquals((-0.01f.toDouble() * 1234.5).mod(tau), n, 1e-9)
        assertEquals(0.0, BodyMath.spinAngle(0f, Float.POSITIVE_INFINITY))
        assertEquals(0.0, BodyMath.spinAngle(1e-4f, Float.NaN))
        assertEquals(0.0, BodyMath.spinAngle(0f, 0f))
    }

    /** PLANET II-like atmosphere (thickness 0.025, tint (0.35, 0.55, 1)^2, ground depth 0.1). */
    private fun zenith(altitude: Double, sunElevationDeg: Double): Double {
        val r = 6.0e6
        return BodyMath.zenithSkyAlpha(
            1.0 + altitude / r, 0.025, 0.1225, 0.3025, 1.0, 0.1, sin(sunElevationDeg * PI / 180),
            2.6, 2.45, 2.25, 1.0, 8,
        )
    }

    @Test
    fun zenithSkyFadesWithAltitudeAndNight() {
        val ground = zenith(0.0, 60.0)
        assertTrue(ground > 0.85, "daylight at the ground: $ground")
        assertTrue(zenith(0.0, 90.0) > ground, "noon")
        val high = zenith(60_000.0, 60.0)
        assertTrue(high < ground && high > 0.0, "60 km: $high")
        assertTrue(zenith(140_000.0, 60.0) < 0.1, "near the top of the shell")
        assertEquals(0.0, zenith(160_000.0, 60.0), "above the shell (150 km)")
        val night = zenith(0.0, -20.0)
        assertTrue(night < 0.15, "night: $night")
        // monotonic in sun height
        var last = -1.0
        for (e in -20..90 step 10) {
            val v = zenith(10_000.0, e.toDouble())
            assertTrue(v >= last - 1e-9, "sun $e deg: $v < $last")
            last = v
        }
    }

    @Test
    fun anythingCompiledOrBakedChangesTheConfig() {
        assertTrue(BodyRenderer.configKey(0) != BodyRenderer.configKey(1), "texture size / steps / octaves")
        assertTrue(BodyRenderer.configKey(1) != BodyRenderer.configKey(2), "detail octaves 3 vs 4")
        assertTrue(BodyRenderer.configKey(2) != BodyRenderer.configKey(3), "ULTRA marches the air in 12 steps")
        assertEquals(BodyRenderer.configKey(3), BodyRenderer.configKey(4))
    }

    @Test
    fun shaderSourcesArePureAscii() {
        val sources = mapOf(
            "vs" to BodyShaders.vs,
            "planet q0" to BodyShaders.planetFs(BodyRenderer.atmoSteps(0), BodyRenderer.detailOctaves(0)),
            "planet q2" to BodyShaders.planetFs(BodyRenderer.atmoSteps(2), BodyRenderer.detailOctaves(2)),
            "ring" to BodyShaders.ringFs,
            "star" to BodyShaders.starFs,
            "glow" to BodyShaders.glowFs,
            "bakeVs" to BodyShaders.bakeVs,
            "bakeFs" to BodyShaders.bakeFs,
        )
        for ((name, src) in sources) {
            val bad = src.indexOfFirst { it.code > 0x7E || (it.code < 0x20 && it != '\n') }
            assertEquals(-1, bad, "$name: non-ASCII character at $bad: '${if (bad >= 0) src[bad] else ' '}'")
        }
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
