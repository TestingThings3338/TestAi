package com.starwake.core.math

import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.TestAsserts.assertSameRotation
import com.starwake.core.TestAsserts.assertUnit
import com.starwake.core.TestAsserts.assertVec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class QuatTest {
    private val rng = Rng(2024)

    private fun randomQuat(): Quat {
        val axis = rng.onSphere(Vec3())
        return Quat().setAxisAngle(axis.x, axis.y, axis.z, rng.range(-PI_F, PI_F))
    }

    private fun randomVec(): Vec3 = Vec3(rng.signed() * 10f, rng.signed() * 10f, rng.signed() * 10f)

    @Test
    fun identityLeavesVectorsUnchanged() {
        val out = Quat().rotate(1f, 2f, 3f, Vec3())
        assertVec(1f, 2f, 3f, out)
    }

    @Test
    fun rotateThenRotateInverseRoundTrips() {
        repeat(200) {
            val q = randomQuat()
            val v = randomVec()
            val r = q.rotate(v, Vec3())
            assertNear(v.length(), r.length(), 1e-4f, "rotation must preserve length")
            val back = q.rotateInverse(r, Vec3())
            assertVec(v, back, 1e-4f)
            val back2 = q.rotate(q.rotateInverse(v, Vec3()), Vec3())
            assertVec(v, back2, 1e-4f)
        }
    }

    @Test
    fun rotateAndRotateInverseAllowAliasedOutput() {
        val q = Quat().setAxisAngle(0.3f, 1f, -0.2f, 1.1f)
        val v = Vec3(1f, -2f, 0.5f)
        val expected = q.rotate(v, Vec3())
        q.rotate(v, v)
        assertVec(expected, v, 1e-6f)
        q.rotateInverse(v, v)
        assertVec(1f, -2f, 0.5f, v, 1e-5f)
    }

    @Test
    fun mulComposesRotationsRightToLeft() {
        repeat(200) {
            val a = randomQuat()
            val b = randomQuat()
            val v = randomVec()
            val ab = Quat().mul(a, b)
            assertUnit(ab, 1e-4f)
            val viaProduct = ab.rotate(v, Vec3())
            val viaSteps = a.rotate(b.rotate(v, Vec3()), Vec3())
            assertVec(viaSteps, viaProduct, 2e-4f, "(a*b)v must equal a(b v)")
        }
    }

    @Test
    fun mulIsAssociative() {
        repeat(100) {
            val a = randomQuat()
            val b = randomQuat()
            val c = randomQuat()
            val left = Quat().mul(Quat().mul(a, b), c)
            val right = Quat().mul(a, Quat().mul(b, c))
            assertSameRotation(left, right, 1e-5f)
            val v = randomVec()
            assertVec(left.rotate(v, Vec3()), right.rotate(v, Vec3()), 2e-4f)
        }
    }

    @Test
    fun mulIsSafeWhenOutputAliasesAnOperand() {
        val a = Quat().setAxisAngle(1f, 0f, 0f, 0.7f)
        val b = Quat().setAxisAngle(0f, 1f, 0f, -0.4f)
        val expected = Quat().mul(a, b)
        val a2 = Quat().set(a)
        a2.mul(a2, b)
        assertSameRotation(expected, a2, 1e-6f)
        val b2 = Quat().set(b)
        b2.mul(a, b2)
        assertSameRotation(expected, b2, 1e-6f)
    }

    @Test
    fun positiveAngleAboutPlusYTurnsTheNoseLeft() {
        // Objects face -Z. A +90° (right handed) turn about +Y must move the nose to -X (left).
        val q = Quat().setAxisAngle(0f, 1f, 0f, PI_F / 2f)
        assertVec(-1f, 0f, 0f, q.rotate(0f, 0f, -1f, Vec3()), 1e-6f)
        // +X (right wing) goes to -Z (forward)
        assertVec(0f, 0f, -1f, q.rotate(1f, 0f, 0f, Vec3()), 1e-6f)
        assertVec(0f, 1f, 0f, q.rotate(0f, 1f, 0f, Vec3()), 1e-6f)
    }

    @Test
    fun positiveAngleAboutPlusXRaisesTheNose() {
        val q = Quat().setAxisAngle(1f, 0f, 0f, PI_F / 2f)
        assertVec(0f, 1f, 0f, q.rotate(0f, 0f, -1f, Vec3()), 1e-6f)
    }

    @Test
    fun positiveAngleAboutPlusZRaisesTheRightWing() {
        val q = Quat().setAxisAngle(0f, 0f, 1f, PI_F / 2f)
        assertVec(0f, 1f, 0f, q.rotate(1f, 0f, 0f, Vec3()), 1e-6f)
    }

    @Test
    fun setAxisAngleNormalisesTheAxisAndHandlesZeroAxis() {
        val a = Quat().setAxisAngle(0f, 5f, 0f, 1f)
        val b = Quat().setAxisAngle(0f, 1f, 0f, 1f)
        assertSameRotation(b, a, 1e-6f)
        assertUnit(a)
        val z = Quat(0.1f, 0.2f, 0.3f, 0.9f).setAxisAngle(0f, 0f, 0f, 1f)
        assertSameRotation(Quat(), z, 0f)
    }

    @Test
    fun integrateLocalWithConstantBodyRateMatchesAxisAngle() {
        val start = Quat().setAxisAngle(0.2f, 0.9f, -0.3f, 0.8f)
        val wx = 0.7f
        val wy = -0.4f
        val wz = 1.3f
        val dt = 1f / 120f
        val steps = 240
        val q = Quat().set(start)
        repeat(steps) { q.integrateLocal(wx, wy, wz, dt) }
        val rate = sqrt(wx * wx + wy * wy + wz * wz)
        val delta = Quat().setAxisAngle(wx, wy, wz, rate * dt * steps)
        val expected = Quat().mul(start, delta) // body frame: applied on the right
        assertSameRotation(expected, q, 1e-6f)
        assertUnit(q)
        // compare the resulting axes too (tighter than the quaternion dot)
        val v = Vec3(0.3f, -0.5f, -1f)
        assertVec(expected.rotate(v, Vec3()), q.rotate(v, Vec3()), 1e-4f)
    }

    @Test
    fun integrateLocalIsInTheBodyFrame() {
        // Ship yawed 90° left; a body pitch-up rate must raise the nose (world +Y), not roll it.
        val q = Quat().setAxisAngle(0f, 1f, 0f, PI_F / 2f)
        repeat(100) { q.integrateLocal(PI_F / 2f, 0f, 0f, 0.01f) } // 90° pitch over 1 s
        assertVec(0f, 1f, 0f, q.rotate(0f, 0f, -1f, Vec3()), 1e-4f)
    }

    @Test
    fun integrateLocalWithZeroRateIsANoOp() {
        val q = Quat().setAxisAngle(1f, 1f, 0f, 0.5f)
        val before = Quat().set(q)
        q.integrateLocal(0f, 0f, 0f, 1f / 60f)
        assertSameRotation(before, q, 1e-6f)
    }

    @Test
    fun slerpHitsTheEndpoints() {
        repeat(50) {
            val a = randomQuat()
            val b = randomQuat()
            assertSameRotation(a, Quat().slerp(a, b, 0f), 1e-5f)
            assertSameRotation(b, Quat().slerp(a, b, 1f), 1e-5f)
        }
    }

    @Test
    fun slerpMidpointIsHalfTheAngle() {
        val a = Quat()
        val b = Quat().setAxisAngle(0f, 1f, 0f, 1.2f)
        val mid = Quat().slerp(a, b, 0.5f)
        assertSameRotation(Quat().setAxisAngle(0f, 1f, 0f, 0.6f), mid, 1e-6f)
        val quarter = Quat().slerp(a, b, 0.25f)
        assertSameRotation(Quat().setAxisAngle(0f, 1f, 0f, 0.3f), quarter, 1e-6f)
    }

    @Test
    fun slerpTakesTheShortestPath() {
        // +300° about Y is the same orientation as -60°; its quaternion has w < 0 (dot with identity < 0).
        val a = Quat()
        val b = Quat().setAxisAngle(0f, 1f, 0f, 300f * DEG2RAD)
        assertTrue(a.dot(b) < 0f)
        val mid = Quat().slerp(a, b, 0.5f)
        assertSameRotation(Quat().setAxisAngle(0f, 1f, 0f, -30f * DEG2RAD), mid, 1e-5f)
        // the nose swings right by 30°, not left by 150°
        val fwd = mid.rotate(0f, 0f, -1f, Vec3())
        assertTrue(fwd.x > 0.49f && fwd.z < -0.86f, "nose should turn 30° right, got $fwd")
    }

    @Test
    fun slerpOfNearlyIdenticalQuaternionsStaysNormalised() {
        val a = Quat().setAxisAngle(1f, 0f, 0f, 0.5f)
        val b = Quat().setAxisAngle(1f, 0f, 0f, 0.5005f)
        val m = Quat().slerp(a, b, 0.5f)
        assertUnit(m, 1e-6f)
        assertSameRotation(Quat().setAxisAngle(1f, 0f, 0f, 0.50025f), m, 1e-6f)
    }

    @Test
    fun slerpIsSafeWhenOutputAliasesA() {
        val a = Quat().setAxisAngle(0f, 0f, 1f, 0.2f)
        val b = Quat().setAxisAngle(0f, 0f, 1f, 1.0f)
        a.slerp(a, b, 0.5f)
        assertSameRotation(Quat().setAxisAngle(0f, 0f, 1f, 0.6f), a, 1e-6f)
    }

    private fun checkLookRotation(fx: Float, fy: Float, fz: Float, ux: Float, uy: Float, uz: Float) {
        val q = Quat().lookRotation(fx, fy, fz, ux, uy, uz)
        assertUnit(q, 1e-5f)
        val f = Vec3(fx, fy, fz).normalize()
        val fwd = q.rotate(0f, 0f, -1f, Vec3())
        assertVec(f, fwd, 1e-4f, "rotated -Z must equal forward")
        val up = q.rotate(0f, 1f, 0f, Vec3())
        val right = q.rotate(1f, 0f, 0f, Vec3())
        assertNear(0f, up.dot(fwd), 1e-4f, "up ⟂ forward")
        // right handed: right × up = back (+Z), i.e. -forward
        val back = Vec3().cross(right, up)
        assertVec(-f.x, -f.y, -f.z, back, 1e-4f, "basis must be right handed")
        val upRef = Vec3(ux, uy, uz)
        val upRefN = Vec3(ux, uy, uz).normalize()
        val perp = upRefN.length() > 0f && abs(upRefN.dot(f)) < 0.999f
        if (perp) {
            assertTrue(up.dot(upRef) > 0f, "+Y must lean towards the requested up: up=$up ref=$upRef")
            // +Y is the projection of up onto the plane perpendicular to forward
            val proj = Vec3().set(upRefN).addScaled(f, -upRefN.dot(f)).normalize()
            assertVec(proj, up, 1e-4f, "+Y must be the closest vector to up")
        }
    }

    @Test
    fun lookRotationPointsMinusZAlongForwardAndKeepsUp() {
        checkLookRotation(0f, 0f, -1f, 0f, 1f, 0f)
        checkLookRotation(0f, 0f, 1f, 0f, 1f, 0f)
        checkLookRotation(1f, 0f, 0f, 0f, 1f, 0f)
        checkLookRotation(-1f, 0f, 0f, 0f, 1f, 0f)
        checkLookRotation(3f, 4f, -5f, 0f, 1f, 0f)
        checkLookRotation(0.2f, -0.9f, 0.1f, 0f, 1f, 0f)
        checkLookRotation(0f, 0f, -1f, 1f, 1f, 0f) // banked up vector
        repeat(200) {
            val f = rng.onSphere(Vec3())
            val u = rng.onSphere(Vec3())
            if (abs(f.dot(u)) < 0.99f) checkLookRotation(f.x, f.y, f.z, u.x, u.y, u.z)
        }
    }

    @Test
    fun lookRotationIdentityForDefaultFrame() {
        assertSameRotation(Quat(), Quat().lookRotation(0f, 0f, -1f, 0f, 1f, 0f), 1e-6f)
    }

    @Test
    fun lookRotationDegenerateUpAlongWorldAxes() {
        // forward parallel (or anti-parallel) to up: any valid frame is fine, forward must still hold
        checkLookRotation(0f, 1f, 0f, 0f, 1f, 0f)
        checkLookRotation(0f, -1f, 0f, 0f, 1f, 0f)
        checkLookRotation(1f, 0f, 0f, 1f, 0f, 0f)
        checkLookRotation(0f, 0f, -1f, 0f, 0f, 1f)
        checkLookRotation(0f, 0f, -1f, 0f, 0f, 0f) // zero up vector
        checkLookRotation(0f, 1f, 0f, 1e-8f, 1f, 0f) // nearly parallel
    }

    @Test
    fun lookRotationZeroForwardGivesIdentity() {
        assertSameRotation(Quat(), Quat(0.5f, 0.5f, 0.5f, 0.5f).lookRotation(0f, 0f, 0f, 0f, 1f, 0f), 0f)
    }

    @Test
    fun lookRotationDegenerateUpOffAxis() {
        checkLookRotation(0.6f, 0.8f, 0f, 0.6f, 0.8f, 0f)
        checkLookRotation(0.6f, 0.8f, 0f, -0.6f, -0.8f, 0f)
        checkLookRotation(0.95f, 0.31225f, 0f, 0.95f, 0.31225f, 0f)
    }

    private fun basisRoundTrip(q: Quat) {
        val x = q.rotate(1f, 0f, 0f, Vec3())
        val y = q.rotate(0f, 1f, 0f, Vec3())
        val z = q.rotate(0f, 0f, 1f, Vec3())
        val r = Quat().fromBasis(x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z)
        assertSameRotation(q, r, 1e-5f, "fromBasis round trip")
        assertUnit(r)
    }

    @Test
    fun fromBasisRoundTripsRandomRotations() {
        repeat(300) { basisRoundTrip(randomQuat()) }
    }

    @Test
    fun fromBasisCoversEveryBranch() {
        // trace > 0
        basisRoundTrip(Quat())
        basisRoundTrip(Quat().setAxisAngle(1f, 2f, 3f, 0.4f))
        // trace <= 0 with m00, m11 and m22 the largest diagonal element respectively (≈180° turns)
        basisRoundTrip(Quat().setAxisAngle(1f, 0f, 0f, PI.toFloat()))
        basisRoundTrip(Quat().setAxisAngle(0f, 1f, 0f, PI.toFloat()))
        basisRoundTrip(Quat().setAxisAngle(0f, 0f, 1f, PI.toFloat()))
        basisRoundTrip(Quat().setAxisAngle(1f, 0.2f, 0.1f, 3f))
        basisRoundTrip(Quat().setAxisAngle(0.1f, 1f, 0.2f, 3f))
        basisRoundTrip(Quat().setAxisAngle(0.2f, 0.1f, 1f, 3f))
        basisRoundTrip(Quat().setAxisAngle(1f, 1f, 1f, 2.3f))
    }

    @Test
    fun normalizeHandlesZeroAndScales() {
        assertSameRotation(Quat(), Quat(0f, 0f, 0f, 0f).normalize(), 0f)
        val q = Quat(0f, 2f, 0f, 2f).normalize()
        assertNear(sqrt(0.5f), q.y, 1e-6f)
        assertNear(sqrt(0.5f), q.w, 1e-6f)
    }
}
