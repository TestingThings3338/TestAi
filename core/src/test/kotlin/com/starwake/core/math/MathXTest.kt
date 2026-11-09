package com.starwake.core.math

import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.TestAsserts.assertVec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MathXTest {
    @Test
    fun clampAndLerp() {
        assertEquals(0f, clamp(-1f, 0f, 2f))
        assertEquals(2f, clamp(3f, 0f, 2f))
        assertEquals(1.5f, clamp(1.5f, 0f, 2f))
        assertEquals(1f, clamp01(7f))
        assertEquals(0f, clamp01(-7f))
        assertEquals(5f, lerp(0f, 10f, 0.5f))
        assertEquals(10f, lerp(0f, 10f, 1f))
        assertEquals(-2f, lerp(2f, -2f, 1f))
    }

    @Test
    fun deadZoneZeroesTheCentreAndRescalesToFullRange() {
        val dz = 0.15f
        assertEquals(0f, deadZone(0f, dz))
        assertEquals(0f, deadZone(0.15f, dz))
        assertEquals(0f, deadZone(-0.1f, dz))
        assertNear(1f, deadZone(1f, dz), 1e-6f)
        assertNear(-1f, deadZone(-1f, dz), 1e-6f)
        assertNear(0.5f, deadZone(0.575f, dz), 1e-6f)
        assertNear(-0.5f, deadZone(-0.575f, dz), 1e-6f)
        // continuous at the edge and monotonic
        assertTrue(deadZone(0.1501f, dz) in 0f..1e-3f)
        var prev = -2f
        var v = -1f
        while (v <= 1f) {
            val o = deadZone(v, dz)
            assertTrue(o >= prev - 1e-7f, "deadZone must be monotonic at $v")
            assertTrue(-o == deadZone(-v, dz), "deadZone must be odd at $v") // primitive ==: -0f == 0f
            prev = o
            v += 0.01f
        }
        // zero dead zone is the identity
        assertNear(0.37f, deadZone(0.37f, 0f), 1e-7f)
    }

    @Test
    fun approachMovesByAtMostMaxDeltaAndNeverOvershoots() {
        assertEquals(1f, approach(0f, 10f, 1f))
        assertEquals(-1f, approach(0f, -10f, 1f))
        assertEquals(10f, approach(9.5f, 10f, 1f))
        assertEquals(-10f, approach(-9.5f, -10f, 1f))
        assertEquals(3f, approach(3f, 3f, 1f))
        assertEquals(2f, approach(2f, 5f, 0f))
        // repeated application converges exactly
        var x = 0f
        repeat(100) { x = approach(x, 2.5f, 0.1f) }
        assertEquals(2.5f, x)
    }

    @Test
    fun expApproachIsFrameRateIndependent() {
        assertEquals(0f, expApproach(5f, 0f))
        assertTrue(expApproach(5f, 100f) > 0.9999f)
        assertTrue(expApproach(5f, 0.1f) in 0f..1f)
        // two steps of dt equal one step of 2*dt
        val rate = 4f
        val dt = 1f / 60f
        var a = 0f
        repeat(2) { a += (1f - a) * expApproach(rate, dt) }
        val b = expApproach(rate, 2f * dt)
        assertNear(b, a, 1e-6f)
        // 60 Hz and 120 Hz reach the same value after one second
        var x60 = 0f
        repeat(60) { x60 += (1f - x60) * expApproach(rate, 1f / 60f) }
        var x120 = 0f
        repeat(120) { x120 += (1f - x120) * expApproach(rate, 1f / 120f) }
        assertNear(x60, x120, 1e-5f)
        assertNear(1f - kotlin.math.exp(-rate), x60, 1e-5f)
    }

    @Test
    fun smoothstepAndEasing() {
        assertEquals(0f, smoothstep(0f, 1f, -1f))
        assertEquals(1f, smoothstep(0f, 1f, 2f))
        assertNear(0.5f, smoothstep(0f, 1f, 0.5f), 1e-6f)
        assertNear(0.5f, smoothstep(2f, 4f, 3f), 1e-6f)
        assertEquals(0f, easeInOutCubic(0f))
        assertEquals(1f, easeInOutCubic(1f))
        assertNear(0.5f, easeInOutCubic(0.5f), 1e-6f)
        assertEquals(0f, easeInOutCubic(-3f))
        assertEquals(1f, easeInOutCubic(3f))
        assertEquals(0f, easeOutCubic(0f))
        assertEquals(1f, easeOutCubic(1f))
        assertTrue(easeOutCubic(0.5f) > 0.5f, "ease-out is ahead of linear")
        assertTrue(easeInOutCubic(0.25f) < 0.25f && easeInOutCubic(0.75f) > 0.75f)
    }

    @Test
    fun expoCurveBlendsLinearAndCubic() {
        assertEquals(0.5f, expoCurve(0.5f, 0f))
        assertNear(0.125f, expoCurve(0.5f, 1f), 1e-7f)
        assertEquals(1f, expoCurve(1f, 0.7f))
        assertEquals(-1f, expoCurve(-1f, 0.7f))
    }

    @Test
    fun argbPacksRoundsAndClamps() {
        assertEquals(0xFFFF0000.toInt(), argb(1f, 1f, 0f, 0f))
        assertEquals(0xFF00FF00.toInt(), argb(1f, 0f, 1f, 0f))
        assertEquals(0xFF0000FF.toInt(), argb(1f, 0f, 0f, 1f))
        assertEquals(0x00000000, argb(0f, 0f, 0f, 0f))
        assertEquals(0x80808080.toInt(), argb(0.5f, 0.5f, 0.5f, 0.5f)) // 127.5 rounds up to 128
        assertEquals(0xFFFFFFFF.toInt(), argb(2f, 3f, 1.5f, 9f))
        assertEquals(0x00000000, argb(-1f, -2f, -0.5f, -9f))
        assertEquals(0x40332211, argb(64 / 255f, 0x33 / 255f, 0x22 / 255f, 0x11 / 255f))
    }

    @Test
    fun withAlphaScalesOnlyTheAlphaChannel() {
        val c = 0xFF112233.toInt()
        assertEquals(c, withAlpha(c, 1f))
        assertEquals(0x00112233, withAlpha(c, 0f))
        assertEquals(0x80112233.toInt(), withAlpha(c, 0.5f))
        assertEquals(c, withAlpha(c, 5f)) // clamped
        assertEquals(0x00112233, withAlpha(c, -1f))
        // multiplies an existing alpha instead of replacing it
        assertEquals(0x40ABCDEF, withAlpha(0x80ABCDEF.toInt(), 0.5f))
    }

    @Test
    fun lerpColorInterpolatesEveryChannel() {
        val a = 0xFF000000.toInt()
        val b = 0x00FF80FF
        assertEquals(a, lerpColor(a, b, 0f))
        assertEquals(b, lerpColor(a, b, 1f))
        assertEquals(b, lerpColor(a, b, 2f))
        val mid = lerpColor(a, b, 0.5f)
        assertTrue(abs((mid ushr 24) - 0x7F) <= 1)
        assertTrue(abs(((mid shr 16) and 0xFF) - 0x7F) <= 1)
        assertTrue(abs(((mid shr 8) and 0xFF) - 0x40) <= 1)
        assertTrue(abs((mid and 0xFF) - 0x7F) <= 1)
        assertEquals(0x12345678, lerpColor(0x12345678, 0x12345678, 0.37f))
    }

    @Test
    fun angleConstants() {
        assertNear(kotlin.math.PI.toFloat(), PI_F, 1e-7f)
        assertNear(2f * PI_F, TAU_F, 1e-6f)
        assertNear(PI_F, 180f * DEG2RAD, 1e-6f)
    }

    @Test
    fun vec3Basics() {
        val a = Vec3(1f, 0f, 0f)
        val b = Vec3(0f, 1f, 0f)
        assertVec(0f, 0f, 1f, Vec3().cross(a, b))
        // aliasing-safe cross
        val c = Vec3(1f, 0f, 0f)
        c.cross(c, b)
        assertVec(0f, 0f, 1f, c)
        assertEquals(0f, a.dot(b))
        assertNear(5f, Vec3(3f, 4f, 0f).length(), 1e-6f)
        assertVec(0.6f, 0.8f, 0f, Vec3(3f, 4f, 0f).normalize())
        assertVec(0f, 0f, 0f, Vec3().normalize(), 0f) // zero vector stays zero, no NaN
        assertNear(5f, Vec3(1f, 1f, 1f).distance(Vec3(4f, 5f, 1f)), 1e-6f)
        assertVec(2f, 3f, 4f, Vec3(1f, 1f, 1f).addScaled(Vec3(1f, 2f, 3f), 1f))
        assertVec(0.5f, 0f, 0f, Vec3().lerp(Vec3(1f, 0f, 0f), 0.5f))
    }

    @Test
    fun smoothIsShipMathSmooth() {
        assertEquals(7f, smooth(1f, 7f, 0f, 0.016f), "tau <= 0 jumps to the target")
        assertEquals(7f, smooth(1f, 7f, -1f, 0.016f))
        // one tau covers 63 % of the gap
        assertNear(1f + 6f * (1f - kotlin.math.exp(-1f)), smooth(1f, 7f, 0.5f, 0.5f), 1e-6f)
        // two half steps equal one full step: frame-rate independent
        val full = smooth(0f, 10f, 0.12f, 0.1f)
        val half = smooth(smooth(0f, 10f, 0.12f, 0.05f), 10f, 0.12f, 0.05f)
        assertNear(full, half, 1e-5f)
        // the same as expApproach with rate = 1 / tau
        assertNear(0f + 10f * expApproach(1f / 0.12f, 0.1f), full, 1e-5f)
    }

    @Test
    fun applyDeadzoneIsRadialWithACurve() {
        val out = Vec3()
        // inside the dead zone (and its edge) -> 0
        assertVec(0f, 0f, 0f, applyDeadzone(0.1f, 0.1f, 0.2f, 1.7f, out))
        assertVec(0f, 0f, 0f, applyDeadzone(0.2f, 0f, 0.2f, 1.7f, out))
        assertVec(0f, 0f, 0f, applyDeadzone(0f, 0f, 0f, 1f, out), message = "zero input stays zero")
        // 02-flight.md 10.4: 0.5 -> 0.18874, 0.6 -> 0.30779 (= 0.5^1.7), 1.0 -> 1
        assertNear(0.18874f, applyDeadzone(0.5f, 0f, 0.2f, 1.7f, out).x, 1e-5f)
        assertNear(0.30779f, applyDeadzone(0.6f, 0f, 0.2f, 1.7f, out).x, 1e-5f)
        assertNear(0.30779f, applyDeadzone(0f, -0.6f, 0.2f, 1.7f, out).y * -1f, 1e-5f)
        assertNear(1f, applyDeadzone(1f, 0f, 0.2f, 1.7f, out).x, 1e-6f)
        assertNear(1f, applyDeadzone(3f, 0f, 0.2f, 1.7f, out).x, 1e-6f, "clamped to 1")
        // radial: the diagonal (0.5, 0.5) has the magnitude of (0.7071, 0): 0.46070
        applyDeadzone(0.5f, 0.5f, 0.2f, 1.7f, out)
        val diag = kotlin.math.sqrt(out.x * out.x + out.y * out.y)
        assertNear(0.46070f, diag, 1e-4f)
        assertNear(applyDeadzone(0.70710677f, 0f, 0.2f, 1.7f, Vec3()).x, diag, 1e-5f)
        assertNear(out.x, out.y, 1e-6f, "direction kept")
        // curve 1 is linear after the rescale
        assertNear(0.5f, applyDeadzone(0.6f, 0f, 0.2f, 1f, out).x, 1e-6f)
    }

    @Test
    fun roundHalfAwayIsLuauMathRound() {
        assertEquals(3.0, roundHalfAway(2.5))
        assertEquals(-3.0, roundHalfAway(-2.5))
        assertEquals(2.0, roundHalfAway(2.49))
        assertEquals(-2.0, roundHalfAway(-2.49))
        assertEquals(0.0, roundHalfAway(0.0))
        assertEquals(1.0, roundHalfAway(0.5))
    }

    @Test
    fun dvec3KeepsPrecisionFarFromTheOrigin() {
        val far = DVec3(1.0e7 + 0.25, -2.0e7, 3.0e7 + 0.5)
        val origin = DVec3(1.0e7, -2.0e7 - 0.75, 3.0e7)
        val rel = far.relativeTo(origin, Vec3())
        assertVec(0.25f, 0.75f, 0.5f, rel, 1e-6f)
        val p = DVec3(1.0e7, 0.0, 0.0)
        repeat(1000) { p.addScaled(Vec3(0.001f, 0f, 0f), 1.0) }
        assertNear(1f, (p.x - 1.0e7).toFloat(), 1e-6f)
    }
}
