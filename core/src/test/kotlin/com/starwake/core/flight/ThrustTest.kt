package com.starwake.core.flight

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import kotlin.math.abs
import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** `ShipThrust` (02-flight.md 3.4, the timings of 5.1): the lever, the bands, boost, cruise, caps, brake and gravity. */
class ThrustTest {
    private val dt = 1.0 / 60.0

    private fun near(what: String, expected: Double, actual: Double, eps: Double) {
        if (abs(expected - actual) > eps) fail("$what: expected $expected ± $eps, was $actual")
    }

    /** A VARROW at full tank in open space (no limit, Roblox's 3 Mm/s cap), nose -Z. */
    private inner class Flyer(profile: ShipProfile = ShipProfiles.VARROW, law: FlightLaw = FlightLaw.ROBLOX) {
        val rot = Quat()
        val vel = DVec3()
        val h = ShipHandling(profile)
        val t = ShipThrust(law)
        var time = 0.0

        /** One frame; the handling sees the previous frame's speed (as Roblox). */
        fun step(d: Double = dt) {
            h.update(d, vel.length(), 1.0, 0.0)
            t.update(d, rot, vel, h)
            time += d
        }

        fun fly(seconds: Double, d: Double = dt) {
            val end = time + seconds - 1e-9
            while (time < end) step(d)
        }

        val forward: Double get() = -vel.z
    }

    // ------------------------------------------------------------------ the lever map (3.4.1)

    @Test
    fun theLeverMapKeepsTheCombatBandLinear() {
        val top = 260.0
        val ref = 3e6
        // VARROW, reference 3 Mm/s: lever 26 % = 260 m/s, then exponential up to the reference
        near("0.26", 260.0, ShipThrust.targetSpeed(0.26, ref, top), 1e-9)
        val table = doubleArrayOf(0.30, 463.0, 0.40, 1820.0, 0.50, 6710.0, 0.60, 23_800.0, 0.70, 81_800.0, 0.80, 276_000.0, 0.90, 915_000.0, 1.00, 3_000_000.0)
        var i = 0
        while (i < table.size) {
            val v = ShipThrust.targetSpeed(table[i], ref, top)
            near("lever ${table[i]}", table[i + 1], v, table[i + 1] * 0.005)
            i += 2
        }
        // at or below 1 km/s of reference the lever is linear to it
        near("reference 500", 250.0, ShipThrust.targetSpeed(0.5, 500.0, top), 1e-9)
    }

    @Test
    fun throttleForInvertsTheLeverMap() {
        for (ref in doubleArrayOf(500.0, 3e6, 9e9)) {
            var t = 0.0
            while (t <= 1.0) {
                val v = ShipThrust.targetSpeed(t, ref, 260.0)
                near("throttleFor at ref $ref, t $t", t, ShipThrust.throttleFor(v, ref, 260.0), 1e-6)
                t += 0.05
            }
        }
        assertEquals(0.0, ShipThrust.throttleFor(0.0, 3e6, 260.0))
        assertEquals(1.0, ShipThrust.throttleFor(4e6, 3e6, 260.0))
        // the band is clamped to 0.05..0.95: a 1200 m/s TopSpeed keeps 5 % of the lever exponential
        near("band clamp", 950.0, ShipThrust.targetSpeed(0.95, 3e6, 1200.0), 1e-9)
        assertTrue(ShipThrust.targetSpeed(0.96, 3e6, 1200.0) > 960.0)
    }

    // ------------------------------------------------------------------ the lever (3.4.3)

    @Test
    fun theLeverRampsFullStopsAndClamps() {
        val f = Flyer()
        f.t.throttleAxis = 1.0
        f.fly(1.0)
        near("W held 1 s", 0.25, f.t.throttle, 1e-9)
        f.fly(5.0)
        assertEquals(1.0, f.t.throttle, "clamped at 100 %")
        f.t.throttleAxis = 0.0
        f.t.fullStopHeld = true
        f.fly(1.2 - 1e-9)
        assertTrue(abs(f.t.throttle) < 1e-9, "full stop: 1 -> 0 in 1.2 s (${f.t.throttle})")
        f.t.fullStopHeld = false
        f.t.throttleAxis = -1.0
        f.fly(3.0)
        assertEquals(-0.25, f.t.throttle, "clamped at -25 %")
        f.t.setThrottle(5.0)
        assertEquals(1.0, f.t.throttle)
    }

    @Test
    fun fullReverseReachesTheReverseSpeedIn52Seconds() {
        val f = Flyer()
        f.t.setThrottle(-0.25)
        var reached = -1.0
        while (f.time < 8.0) {
            f.step()
            if (reached < 0 && f.forward <= -130.0 + 1e-6) reached = f.time
        }
        near("time to -130 m/s", 5.2, reached, 0.02)
        near("reverse speed", -130.0, f.forward, 1e-6)
    }

    // ------------------------------------------------------------------ the combat band (FA on)

    @Test
    fun zeroToTopSpeedIn743Seconds() {
        val f = Flyer()
        f.t.setThrottle(0.26)
        var reached = -1.0
        while (f.time < 10.0) {
            f.step()
            if (reached < 0 && f.forward >= 260.0 - 1e-6) reached = f.time
        }
        near("time to 260 m/s", 7.43, reached, 0.02)
        assertFalse(f.t.cruising, "the lever's 26 % is the combat band")
    }

    @Test
    fun flightAssistKillsDriftWithDoubleSideThrust() {
        // drifting sideways at 40 m/s: without strafe input 2 x 20 m/s² (1 s to stop)
        val f = Flyer()
        f.vel.set(40.0, 0.0, 0.0)
        f.step()
        near("drift after a frame", 40.0 - 40.0 * dt, f.vel.x, 1e-9)
        f.fly(1.0 - dt)
        near("drift stopped after 1 s", 0.0, f.vel.x, 1e-6)
        // wanted strafing uses the real thrust: 20 m/s² up to StrafeSpeed 130
        f.t.lateralX = 1.0
        f.step()
        near("strafe accel", 20.0 * dt, f.vel.x, 1e-9)
        f.fly(10.0)
        near("strafe speed", 130.0, f.vel.x, 1e-6)
        // the full stop holds every axis at zero
        f.t.lateralX = 0.0
        f.t.setThrottle(1.0)
        f.t.fullStopHeld = true
        f.fly(4.0)
        assertTrue(f.vel.length() < 1e-9, "full stop: ${f.vel}")
    }

    // ------------------------------------------------------------------ boost

    @Test
    fun boostBurnsTwoSecondsWithAFourSecondCooldownFromIgnition() {
        val f = Flyer()
        f.t.setThrottle(0.26)
        f.fly(10.0)
        near("at TopSpeed", 260.0, f.forward, 1e-6)
        f.t.boostRequested = true
        f.step()
        assertTrue(f.t.boostStarted && f.t.boosting)
        f.t.boostStarted = false
        // 2.2 x 35 m/s² up to BoostSpeed 340: 80 m/s in 1.04 s
        var reached = -1.0
        val t0 = f.time
        while (f.time - t0 < 1.9) {
            f.step()
            if (reached < 0 && f.forward >= 340.0 - 1e-6) reached = f.time - t0 + dt
        }
        near("260 -> 340 m/s", 1.04, reached, 0.03)
        // a request while it burns or cools down is dropped, not queued
        f.t.boostRequested = true
        f.step()
        assertFalse(f.t.boostStarted)
        assertFalse(f.t.boostRequested, "dropped")
        f.fly(0.2)
        assertFalse(f.t.boosting, "2 s burn")
        // after the burn the excess decays: above 1.02 x top the ship is "cruising" towards the
        // lever's 260 m/s (τ 1.8 s), so a boost request is refused until it is back in the band
        f.fly(1.0)
        assertTrue(f.forward < 340.0 && f.forward > 265.2, "decaying: ${f.forward}")
        assertTrue(f.t.cruising, "above 1.02 x top with FA: the cruise brings it down")
        while (f.t.boostCooldown > 0.0) f.step()
        near("cooldown from ignition", 4.0, f.time - t0 + dt, 2 * dt + 1e-9)
        f.t.boostRequested = true
        f.step()
        assertFalse(f.t.boostStarted, "refused while still cruising down (the previous frame's flag)")
        while (f.t.cruising) f.step()
        f.t.boostRequested = true
        f.step()
        assertTrue(f.t.boostStarted, "ready again once back in the combat band")
    }

    @Test
    fun theExcessOverTopSpeedDecaysExponentiallyAboveAndLinearlyBelow1_02TopSpeed() {
        // with FA the speed above 1.02 x top is cruise towards the lever: τ 1.8 s towards 260
        val f = Flyer()
        f.t.setThrottle(0.26)
        f.vel.set(0.0, 0.0, -340.0)
        f.step()
        near("cruise down", 260.0 + 80.0 * kotlin.math.exp(-dt / 1.8), f.forward, 1e-6)
        // below it the FA band's retro thrust (25 m/s²) and the cap's (25 m/s²) both brake
        while (f.forward > 265.2) f.step()
        val v0 = f.forward
        f.step()
        near("FA band and cap below 1.02 top", v0 - 2 * 25.0 * dt, f.forward, 1e-6)
        // without FA only the cap acts ("cap outside cruise"): exponential above, 25 m/s² below
        val g = Flyer()
        g.t.flightAssist = false
        g.vel.set(0.0, 0.0, -340.0)
        g.step()
        near("FA off, exponential", 260.0 + 80.0 * kotlin.math.exp(-dt / 1.8), g.forward, 1e-6)
        while (g.forward > 265.2) g.step()
        val w0 = g.forward
        g.step()
        near("FA off, linear 25 m/s²", w0 - 25.0 * dt, g.forward, 1e-6)
        g.fly(2.0)
        near("down to TopSpeed", 260.0, g.forward, 1e-9)
    }

    @Test
    fun noBoostWhileCruisingOrOverloaded() {
        val f = Flyer()
        f.t.setThrottle(1.0)
        f.fly(12.0)
        assertTrue(f.t.cruising)
        f.t.boostRequested = true
        f.step()
        assertFalse(f.t.boostStarted, "no boost in cruise")
        assertFalse(f.t.boostRequested, "dropped")
        val heavy = Flyer()
        heavy.t.boostRequested = true
        heavy.h.update(0.0, 0.0, 1.0, 200.0)
        heavy.t.update(dt, heavy.rot, heavy.vel, heavy.h)
        assertFalse(heavy.t.boostStarted, "no boost overloaded")
    }

    @Test
    fun withoutFlightAssistABoostAtAZeroLeverDoesNothing() {
        val f = Flyer()
        f.t.flightAssist = false
        f.t.boostRequested = true
        f.fly(2.0)
        assertTrue(f.t.boostCooldown > 0.0, "it fired (and costs the cooldown)")
        assertEquals(0.0, f.vel.length(), "but pushes nothing at a zero lever")
        // with the lever it multiplies the forward thrust: 2.2 x 35 x 0.5
        f.t.setThrottle(0.5)
        f.fly(4.0)
        f.t.boostRequested = true
        f.step()
        val v0 = f.forward
        f.step()
        near("boosted Newton", 2.2 * 35.0 * 0.5 * dt, f.forward - v0, 1e-9)
    }

    // ------------------------------------------------------------------ cruise (5.1)

    @Test
    fun theOpenSpaceCruiseCurve() {
        // lever 100 % at t = 0, no bodies: the reference is Roblox's 3 Mm/s cap (02 5.1, ±1 %)
        val f = Flyer()
        f.t.setThrottle(1.0)
        // (7.43 s to TopSpeed is the combat band from rest at a 26 % lever; at 100 % the cruise takes
        // over at 0.98 x top, 7.28 s, and the doubling runs from there)
        val marks = doubleArrayOf(
            8.0, 474.0, 9.0, 1_130.0, 10.0, 2_680.0, 11.0, 6_380.0, 12.0, 15_200.0, 13.0, 36_100.0,
            14.0, 85_900.0, 15.0, 204_000.0, 16.0, 486_000.0, 17.0, 1_160_000.0, 18.0, 2_350_000.0, 20.0, 2_950_000.0,
        )
        var i = 0
        while (i < marks.size) {
            f.fly(marks[i] - f.time)
            near("speed at ${marks[i]} s", marks[i + 1], f.vel.length(), marks[i + 1] * 0.01)
            i += 2
        }
        assertTrue(f.t.cruising)
        // the felt push is capped at 1.5 g
        assertTrue(f.t.felt.length() <= 14.715 + 1e-9, "felt ${f.t.felt.length()}")
    }

    @Test
    fun inCruiseTheFlightDirectionFollowsTheNose() {
        val f = Flyer()
        f.t.setThrottle(1.0)
        f.fly(14.0)
        assertTrue(f.t.cruising)
        // turn the nose 90 degrees to the right: the velocity follows with τ 0.25 s
        f.rot.setAxisAngle(0f, 1f, 0f, (-Math.PI / 2).toFloat())
        fun off(): Double = Math.toDegrees(acos((f.vel.x / f.vel.length()).coerceIn(-1.0, 1.0)))
        f.fly(0.1)
        assertTrue(off() > 30.0, "after 0.1 s still ${off()} deg off")
        f.fly(1.4)
        assertTrue(off() < 1.0, "after 1.5 s ${off()} deg off")
    }

    @Test
    fun heavierShipsCruiseUpMoreSlowly() {
        fun speedAfter(fuel: Double): Double {
            val f = Flyer(ShipProfiles.MORVEN)
            f.t.setThrottle(1.0)
            while (f.time < 16.0) {
                f.h.update(dt, f.vel.length(), fuel, 0.0)
                f.t.update(dt, f.rot, f.vel, f.h)
                f.time += dt
            }
            return f.vel.length()
        }
        val full = speedAfter(1.0)
        val empty = speedAfter(0.0)
        assertTrue(empty > full * 1.2, "the empty MORVEN is quicker: $empty vs $full")
    }

    @Test
    fun withoutFlightAssistThereIsNoCruise() {
        val f = Flyer()
        f.t.setThrottle(1.0)
        f.fly(14.0)
        val v = f.vel.length()
        assertTrue(v > 50_000.0)
        f.t.flightAssist = false
        f.step()
        assertFalse(f.t.cruising)
        // out of cruise: the excess decays with τ 1.8 s, the full lever still pushing 35 m/s²
        near("decay", 260.0 + (v - 260.0) * kotlin.math.exp(-dt / 1.8), f.vel.length(), v * 1e-4)
        f.fly(30.0)
        // FA off still caps: with the lever at 100 % where the decay eats the thrust (top + ~1.8 x 35)
        val e = kotlin.math.exp(-dt / 1.8)
        near("FA off at full lever", 260.0 + 35.0 * dt * e / (1.0 - e), f.vel.length(), 0.05)
        f.t.setThrottle(0.0)
        f.fly(10.0)
        near("FA off at a zero lever: TopSpeed", 260.0, f.vel.length(), 1e-6)
    }

    // ------------------------------------------------------------------ the limit and the brake (3.4.4)

    @Test
    fun theLimitRisesAtOnceAndFallsSoftlyBelowOneKmPerSecond() {
        val t = ShipThrust(FlightLaw.ROBLOX)
        t.setSpeedLimit(500.0, false)
        t.advanceSpeedLimit(dt)
        assertEquals(500.0, t.speedLimit, "the first limit applies at once")
        t.setSpeedLimit(800.0, false)
        t.advanceSpeedLimit(dt)
        assertEquals(800.0, t.speedLimit, "a rising limit applies at once")
        t.setSpeedLimit(200.0, false)
        t.advanceSpeedLimit(1.6)
        near("τ 1.6 s", 200.0 + 600.0 * kotlin.math.exp(-1.0), t.speedLimit, 1e-9)
        t.setSpeedLimit(100.0, true)
        t.advanceSpeedLimit(dt)
        assertEquals(100.0, t.speedLimit, "the approach zone applies at once")
        // above MaxSpeed a falling limit applies at once too
        t.setSpeedLimit(5e5, false)
        t.advanceSpeedLimit(dt)
        t.setSpeedLimit(2e5, false)
        t.advanceSpeedLimit(dt)
        assertEquals(2e5, t.speedLimit)
        // and a fall from above 1 km/s to below it starts from 1 km/s
        t.setSpeedLimit(300.0, false)
        t.advanceSpeedLimit(1.6)
        near("from 1 km/s", 300.0 + 700.0 * kotlin.math.exp(-1.0), t.speedLimit, 1e-9)
    }

    @Test
    fun theGravityBrakeIs80MetresPerSecondSquaredBelowAndExponentialAbove() {
        val f = Flyer()
        f.t.flightAssist = false
        f.vel.set(0.0, 0.0, -250.0)
        f.t.setSpeedLimit(100.0, true)
        f.step()
        near("80 m/s² below 1 km/s", 250.0 - 80.0 * dt, f.vel.length(), 1e-9)
        f.fly(5.0)
        near("down to the limit", 100.0, f.vel.length(), 1e-9)
        // never below MinManoeuvreSpeed 12 m/s
        f.t.setSpeedLimit(1.0, true)
        f.fly(5.0)
        near("min manoeuvre", 12.0, f.vel.length(), 1e-9)
        // above 1 km/s: the excess over max(limit, 1000) decays with τ 0.4 s
        val g = Flyer()
        g.t.setThrottle(1.0)
        g.fly(13.0)
        val v = g.vel.length()
        g.t.setThrottle(0.0)
        g.t.setSpeedLimit(2000.0, false)
        g.step()
        // the cruise slows towards the lever first, then the brake: the result is at most the exponential
        assertTrue(g.vel.length() <= 2000.0 + (v - 2000.0) * kotlin.math.exp(-dt / 0.4) + 1e-6, "brake ${g.vel.length()} from $v")
    }

    @Test
    fun flightAssistCancels78PercentOfGravity() {
        val f = Flyer()
        f.t.flightAssist = false
        f.t.setGravity(0.0, -10.0, 0.0)
        f.step()
        near("FA off: all of it", -10.0 * dt, f.vel.y, 1e-12)
        val g = Flyer()
        g.t.setGravity(0.0, -10.0, 0.0)
        g.vel.set(0.0, -10.0, 0.0)
        // FA brings the vertical speed towards 0 at 2 x 22 m/s², then 22 % of the pull is added
        g.step()
        near("FA on: 22 %", -10.0 + 44.0 * dt - 2.2 * dt, g.vel.y, 1e-9)
        // a tiny residual reads as standing still
        val h = Flyer()
        h.t.setGravity(0.0, -1.0, 0.0)
        h.step()
        assertEquals(0.0, h.vel.length(), "below the 0.05 m/s cut-off")
    }

    @Test
    fun cancelVelocityAlongRemovesOnlyTheOutwardPart() {
        val v = DVec3(3.0, -4.0, 5.0)
        ShipThrust.cancelAlong(v, 0.0, -1.0, 0.0)
        assertEquals(0.0, v.y)
        assertEquals(3.0, v.x)
        ShipThrust.cancelAlong(v, 1.0, 0.0, 0.0)
        assertEquals(0.0, v.x)
        ShipThrust.cancelAlong(v, 0.0, 0.0, -1.0)
        assertEquals(5.0, v.z, "a component pointing away from the normal stays")
    }

    @Test
    fun theReferenceIsTheCruiseCapOfTheLaw() {
        // the port's 0.5 c: the lever's full travel asks for 150 Mm/s in open space
        val f = Flyer(law = FlightLaw.PORT)
        f.t.setThrottle(1.0)
        f.step()
        near("target", 1.5e8, f.t.targetForward, 1e-3)
        val r = Flyer(law = FlightLaw.ROBLOX)
        r.t.setThrottle(1.0)
        r.step()
        near("Roblox target", 3e6, r.t.targetForward, 1e-6)
    }
}
