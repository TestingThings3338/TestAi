package com.starwake.core.flight

import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.TestAsserts.assertUnit
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.math.applyDeadzone
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ShipRotation` (02-flight.md 3.3): Roblox signs, the dead zone and curve, the FA approach and FA-off torque. */
class RotationTest {
    private val rot = Quat()
    private val rates = Vec3()
    private val rotation = ShipRotation()
    private val dt = 1f / 60f

    /** VARROW, full tank, standing (agility 0.65). */
    private val standing = ShipHandling().also { it.update(0.0, 0.0, 1.0, 0.0) }

    private fun fly(seconds: Float, aimX: Float, aimY: Float, keys: Float, fa: Boolean = true, rolls: Boolean = true, h: ShipHandling = standing) {
        var t = 0f
        while (t < seconds - 1e-6f) {
            rotation.update(rot, rates, dt, aimX, aimY, keys, h, fa, rolls)
            t += dt
        }
    }

    private fun axis(x: Float, y: Float, z: Float): Vec3 = rot.rotate(x, y, z, Vec3())

    @Test
    fun theDeadZoneIsRadialWithTheRobloxCurve() {
        val out = Vec3()
        fun mag(x: Float, y: Float) = applyDeadzone(x, y, FlightConfig.Cursor.DEADZONE, FlightConfig.Rotation.INPUT_CURVE, out).length()
        assertEquals(0f, mag(0.2f, 0f))
        assertNear(0.18874f, mag(0.5f, 0f), 2e-5f)
        assertNear(0.30779f, mag(0.6f, 0f), 2e-5f)
        assertNear(1f, mag(1f, 0f), 1e-6f)
        // radial, not per axis: the diagonal (0.5, 0.5) is |0.7071| along the diagonal
        assertNear(0.46070f, mag(0.5f, 0.5f), 2e-5f)
        assertNear(mag(0.70710677f, 0f), mag(0.5f, 0.5f), 1e-6f)
    }

    @Test
    fun cursorUpRaisesTheNose() {
        fly(0.5f, 0f, -1f, 0f) // screen Y is down: up is negative
        assertTrue(rates.x > 0f, "positive pitch rate")
        assertTrue(axis(0f, 0f, -1f).y > 0.05f, "the nose rose")
    }

    @Test
    fun cursorRightRollsTheRightWingDownWithTheDefaultMapping() {
        fly(0.3f, 1f, 0f, 0f, rolls = true)
        assertTrue(rates.z < 0f, "negative roll rate (positive rolls left)")
        assertTrue(axis(1f, 0f, 0f).y < -0.05f, "the right wing went down")
        assertEquals(0f, rates.y, 0f, "the cursor does not yaw with MOUSE X AXIS = ROLL")
    }

    @Test
    fun qYawsLeftAndTheYawMappingSwapsTheAxes() {
        fly(0.5f, 0f, 0f, 1f, rolls = true)
        assertTrue(rates.y > 0f, "Q (+1) yaws left: positive yaw rate")
        assertTrue(axis(0f, 0f, -1f).x < -0.01f, "the nose turned left")
        // MOUSE X AXIS = YAW (and the autopilot): cursor X yaws, the key axis rolls
        rot.identity(); rates.zero()
        fly(0.5f, 1f, 0f, 0f, rolls = false)
        assertTrue(rates.y < 0f, "cursor right yaws right")
        assertTrue(axis(0f, 0f, -1f).x > 0.01f, "the nose turned right")
        rot.identity(); rates.zero()
        fly(0.3f, 0f, 0f, 1f, rolls = false)
        assertTrue(rates.z > 0f, "Q rolls left in the legacy mapping")
        assertTrue(axis(1f, 0f, 0f).y > 0.05f, "the right wing went up")
    }

    @Test
    fun flightAssistReachesTheStandingPitchRateIn021Seconds() {
        // VARROW standing: 38 °/s x 0.65 = 24.7 °/s at 120 °/s² -> 0.206 s
        val target = 38.0 * 0.65 * Math.PI / 180.0
        var t = 0f
        var reached = -1f
        while (t < 0.5f) {
            rotation.update(rot, rates, 1f / 1000f, 0f, -1f, 0f, standing, true, true)
            t += 1f / 1000f
            if (reached < 0f && abs(rates.x - target) < 1e-4) reached = t
        }
        assertNear(0.206f, reached, 0.003f, "time to the standing pitch rate")
        assertNear(target.toFloat(), rates.x, 1e-5f, "held at the rate")
        // releasing: the computer brakes the rotation at the same acceleration
        fly(0.25f, 0f, 0f, 0f)
        assertEquals(0f, rates.x, 0f)
    }

    @Test
    fun withoutFlightAssistTheStickIsTorqueAndTheSpinGoesOn() {
        // 120 °/s² x 0.7 = 84 °/s² of torque, capped at 38 x 0.65 x 1.1 = 27.17 °/s
        rotation.update(rot, rates, 0.1f, 0f, -1f, 0f, standing, false, true)
        assertNear((84.0 * 0.1 * Math.PI / 180.0).toFloat(), rates.x, 1e-6f, "torque 84 °/s²")
        fly(2f, 0f, -1f, 0f, fa = false)
        val cap = (38.0 * 0.65 * 1.1 * Math.PI / 180.0).toFloat()
        assertNear(cap, rates.x, 1e-6f, "the FA-off cap (27.17 °/s)")
        // no input: no decay
        fly(3f, 0f, 0f, 0f, fa = false)
        assertNear(cap, rates.x, 1e-6f, "the spin goes on")
        // countering stops it
        fly(0.5f, 0f, 1f, 0f, fa = false)
        assertTrue(rates.x < cap * 0.1f)
    }

    @Test
    fun theDeadZoneHoldsTheShip() {
        fly(1f, 0.15f, -0.1f, 0f)
        assertEquals(0f, rates.x, 0f)
        assertEquals(0f, rates.z, 0f)
        assertEquals(0f, rotation.inputAmount, 0f)
    }

    @Test
    fun inputAmountsFeedTheAudio() {
        rotation.update(rot, rates, dt, 1f, 0f, 1f, standing, true, true)
        assertNear(1f, rotation.rollAmount, 1e-6f, "the cursor rolls")
        assertNear(1f, rotation.inputAmount, 1e-6f, "the key axis yaws")
        rotation.update(rot, rates, dt, 1f, 0f, 0.5f, standing, true, false)
        assertNear(1f, rotation.inputAmount, 1e-6f)
        assertNear(0.5f, rotation.rollAmount, 1e-6f)
    }

    @Test
    fun aHundredThousandStepsKeepTheQuaternionUnit() {
        val h = ShipHandling().also { it.update(0.0, 130.0, 1.0, 0.0) }
        for (i in 0 until 100_000) {
            val a = i * 0.001f
            rotation.update(rot, rates, dt, kotlin.math.sin(a), kotlin.math.cos(a * 1.3f), kotlin.math.sin(a * 0.7f), h, i % 3 != 0, i % 2 == 0)
        }
        assertUnit(rot, 1e-5f)
        // still a rotation: the axes are orthonormal
        val x = axis(1f, 0f, 0f)
        val y = axis(0f, 1f, 0f)
        assertTrue(abs(x.dot(y)) < 1e-5f)
        assertNear(1f, sqrt(x.dot(x)), 1e-5f)
    }

    @Test
    fun ratesInDegreesForTheCockpit() {
        rates.set(0.1f, -0.2f, 0.3f)
        val d = ShipRotation.angularVelocityDeg(rates, Vec3())
        assertNear(5.7296f, d.x, 1e-3f)
        assertNear(-11.459f, d.y, 1e-3f)
        assertNear(17.189f, d.z, 1e-3f)
    }
}
