package com.starwake.core.game

import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.TestAsserts.assertSameRotation
import com.starwake.core.TestAsserts.assertVec
import com.starwake.core.math.PI_F
import com.starwake.core.math.Quat
import com.starwake.core.math.Rng
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlightModelTest {
    private val spec = ShipSpec.STANDARD
    private val model = FlightModel(spec)
    private val dt = 1f / 60f

    private fun ship(fa: Boolean = true) = ShipState().also { it.flightAssist = fa }

    /** Runs [seconds] of simulation; [each] is called after every step. */
    private fun fly(s: ShipState, c: ControlInput, seconds: Float, step: Float = dt, each: (ShipState) -> Unit = {}) {
        val n = Math.round(seconds / step)
        repeat(n) {
            model.step(s, c, step)
            each(s)
        }
    }

    private fun forward(s: ShipState) = s.rot.rotate(0f, 0f, -1f, Vec3())
    private fun right(s: ShipState) = s.rot.rotate(1f, 0f, 0f, Vec3())

    private fun cruise(fa: Boolean = true): Pair<ShipState, ControlInput> {
        val s = ship(fa)
        val c = ControlInput().also { it.throttle = 1f }
        fly(s, c, 10f)
        return s to c
    }

    // ---------------------------------------------------------------- spec sanity

    @Test
    fun standardSpecIsInternallyConsistent() {
        assertEquals("STANDARD", spec.name)
        // one probe at the origin: the sphere tests below see the plain collision sphere
        assertContentEquals(floatArrayOf(0f, 0f, 0f), spec.probes)
        assertEquals(spec.collisionRadius, spec.reach)
        assertTrue(spec.maxReverse < spec.maxSpeed && spec.maxSpeed < spec.boostSpeed)
        assertTrue(spec.boostCost in 0f..1f && spec.capacitorRecharge > 0f)
        assertTrue(spec.maxPitchRate > 0f && spec.maxYawRate > 0f && spec.maxRollRate > 0f)
    }

    // ---------------------------------------------------------------- throttle, FA on

    @Test
    fun fullThrottleReachesMaxSpeedAlongMinusZ() {
        val s = ship()
        val c = ControlInput().also { it.throttle = 1f }
        fly(s, c, 1f)
        assertNear(spec.accelForward, s.speed, 1.5f, "speed after 1 s at accelForward")
        fly(s, c, 9f)
        assertNear(spec.maxSpeed, s.speed, 1f, "speed after 10 s")
        assertVec(0f, 0f, -spec.maxSpeed, s.vel, 1f)
        assertNear(spec.maxSpeed, s.forwardSpeed, 1f)
        assertTrue(s.pos.z < -1500.0 && abs(s.pos.x) < 1e-3 && abs(s.pos.y) < 1e-3, "position ${s.pos}")
        assertSameRotation(Quat(), s.rot, 0f)
    }

    @Test
    fun partialThrottleCommandsAProportionalSpeed() {
        val s = ship()
        val c = ControlInput().also { it.throttle = 0.5f }
        fly(s, c, 10f)
        assertNear(spec.maxSpeed * 0.5f, s.speed, 0.5f)
        c.throttle = 7f // clamped
        fly(s, c, 10f)
        assertNear(spec.maxSpeed, s.speed, 0.5f)
    }

    @Test
    fun zeroThrottleBrakesToAStop() {
        val (s, c) = cruise()
        c.throttle = 0f
        fly(s, c, 1f)
        assertNear(spec.maxSpeed - spec.accelReverse, s.speed, 1.5f, "braking uses accelReverse")
        fly(s, c, 9f)
        assertTrue(s.speed < 1e-3f, "ship should stop, speed ${s.speed}")
        val z = s.pos.z
        fly(s, c, 1f)
        assertNear(z.toFloat(), s.pos.z.toFloat(), 1e-3f, "a stopped ship stays put")
    }

    @Test
    fun reverseThrottleTopsOutAtMaxReverse() {
        val s = ship()
        val c = ControlInput().also { it.throttle = -1f }
        fly(s, c, 10f)
        assertNear(spec.maxReverse, s.speed, 1f)
        assertNear(-spec.maxReverse, s.forwardSpeed, 1f)
        assertTrue(s.vel.z > 0f, "reversing moves along +Z")
        c.throttle = 1f
        fly(s, c, 10f)
        assertNear(spec.maxSpeed, s.forwardSpeed, 1f, "can go from full reverse to full ahead")
    }

    @Test
    fun strafeWithFlightAssistSlidesSideways() {
        val s = ship()
        val c = ControlInput().also { it.strafeX = 1f; it.strafeY = -1f }
        fly(s, c, 3f)
        assertVec(spec.maxStrafeSpeed, -spec.maxStrafeSpeed, 0f, s.vel, 0.5f)
        c.clearAxes()
        fly(s, c, 3f)
        assertTrue(s.speed < 1e-3f, "flight assist must cancel the drift, speed ${s.speed}")
    }

    @Test
    fun flightAssistCancelsSideSlipAfterATurn() {
        val (s, c) = cruise()
        c.yaw = 1f
        fly(s, c, 2f)
        c.yaw = 0f
        fly(s, c, 4f)
        val fwd = forward(s)
        val velDir = Vec3().set(s.vel).normalize()
        assertTrue(velDir.dot(fwd) > 0.9999f, "velocity $velDir should realign with the nose $fwd")
        assertNear(spec.maxSpeed, s.speed, 1f)
    }

    // ---------------------------------------------------------------- boost

    @Test
    fun boostExceedsMaxSpeedButNeverBoostSpeedAndCostsCapacitor() {
        val (s, c) = cruise()
        assertEquals(1f, s.capacitor)
        c.boost = true
        assertTrue(model.step(s, c, dt), "step must report the boost trigger")
        assertTrue(s.boosting)
        assertNear(1f - spec.boostCost, s.capacitor, 1e-6f)
        c.boost = false
        var maxSpeed = 0f
        var t = dt
        while (s.boosting) {
            model.step(s, c, dt)
            maxSpeed = maxOf(maxSpeed, s.speed)
            assertTrue(s.speed <= spec.boostSpeed + 1e-3f, "speed ${s.speed} above boostSpeed")
            assertTrue(s.engineOutput <= 1.6f + 1e-4f)
            t += dt
        }
        assertNear(spec.boostDuration, t, 2 * dt, "boost duration")
        assertTrue(maxSpeed > spec.maxSpeed + 100f, "boost should add real speed, peak $maxSpeed")
        assertNear(spec.boostSpeed, maxSpeed, 1f, "straight boost reaches boostSpeed")
    }

    @Test
    fun boostCannotRetriggerWhileActive() {
        val (s, c) = cruise()
        c.boost = true
        assertTrue(model.step(s, c, dt))
        val cap = s.capacitor
        val remaining = s.boostTime
        var retriggers = 0
        repeat(30) { if (model.step(s, c, dt)) retriggers++ }
        assertEquals(0, retriggers)
        assertEquals(cap, s.capacitor, "no extra cost and no recharge while boosting")
        assertNear(remaining - 30 * dt, s.boostTime, 1e-4f, "boost timer is not reset")
    }

    @Test
    fun boostNeedsEnoughCapacitor() {
        val (s, c) = cruise()
        s.capacitor = spec.boostCost - 0.01f
        c.boost = true
        assertFalse(model.step(s, c, dt))
        assertFalse(s.boosting)
    }

    @Test
    fun capacitorRechargesToFullAndNoFurther() {
        val (s, c) = cruise()
        c.boost = true
        model.step(s, c, dt)
        c.boost = false
        fly(s, c, spec.boostDuration + dt)
        assertFalse(s.boosting)
        val after = s.capacitor
        fly(s, c, 1f)
        assertNear(after + spec.capacitorRecharge, s.capacitor, 1e-3f, "recharge rate")
        fly(s, c, 30f)
        assertEquals(1f, s.capacitor)
    }

    @Test
    fun speedReturnsToMaxSpeedAfterBoost() {
        val (s, c) = cruise()
        c.boost = true
        model.step(s, c, dt)
        c.boost = false
        while (s.boosting) model.step(s, c, dt)
        assertTrue(s.speed > spec.maxSpeed + 100f)
        var prev = s.speed
        repeat(Math.round(3f / dt)) {
            model.step(s, c, dt)
            assertTrue(s.speed <= prev + 1e-3f, "speed must bleed off monotonically")
            prev = s.speed
        }
        assertNear(spec.maxSpeed, s.speed, 1f)
        assertVec(0f, 0f, -spec.maxSpeed, s.vel, 1f)
    }

    // ---------------------------------------------------------------- rotation signs

    private fun rotationSigns(fa: Boolean) {
        run {
            val s = ship(fa)
            fly(s, ControlInput().also { it.pitch = 1f }, 0.4f)
            val f = forward(s)
            assertTrue(f.y > 0.05f && abs(f.x) < 1e-4f, "pitch +1 must raise the nose (fa=$fa): $f")
        }
        run {
            val s = ship(fa)
            fly(s, ControlInput().also { it.yaw = 1f }, 0.4f)
            val f = forward(s)
            assertTrue(f.x > 0.02f && abs(f.y) < 1e-4f, "yaw +1 must turn the nose right (fa=$fa): $f")
        }
        run {
            val s = ship(fa)
            fly(s, ControlInput().also { it.roll = 1f }, 0.4f)
            val r = right(s)
            assertTrue(r.y < -0.05f, "roll +1 must lower the right wing (fa=$fa): $r")
            assertVec(0f, 0f, -1f, forward(s), 1e-4f, "rolling must not move the nose")
        }
    }

    @Test
    fun stickSignConventionsWithFlightAssist() = rotationSigns(true)

    @Test
    fun stickSignConventionsWithoutFlightAssist() = rotationSigns(false)

    @Test
    fun flightAssistHoldsTheCommandedRates() {
        val s = ship()
        val c = ControlInput().also { it.pitch = 1f; it.yaw = -1f; it.roll = 0.5f }
        fly(s, c, 2f)
        assertVec(spec.maxPitchRate, spec.maxYawRate, -0.5f * spec.maxRollRate, s.angVel, 1e-5f)
    }

    @Test
    fun releasingTheStickWithFlightAssistStopsRotationWithinHalfASecond() {
        val s = ship()
        val c = ControlInput().also { it.pitch = 1f; it.yaw = 1f; it.roll = 1f; it.throttle = 1f }
        fly(s, c, 2f)
        c.pitch = 0f; c.yaw = 0f; c.roll = 0f
        fly(s, c, 0.1f)
        assertTrue(s.angVel.length() > 0.1f, "some rotational inertia is expected (feel), got ${s.angVel}")
        fly(s, c, 0.4f)
        assertVec(0f, 0f, 0f, s.angVel, 0f, "rotation must have stopped 0.5 s after release")
        val q = Quat().set(s.rot)
        fly(s, c, 1f)
        assertSameRotation(q, s.rot, 1e-6f)
    }

    @Test
    fun withoutFlightAssistRotationPersists() {
        val s = ship(fa = false)
        val c = ControlInput().also { it.pitch = 1f; it.roll = -1f }
        fly(s, c, 0.2f)
        c.clearAxes()
        val w = Vec3().set(s.angVel)
        assertTrue(w.length() > 0.5f)
        val q = Quat().set(s.rot)
        fly(s, c, 1f)
        assertTrue(abs(q.dot(s.rot)) < 0.9f, "the ship must keep turning")
        fly(s, c, 2f)
        assertVec(w, s.angVel, 0f, "angular velocity must be conserved")
        // and is capped at the max rates
        c.roll = 1f
        fly(s, c, 3f)
        assertNear(-spec.maxRollRate, s.angVel.z, 1e-5f)
    }

    @Test
    fun withoutFlightAssistVelocityIsConservedWithZeroThrottle() {
        val s = ship(fa = false)
        s.vel.set(30f, -20f, 50f)
        s.angVel.set(0.3f, -0.2f, 0.9f) // tumbling
        val c = ControlInput()
        val seconds = 5f
        fly(s, c, seconds)
        assertVec(30f, -20f, 50f, s.vel, 0.01f, "Newtonian: velocity must not change")
        assertNear(30f * seconds, s.pos.x.toFloat(), 0.1f)
        assertNear(-20f * seconds, s.pos.y.toFloat(), 0.1f)
        assertNear(50f * seconds, s.pos.z.toFloat(), 0.1f)
    }

    @Test
    fun withoutFlightAssistThrottleIsRawThrust() {
        val s = ship(fa = false)
        val c = ControlInput().also { it.throttle = 1f }
        fly(s, c, 1f)
        assertNear(spec.accelForward, s.speed, 1f)
        c.throttle = 0f
        fly(s, c, 5f)
        assertNear(spec.accelForward, s.speed, 1e-2f, "no drag without flight assist")
    }

    @Test
    fun simulationIsFrameRateIndependent() {
        val results = listOf(1f / 30f, 1f / 60f, 1f / 120f).map { h ->
            val s = ship()
            val c = ControlInput().also { it.throttle = 1f }
            fly(s, c, 1.5f, h)
            c.pitch = 1f
            fly(s, c, 1f, h)
            s
        }
        println("speed after 1.5 s throttle + 1 s full pitch at 30/60/120 Hz: " + results.joinToString { "%.2f".format(it.speed) })
        for (s in results.drop(1)) {
            // Straight-line speed is exact at any rate; in a turn each step rotates the velocity
            // before FA removes the slip, so the speed bled per second is O(dt): ~1-1.6 m/s here.
            assertNear(results[0].speed, s.speed, 2.5f)
            val a = forward(results[0])
            val b = forward(s)
            assertTrue(a.dot(b) > 0.999f, "orientation differs between frame rates: $a vs $b")
        }
    }

    // ---------------------------------------------------------------- speed caps

    private fun randomFlight(fa: Boolean, seed: Long, boost: Boolean, strafe: Boolean, rotate: Boolean, seconds: Float, check: (ShipState, Float) -> Unit) {
        val rng = Rng(seed)
        val s = ship(fa)
        val c = ControlInput()
        var sinceBoost = 1000f
        val steps = Math.round(seconds / dt)
        for (i in 0 until steps) {
            if (i % 20 == 0) {
                if (rotate) {
                    c.pitch = rng.signed(); c.yaw = rng.signed(); c.roll = rng.signed()
                }
                c.throttle = rng.signed()
                if (strafe) {
                    c.strafeX = rng.signed(); c.strafeY = rng.signed()
                }
                c.boost = boost && rng.nextFloat() < 0.1f
            }
            model.step(s, c, dt)
            sinceBoost = if (s.boosting) 0f else sinceBoost + dt
            check(s, sinceBoost)
        }
    }

    @Test
    fun randomFlightWithFlightAssistAndNoBoostStaysUnderMaxSpeed() {
        var peak = 0f
        randomFlight(fa = true, seed = 77, boost = false, strafe = true, rotate = true, seconds = 600f) { s, _ ->
            peak = maxOf(peak, s.speed)
            assertTrue(s.speed <= spec.maxSpeed + 0.01f, "speed ${s.speed} above maxSpeed")
            assertTrue(s.vel.x.isFinite() && s.pos.z.isFinite())
        }
        println("FA on, random stick+strafe, no boost: peak %.2f m/s".format(peak))
    }

    @Test
    fun randomStraightBoostingWithFlightAssistStaysUnderBoostSpeed() {
        randomFlight(fa = true, seed = 78, boost = true, strafe = false, rotate = false, seconds = 300f) { s, sinceBoost ->
            assertTrue(s.speed <= spec.boostSpeed + 0.01f, "speed ${s.speed} above boostSpeed")
            if (sinceBoost > 3f) assertTrue(s.speed <= spec.maxSpeed + 0.01f, "speed ${s.speed} 3 s after a boost")
        }
    }

    @Test
    fun randomFlightWithoutFlightAssistBoostOrStrafeStaysUnderMaxSpeed() {
        randomFlight(fa = false, seed = 79, boost = false, strafe = false, rotate = true, seconds = 600f) { s, _ ->
            assertTrue(s.speed <= spec.maxSpeed + 0.01f, "speed ${s.speed} above maxSpeed")
        }
    }

    @Test
    fun randomInputsNeverExceedTheSpeedCaps() {
        for (fa in listOf(true, false)) {
            var peak = 0f
            randomFlight(fa = fa, seed = 77, boost = true, strafe = true, rotate = true, seconds = 600f) { s, sinceBoost ->
                peak = maxOf(peak, s.speed)
                assertTrue(s.speed <= spec.boostSpeed + 0.5f, "fa=$fa speed ${s.speed} above boostSpeed")
                if (sinceBoost > 5f) assertTrue(s.speed <= spec.maxSpeed + 0.5f, "fa=$fa speed ${s.speed} long after boost")
            }
        }
    }

    @Test
    fun boostingWhileTurningOrStrafingStaysUnderBoostSpeed() {
        for ((name, input) in listOf<Pair<String, (ControlInput) -> Unit>>(
            "pitch" to { it.pitch = 1f },
            "strafe" to { it.strafeX = 1f; it.strafeY = 1f },
        )) {
            val (s, c) = cruise()
            input(c)
            c.boost = true
            var peak = 0f
            fly(s, c, spec.boostDuration) { peak = maxOf(peak, it.speed) }
            assertTrue(peak <= spec.boostSpeed + 0.5f, "boost + $name: peak speed $peak")
        }
    }

    @Test
    fun thrustAndStrafeWithoutFlightAssistIsCappedAtMaxSpeed() {
        val s = ship(fa = false)
        val c = ControlInput().also { it.throttle = 1f; it.strafeX = 1f; it.strafeY = 1f }
        fly(s, c, 60f)
        assertTrue(s.speed <= spec.maxSpeed + 0.5f, "speed ${s.speed}")
    }

    @Test
    fun boostWithoutFlightAssistIsCappedAtBoostSpeed() {
        val (s, c) = cruise(fa = false)
        c.boost = true
        model.step(s, c, dt)
        c.boost = false
        var peak = 0f
        fly(s, c, spec.boostDuration) { peak = maxOf(peak, it.speed) }
        assertTrue(peak <= spec.boostSpeed + 0.5f, "peak $peak")
        fly(s, c, 5f)
        assertTrue(s.speed <= spec.maxSpeed + 0.5f, "speed ${s.speed} 5 s after the boost")
    }

    // ---------------------------------------------------------------- collisions

    @Test
    fun collideSphereIgnoresDistantSpheres() {
        val s = ship()
        s.vel.set(0f, 0f, -100f)
        assertEquals(0f, model.collideSphere(s, 0.0, 0.0, -100.0, 50.0))
        assertVec(0f, 0f, -100f, s.vel, 0f)
        assertEquals(0.0, s.pos.z)
        // exactly touching is not a contact
        assertEquals(0f, model.collideSphere(s, 0.0, 0.0, -(50.0 + spec.collisionRadius), 50.0))
    }

    @Test
    fun collideSpherePushesOutAndRemovesInwardVelocity() {
        val s = ship()
        s.pos.set(0.0, 0.0, -5.0)
        s.vel.set(10f, 0f, -50f) // sliding forward into a rock ahead
        val impact = model.collideSphere(s, 0.0, 0.0, -20.0, 10.0)
        assertNear(50f, impact, 1e-4f, "impact speed is the inward normal speed")
        val minDist = 10f + spec.collisionRadius
        assertNear(-20f + minDist, s.pos.z.toFloat(), 1e-4f, "pushed out to the surface")
        assertNear(0f, s.pos.x.toFloat(), 0f)
        assertTrue(s.vel.z >= 0f, "no velocity into the rock may remain: ${s.vel}")
        assertNear(10f * 0.8f, s.vel.x, 1e-4f, "tangential speed is damped, not removed")
        // a second call at the surface is no longer a contact
        assertEquals(0f, model.collideSphere(s, 0.0, 0.0, -20.0, 10.0))
    }

    @Test
    fun collideSphereSeparatesEvenWhenMovingAway() {
        val s = ship()
        s.pos.set(3.0, 4.0, 0.0)
        s.vel.set(1f, 0f, 0f)
        val impact = model.collideSphere(s, 0.0, 0.0, 0.0, 5.0)
        assertEquals(0f, impact, "moving away is not an impact")
        val d = kotlin.math.sqrt(s.pos.x * s.pos.x + s.pos.y * s.pos.y + s.pos.z * s.pos.z)
        assertNear(5f + spec.collisionRadius, d.toFloat(), 1e-4f, "still pushed out along the contact normal")
        assertVec(1f, 0f, 0f, s.vel, 0f)
    }

    @Test
    fun collideSphereResolvesDeepPenetration() {
        val s = ship()
        s.pos.set(100.0, 0.5, -0.25) // almost at the centre of a 60 m rock
        s.vel.set(0f, -30f, 0f)
        model.collideSphere(s, 100.0, 0.0, 0.0, 60.0)
        val dx = s.pos.x - 100.0
        val d = kotlin.math.sqrt(dx * dx + s.pos.y * s.pos.y + s.pos.z * s.pos.z)
        assertNear(60f + spec.collisionRadius, d.toFloat(), 1e-3f)
        assertTrue(s.vel.y >= 0f)
    }

    @Test
    fun collideSphereResolvesTheExactCentre() {
        val s = ship()
        s.pos.set(10.0, 20.0, 30.0)
        model.collideSphere(s, 10.0, 20.0, 30.0, 20.0)
        val dx = s.pos.x - 10.0
        val dy = s.pos.y - 20.0
        val dz = s.pos.z - 30.0
        val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        assertTrue(d >= 20.0 + spec.collisionRadius - 1e-3, "ship still inside the rock (distance $d)")
    }

    @Test
    fun collisionKnockIsIndependentOfWorldOrientation() {
        fun knock(r: Quat): Vec3 {
            val s = ship()
            s.rot.set(r)
            r.rotate(0f, -50f, 0f, s.vel) // falling onto a rock below the ship (in ship space)
            val c = r.rotate(0f, -15f, 0f, Vec3()) // 15 m < 10 m rock + 6.2 m ship
            model.collideSphere(s, c.x.toDouble(), c.y.toDouble(), c.z.toDouble(), 10.0)
            return Vec3().set(s.angVel)
        }
        val a = knock(Quat())
        assertTrue(a.length() > 0.1f, "a 50 m/s impact from below should knock the ship: $a")
        val b = knock(Quat().setAxisAngle(0f, 0f, 1f, PI_F / 2f))
        assertVec(a, b, 1e-4f, "body-frame knock must not depend on world orientation")
    }

    @Test
    fun engineOutputTracksThrust() {
        val s = ship()
        val c = ControlInput().also { it.throttle = 1f }
        fly(s, c, 0.5f)
        assertTrue(s.engineOutput > 0.5f, "accelerating: engine output ${s.engineOutput}")
        fly(s, c, 10f)
        assertTrue(s.engineOutput in 0.1f..0.2f, "cruising idles the engine: ${s.engineOutput}")
        c.boost = true
        fly(s, c, 1f)
        assertTrue(s.engineOutput > 1.4f && s.engineOutput <= 1.6f, "boost: ${s.engineOutput}")
    }

    @Test
    fun resetRestoresTheInitialState() {
        val (s, _) = cruise()
        s.flightAssist = false
        s.capacitor = 0.1f
        s.boostTime = 1f
        s.angVel.set(1f, 2f, 3f)
        s.reset()
        assertEquals(0.0, s.pos.z)
        assertVec(0f, 0f, 0f, s.vel, 0f)
        assertVec(0f, 0f, 0f, s.angVel, 0f)
        assertSameRotation(Quat(), s.rot, 0f)
        assertTrue(s.flightAssist && s.capacitor == 1f && !s.boosting && s.speed == 0f && s.forwardSpeed == 0f)
    }

    @Test
    fun clearAxesKeepsTheThrottleLever() {
        val c = ControlInput().also {
            it.pitch = 1f; it.yaw = 1f; it.roll = 1f; it.strafeX = 1f; it.strafeY = 1f; it.boost = true; it.throttle = 0.7f
        }
        c.clearAxes()
        assertEquals(0.7f, c.throttle)
        assertTrue(c.pitch == 0f && c.yaw == 0f && c.roll == 0f && c.strafeX == 0f && c.strafeY == 0f && !c.boost)
    }

    @Test
    fun aLimitBelowTopSpeedIsAHardCapEvenWhileBoosting() {
        val s = ShipState()
        val c = ControlInput().also { it.throttle = 1f }
        repeat(60 * 6) { model.step(s, c, 1f / 60f, 200f) }
        assertTrue(s.speed <= 200.01f, "throttle ${s.speed}")
        c.boost = true
        repeat(60 * 3) {
            model.step(s, c, 1f / 60f, 200f)
            c.boost = false
            assertTrue(s.speed <= 200.01f, "boost ${s.speed}")
        }
    }

    @Test
    fun planetSizedSpheresHoldThePilotAtExactlyTheCollisionRadius() {
        // 6000 km radius: Float maths would be off by metres here
        val r = 6.0e6
        val s = ShipState()
        s.pos.set(0.0, r + 3.0, 0.0)
        s.vel.set(0f, -20f, 0f)
        model.collideSphere(s, 0.0, 0.0, 0.0, r)
        assertEquals(r + spec.collisionRadius, s.pos.y, 1e-6)
    }
}
