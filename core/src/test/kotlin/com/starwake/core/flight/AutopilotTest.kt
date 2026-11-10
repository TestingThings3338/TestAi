package com.starwake.core.flight

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.math.applyDeadzone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** `ShipAutopilot` (02-flight.md 3.7): the examples of the spec, the statuses and the stop-speed law. */
class AutopilotTest {
    private val ap = Autopilot()
    private val pos = DVec3()
    private val rot = Quat()
    private val target = DVec3()

    /** VARROW at full tank. */
    private val h = ShipHandling()

    private fun near(what: String, expected: Double, actual: Double, eps: Double) {
        if (abs(expected - actual) > eps) fail("$what: expected $expected ± $eps, was $actual")
    }

    /** A target [d] m away in the direction (x, y, z) of ship space (nose -Z, identity rotation). */
    private fun at(x: Double, y: Double, z: Double, d: Double = 1e6) {
        val l = kotlin.math.sqrt(x * x + y * y + z * z)
        target.set(x / l * d, y / l * d, z / l * d)
    }

    private fun compute(speed: Double = 0.0, limit: Double = 1e9, radius: Double = 100.0): Boolean =
        ap.compute(pos, rot, target, radius, speed, limit, h, FlightLaw.ROBLOX.cruiseCap)

    @Test
    fun aTargetToTheRightIsAFullYawAndARightRoll() {
        assertTrue(ap.engage(3, 1))
        at(1.0, 0.0, 0.0)
        assertTrue(compute())
        near("cursor x", 0.95, ap.cursorX.toDouble(), 1e-6)
        near("cursor y", 0.0, ap.cursorY.toDouble(), 1e-6)
        near("roll (right is -1)", -1.0, ap.roll.toDouble(), 1e-6)
        assertEquals(Autopilot.ALIGNING, ap.status)
        assertEquals(0.0, ap.throttle)
    }

    @Test
    fun aTargetStraightBehindPullsUp() {
        ap.engage(3, 1)
        at(0.0, 0.0, 1.0)
        assertTrue(compute())
        near("cursor x", 0.0, ap.cursorX.toDouble(), 1e-6)
        near("cursor y (up)", -0.95, ap.cursorY.toDouble(), 1e-6)
    }

    @Test
    fun aTargetTenDegreesAboveGivesAGentlePitch() {
        ap.engage(3, 1)
        val a = Math.toRadians(10.0)
        at(0.0, sin(a), -cos(a))
        assertTrue(compute())
        near("cursor x", 0.0, ap.cursorX.toDouble(), 1e-6)
        near("cursor y", -0.7585, ap.cursorY.toDouble(), 2e-4)
        assertEquals(0f, ap.roll, "no roll under 20 degrees")
        // the dead zone compensation: applyDeadzone(0.2 + 0.8 m) = m^1.7, a pitch command of +0.543
        val out = applyDeadzone(ap.cursorX, ap.cursorY, FlightConfig.Cursor.DEADZONE, FlightConfig.Rotation.INPUT_CURVE, Vec3())
        near("pitch command", 0.543, -out.y.toDouble(), 1e-3)
    }

    @Test
    fun targetsBelowArePushedNotRolledOver() {
        ap.engage(3, 1)
        at(0.3, -1.0, -0.2)
        assertTrue(compute())
        assertTrue(ap.cursorY > 0f, "push the nose down")
        assertTrue(abs(ap.roll) < 1f, "a short roll into the pitch plane: ${ap.roll}")
    }

    @Test
    fun theStopSpeedFollowsTheBrakingDistance() {
        // Luna (R 647, Roblox): 179.6 m/s at 1 km surface distance, 40.6 km/s at 100 km
        fun desired(surf: Double): Double {
            ap.engage(3, 1)
            at(0.0, 0.0, -1.0, 647.0 + surf)
            // at speed 0 it cruises; the lever asks for the stop speed (reference: no limit, 3 Mm/s cap)
            compute(radius = 647.0)
            return ShipThrust.targetSpeed(ap.throttle, 3e6, 260.0)
        }
        near("1 km", 179.6, desired(1000.0), 0.2)
        near("100 km", 40_608.0, desired(100_000.0), 60.0)
        assertEquals(Autopilot.CRUISING, ap.status)
        // the speed limit caps the wish
        ap.engage(3, 1)
        at(0.0, 0.0, -1.0, 647.0 + 100_000.0)
        compute(radius = 647.0, limit = 500.0)
        near("limited", 500.0, ShipThrust.targetSpeed(ap.throttle, 500.0, 260.0), 0.5)
    }

    @Test
    fun tooFastBrakesAndArrivingDisengages() {
        ap.engage(3, 1)
        at(0.0, 0.0, -1.0, 647.0 + 1000.0)
        assertTrue(compute(speed = 400.0, radius = 647.0))
        assertEquals(Autopilot.BRAKING, ap.status)
        assertEquals(0.0, ap.throttle)
        // within 0.35 R of the surface at 25 m/s or less: arrived
        at(0.0, 0.0, -1.0, 647.0 + 226.0)
        assertFalse(compute(speed = 25.0, radius = 647.0))
        assertEquals(Autopilot.ARRIVED, ap.status)
        assertFalse(ap.engaged)
        assertFalse(compute(), "off: nothing to fly")
    }

    @Test
    fun engagingNeedsATarget() {
        assertFalse(ap.engage(-1, -1))
        assertEquals(Autopilot.NO_TARGET, ap.status)
        assertFalse(ap.engaged)
        assertTrue(ap.engage(2, 0))
        assertEquals(Autopilot.ENGAGED, ap.status)
        ap.disengage(Autopilot.MANUAL_OVERRIDE)
        assertEquals(Autopilot.MANUAL_OVERRIDE, ap.status)
        assertEquals(-1, ap.targetIndex)
    }

    @Test
    fun aRotatedShipAimsInItsOwnFrame() {
        // nose turned 90 degrees left: a target straight ahead in the world (-Z) is to the right
        rot.setAxisAngle(0f, 1f, 0f, (Math.PI / 2).toFloat())
        ap.engage(3, 1)
        at(0.0, 0.0, -1.0)
        compute()
        assertTrue(ap.cursorX > 0.9f, "cursor right: ${ap.cursorX}")
    }
}
