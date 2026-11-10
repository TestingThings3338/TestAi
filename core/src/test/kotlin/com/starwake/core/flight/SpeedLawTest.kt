package com.starwake.core.flight

import com.starwake.core.math.DVec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The speed law (02-flight.md 3.5, 9.4, 9.5; DECISIONS 2, 3): parity with Roblox at S = 1 (the
 * Earth table, the star, the edge, the trip script's times), the S-scaling of the far law and the
 * near-ground band of the port.
 */
class SpeedLawTest {
    private fun near(what: String, expected: Double, actual: Double, eps: Double) {
        if (abs(expected - actual) > eps) fail("$what: expected $expected ± $eps, was $actual")
    }

    // ------------------------------------------------------------------ the pure functions

    @Test
    fun cruiseTimeDependsOnTheHeading() {
        near("towards", 3.5, SpeedLaw.cruiseTime(1.0), 1e-12)
        near("level", 2.5, SpeedLaw.cruiseTime(0.0), 1e-12)
        near("away", 1.5, SpeedLaw.cruiseTime(-1.0), 1e-12)
        near("clamped", 3.5, SpeedLaw.cruiseTime(5.0), 1e-12)
    }

    @Test
    fun approachReachIsContinuousAtTheKnee() {
        // knee = 70 x 4 = 280 m: h / 2 below, sqrt(140 (h - 140)) above; both 140 m/s with slope 0.5
        near("below", 140.0, SpeedLaw.approachReach(280.0, 70.0), 1e-9)
        near("above", 140.0, SpeedLaw.approachReach(280.0 + 1e-9, 70.0), 1e-6)
        val slope = (SpeedLaw.approachReach(280.001, 70.0) - SpeedLaw.approachReach(280.0, 70.0)) / 0.001
        near("slope", 0.5, slope, 1e-3)
        near("500 m", 224.5, SpeedLaw.approachReach(500.0, 70.0), 0.05)
        assertEquals(0.0, SpeedLaw.approachReach(-5.0, 70.0))
    }

    @Test
    fun cruiseLimitShrinksTheGapByEEveryTimeConstant() {
        near("base at the edge", 1000.0, SpeedLaw.cruiseLimit(1000.0, 0.0, 3.5, 3e6), 1e-12)
        near("gap / T", 1000.0 + 35_000.0 / 3.5, SpeedLaw.cruiseLimit(1000.0, 35_000.0, 3.5, 3e6), 1e-9)
        near("capped", 3e6, SpeedLaw.cruiseLimit(1000.0, 1e12, 3.5, 3e6), 1e-12)
        near("negative gap", 260.0, SpeedLaw.cruiseLimit(260.0, -50.0, 1.5, 3e6), 1e-12)
    }

    @Test
    fun theEdgeLimitReachesEdgeSpeedItsMarginEarlyOnTheWayOut() {
        val field = 9_000_000.0
        // flying outward: 150 m/s already 2 km before the edge
        near("outward at 2 km", 150.0, SpeedLaw.edgeLimit(field, field - 2000.0, 1.0, 3e6), 1e-9)
        near("outward at 9 km", 150.0 + 7000.0 / 3.5, SpeedLaw.edgeLimit(field, field - 9000.0, 1.0, 3e6), 1e-9)
        // flying inward: no margin, the departure time
        near("inward at 2 km", 150.0 + 2000.0 / 1.5, SpeedLaw.edgeLimit(field, field - 2000.0, -1.0, 3e6), 1e-9)
        // level: half the margin
        near("level at 2 km", 150.0 + 1000.0 / 2.5, SpeedLaw.edgeLimit(field, field - 2000.0, 0.0, 3e6), 1e-9)
        assertEquals(Double.POSITIVE_INFINITY, SpeedLaw.edgeLimit(0.0, 1.0, 1.0, 3e6))
    }

    @Test
    fun zoneLabelsAreRobloxs() {
        assertEquals("APPROACH", SpeedLaw.zoneLabel(LimitZone.CRUISE, 0.25))
        assertEquals("DEPARTURE", SpeedLaw.zoneLabel(LimitZone.CRUISE, -0.25))
        assertEquals("OPEN SPACE", SpeedLaw.zoneLabel(LimitZone.CRUISE, 0.2))
        assertEquals("OPEN SPACE", SpeedLaw.zoneLabel(LimitZone.OPEN, 1.0))
        assertEquals("GRAVITY WELL", SpeedLaw.zoneLabel(LimitZone.SHADOW, 1.0))
        assertEquals("HEAT ZONE", SpeedLaw.zoneLabel(LimitZone.HEAT, 1.0))
        assertEquals("LOW ALTITUDE", SpeedLaw.zoneLabel(LimitZone.SURFACE, 1.0))
        assertEquals("STATION ZONE", SpeedLaw.zoneLabel(LimitZone.STATION, 1.0))
        assertEquals("SYSTEM EDGE", SpeedLaw.zoneLabel(LimitZone.EDGE, 1.0))
        assertEquals("SURFACE", SpeedLaw.zoneLabel(LimitZone.LANDING, 1.0))
    }

    // ------------------------------------------------------------------ parity at S = 1: the Earth table of 3.5

    /** Roblox's Earth: barrier 2349 studs, surface 2120, 1 g, minApproach 14, at the origin. */
    private val earth = FlightWorlds.body("Earth", 2120.0, collision = 2349.0, gravityG = 1.0)
    private val sys1 = FlightWorlds.system(earth)
    private val roblox = GravityField(FlightLaw.ROBLOX)
    private val gs = GravityState()

    /** The limit [h] m above Earth's barrier flying at [speed] with heading cosine [cos] (+1 towards). */
    private fun limitAt(h: Double, cos: Double, speed: Double = 100.0): Pair<Double, LimitZone> {
        // the ship on +X, the velocity split into towards (-X) and level (+Y)
        val p = DVec3(2349.0 + h, 0.0, 0.0)
        val s = sqrt(maxOf(0.0, 1.0 - cos * cos))
        val v = DVec3(-cos * speed, s * speed, 0.0)
        roblox.getState(sys1, p, v, gs)
        return gs.speedLimit to gs.limitZone
    }

    @Test
    fun theEarthTableAtSEqualsOne() {
        val S = LimitZone.SURFACE
        val W = LimitZone.SHADOW
        val C = LimitZone.CRUISE
        // h, towards, level, away (02 3.5)
        data class Row(val h: Double, val towards: Double, val zt: LimitZone, val level: Double, val zl: LimitZone, val away: Double, val za: LimitZone)
        val rows = listOf(
            Row(0.0, 14.0, S, 14.0, S, 14.0, S),
            Row(28.0, 14.0, S, 14.0, S, 14.0, S),
            Row(100.0, 50.0, S, 50.0, S, 50.0, S),
            Row(280.0, 140.0, S, 140.0, S, 140.0, S),
            Row(500.0, 224.5, S, 250.0, S, 250.0, S),
            Row(1000.0, 347.0, S, 352.35, W, 352.35, W),
            Row(3000.0, 359.3, W, 359.3, W, 359.3, W),
            Row(5168.0, 540.2, W, 540.2, W, 540.2, W),
            Row(10_000.0, 886.4, W, 886.4, W, 886.4, W),
            Row(11_745.0, 1000.0, C, 1000.0, C, 1000.0, C),
            Row(20_000.0, 3359.0, C, 4302.0, C, 6503.0, C),
            Row(100_000.0, 26_216.0, C, 36_302.0, C, 59_837.0, C),
            Row(1_000_000.0, 283_359.0, C, 396_302.0, C, 659_837.0, C),
        )
        for (r in rows) {
            for ((cos, exp) in listOf(1.0 to (r.towards to r.zt), 0.0 to (r.level to r.zl), -1.0 to (r.away to r.za))) {
                val (lim, zone) = limitAt(r.h, cos)
                val tol = if (exp.first > 2000.0) 0.5 else 0.06
                near("Earth h ${r.h} cos $cos", exp.first, lim, tol)
                assertEquals(exp.second, zone, "zone at h ${r.h} cos $cos")
            }
        }
    }

    @Test
    fun theStarHoldsAtLeastOneKmPerSecondInItsHeatZone() {
        // Sol at S = 1: R 4800, shadow min(92,000, 37,000) = 37,000, floor 1000, minApproach 40
        val star = FlightWorlds.body("Sol", 4800.0, star = true, minApproach = 40f, shadowFactor = 4.0, heatRadius = 37_000.0, massShadowRadius = 92_000.0)
        val sys = FlightWorlds.system(star)
        val p = DVec3()
        val v = DVec3(0.0, 0.0, 0.0)
        // outside the approach zone (2.2 R = 10,560) the heat zone holds 1 km/s whatever the heading
        for (h in doubleArrayOf(11_000.0, 20_000.0, 36_000.0)) {
            p.set(4800.0 + h, 0.0, 0.0)
            roblox.getState(sys, p, v, gs)
            near("star at h $h", 1000.0, gs.speedLimit, 1e-9)
            assertEquals(LimitZone.HEAT, gs.limitZone, "h $h")
            near("heat at h $h", 1.0 - h / 37_000.0, gs.starHeat, 1e-12)
        }
        // inside it, leaving: h / 2 above the floor still lets the pilot go at 1 km/s
        p.set(4800.0 + 5000.0, 0.0, 0.0)
        roblox.getState(sys, p, DVec3(100.0, 0.0, 0.0), gs)
        near("leaving at 5 km", 1000.0, gs.speedLimit, 1e-9)
        assertEquals(LimitZone.HEAT, gs.limitZone)
        p.set(4800.0 + 37_000.0, 0.0, 0.0)
        roblox.getState(sys, p, v, gs)
        near("the heat zone's edge", 1000.0, gs.speedLimit, 1e-9)
        assertEquals(0.0, gs.starHeat)
        assertEquals(LimitZone.CRUISE, gs.limitZone)
    }

    @Test
    fun theLimitIsTheMinimumOverBodiesAndTheNearestBodyHasTheSmallestAltitude() {
        // a big planet and a small moon: the moon is nearer by centre, the planet by altitude
        val big = FlightWorlds.body("Big", 10_000.0, gravityG = 2.0)
        val moon = FlightWorlds.body("Moon", 100.0, x = 13_000.0, minApproach = 12f, shadowFactor = 4.0, gravityG = 0.2)
        val sys = FlightWorlds.system(big, moon)
        // 2,800 above Big (its centre is the nearer one by far), 100 above the moon
        val p = DVec3(12_800.0, 0.0, 0.0)
        val v = DVec3(100.0, 0.0, 0.0) // at the moon, away from Big
        roblox.getState(sys, p, v, gs)
        assertEquals(1, gs.body, "nearest by altitude: the moon (100 vs 2800)")
        assertEquals(100.0, gs.altitude, 1e-9)
        // the moon's approach zone limits: towards it h / 2 = 50 (Big alone would allow 1,400)
        near("moon's limit", 50.0, gs.speedLimit, 1e-9)
        assertEquals(1, gs.limitingBody)
        assertEquals(LimitZone.SURFACE, gs.limitZone)
        // 1 s to impact at 100 m/s
        near("time to impact", 1.0, gs.timeToImpact, 1e-9)
        near("closing", 100.0, gs.closingSpeed, 1e-9)
        // the pull comes from the nearest body only: the moon, 0.2 g at (100 / 200)²
        near("moon's pull", 0.2 * 0.25 * 60.0, gs.gravity.length(), 1e-9)
        // Big alone, the same spot: its own law
        roblox.getState(FlightWorlds.system(big), p, v, gs)
        near("Big's limit", 1400.0, gs.speedLimit, 1e-9)
    }

    @Test
    fun earthPullsSixtyAtTheBarrierInRoblox() {
        val p = DVec3(2349.0, 0.0, 0.0)
        roblox.getState(sys1, p, DVec3(), gs)
        near("60 m/s² per g at the barrier", 60.0, gs.gravity.length(), 1e-9)
        near("pointing at the centre", -60.0, gs.gravity.x, 1e-9)
        // FA cancels 78 %: 13.2 m/s² left
        near("FA residual", 13.2, gs.gravity.length() * (1.0 - FlightConfig.Gravity.FA_GRAVITY_COMPENSATION), 1e-9)
        // nothing pulls above 3.5 radii
        p.set(2349.0 * 4.6, 0.0, 0.0)
        roblox.getState(sys1, p, DVec3(), gs)
        assertEquals(0.0, gs.gravity.length())
    }

    @Test
    fun thePortPullsNineEightyOneAtThirtyFivePercent() {
        // DECISIONS 5: 9.81 m/s² per g, damped to 35 %, measured from the surface (no barrier)
        val e = FlightWorlds.body("Earth", 6.36e6, collision = 7.047e6, gravityG = 1.0)
        val sys = FlightWorlds.system(e)
        GravityField(FlightLaw.PORT).getState(sys, DVec3(6.36e6 + 1.0, 0.0, 0.0), DVec3(), gs)
        near("at the surface", 9.81 * 0.35, gs.gravity.length(), 1e-5)
        near("1 g", 1.0, gs.gravityG, 1e-5)
    }

    // ------------------------------------------------------------------ parity at S = 1: the trip script

    @Test
    fun tripTimesMatchTheRobloxScriptAtSEqualsOne() {
        // trip.py (02 9.4, scratch flightsim) at S = 1, start at the barrier at rest, no gravity:
        // to B's barrier, to B's shadow edge, the peak (frame-exact, ±2 frames of 1/60 s)
        data class Ref(val name: String, val d: Double, val ra: Double, val rb: Double, val ground: Double, val shadow: Double, val peak: Double)
        val refs = listOf(
            Ref("Earth->Venus", 150_950.0, 2349.0, 2239.0, 73.5833, 47.6000, 21_373.7468),
            Ref("Earth->Mars", 247_380.0, 2349.0, 1255.0, 64.8667, 50.6667, 38_405.5379),
            Ref("Uranus->Neptune", 1_661_430.0, 3741.0, 3635.0, 103.4333, 68.1500, 269_983.1488),
            Ref("Earth->Luna", 128_000.0, 2349.0, 647.0, 51.6667, 47.0333, 19_143.7902),
        )
        for (r in refs) {
            assertEquals(r.rb, FlightWorlds.barrier(when (r.name.substringAfter("->")) {
                "Venus" -> 2020.0
                "Mars" -> 1130.0
                "Neptune" -> 3284.0
                else -> 580.0
            }))
            val t = FlightWorlds.trip(FlightLaw.ROBLOX, r.d, r.ra, r.rb, 0.0)
            println("S = 1 ${r.name}: $t")
            near("${r.name} to the barrier", r.ground, t.ground, 2.0 / 60.0 + 1e-6)
            near("${r.name} to the shadow edge", r.shadow, t.shadow, 2.0 / 60.0 + 1e-6)
            near("${r.name} peak", r.peak, t.peak, r.peak * 1e-4)
        }
    }

    // ------------------------------------------------------------------ the S-classified law

    @Test
    fun theFarLawScalesWithS() {
        // v_S(S·h) = S·v_1(h): every far distance and speed x S, times and ratios kept (cap scaled too)
        val s = 3000.0
        val scaled = GravityField(FlightLaw(s, 3e6 * s, 60.0, 1.0, false))
        val big = FlightWorlds.body("Earth", 2120.0 * s, collision = 2349.0 * s)
        val sysS = FlightWorlds.system(big)
        for (h in doubleArrayOf(100.0, 1000.0, 5000.0, 11_745.0, 50_000.0, 1e6)) {
            for (cos in doubleArrayOf(1.0, 0.0, -1.0)) {
                val (v1, z1) = limitAt(h, cos)
                val sq = sqrt(maxOf(0.0, 1.0 - cos * cos))
                scaled.getState(sysS, DVec3(2349.0 * s + h * s, 0.0, 0.0), DVec3(-cos * 100.0, sq * 100.0, 0.0), gs)
                near("S x v at h $h cos $cos", s * v1, gs.speedLimit, s * v1 * 1e-9)
                assertEquals(z1, gs.limitZone)
            }
        }
    }

    @Test
    fun theNearBandIsAbsoluteBelow11_4Km() {
        assertEquals(4000.0 / 0.35, FlightConfig.Surface.NEAR_BAND, 1e-9)
        val port = GravityField(FlightLaw.PORT)
        val e = FlightWorlds.body("Earth", 6.36e6, collision = 7.047e6, gravityG = 1.0)
        val sys = FlightWorlds.system(e)
        fun at(h: Double, cos: Double, speed: Double = 100.0): Pair<Double, LimitZone> {
            val sq = sqrt(maxOf(0.0, 1.0 - cos * cos))
            port.getState(sys, DVec3(6.36e6 + h, 0.0, 0.0), DVec3(-cos * speed, sq * speed, 0.0), gs)
            return gs.speedLimit to gs.limitZone
        }
        // level: Roblox's landing-scene law clamp(0.35 h, 260, 4000)
        assertEquals(260.0 to LimitZone.LANDING, at(100.0, 0.0))
        near("0.35 h", 0.35 * 5000.0, at(5000.0, 0.0).first, 1e-9)
        near("4000 at the band's top", 4000.0, at(11_428.0, 0.0).first, 1.0)
        // descending: the closing part stoppable with the UNSCALED approach brake (70 m/s²)
        assertEquals(sqrt(140.0 * (5000.0 - 140.0)), at(5000.0, 1.0).first, 1e-9)
        assertEquals(LimitZone.SURFACE, at(5000.0, 1.0).second)
        assertEquals(14.0, at(10.0, 1.0).first, "the minimum approach speed at the ground")
        // 45 degrees down: the closing part counts, divided by the cosine
        near("oblique", 50.0 / 0.70710678, at(100.0, 0.70710678).first, 1e-6)
        // above the band the far law ×S: the approach zone's h / 2 and the scaled brake
        val (far, zone) = at(20_000.0, 0.0)
        near("far: h / 2", 10_000.0, far, 1e-9)
        assertEquals(LimitZone.SURFACE, zone)
        // the star keeps its far law to the surface (no near band): its heat zone's floor x S outside
        // its approach zone (2.2 R), the approach zone's h / 2 inside it
        val star = FlightWorlds.body("Sol", 1.44e7, star = true, minApproach = 40f, shadowFactor = 4.0, heatRadius = 1.11e8, massShadowRadius = 2.76e8)
        val sun = FlightWorlds.system(star)
        port.getState(sun, DVec3(1.44e7 + 4e7, 0.0, 0.0), DVec3(), gs)
        near("the star's floor x S", 3e6, gs.speedLimit, 1e-6)
        assertEquals(LimitZone.HEAT, gs.limitZone)
        port.getState(sun, DVec3(1.44e7 + 5000.0, 0.0, 0.0), DVec3(), gs)
        near("5 km above the star", 2500.0, gs.speedLimit, 1e-9)
    }

    @Test
    fun aSmallMoonsFarLawNeverAsksForMoreThanTwoSecondsToTheSurface() {
        // Deimos at S = 3000: R 450 km, shadow 4 R = 1.8 Mm, approach zone 2.2 R = 990 km. Roblox's
        // law hands its shadow over at 3 Mm/s and the approach zone then asks for a sixth of that,
        // faster than the brake follows; the port keeps the closing part within 2 s of the surface
        // outside the zone too (FlightLaw.shadowApproach)
        val moon = FlightWorlds.body("Deimos", 4.5e5, minApproach = 12f, shadowFactor = 4.0)
        val sys = FlightWorlds.system(moon)
        val port = GravityField(FlightLaw.PORT)
        val unguarded = GravityField(FlightLaw(3000.0, FlightLaw.HALF_LIGHT, 9.81, 0.35, nearBand = true, shadowApproach = false))
        fun limit(f: GravityField, h: Double, cos: Double): Double {
            val sq = sqrt(maxOf(0.0, 1.0 - cos * cos))
            f.getState(sys, DVec3(4.5e5 + h, 0.0, 0.0), DVec3(-cos * 1e5, sq * 1e5, 0.0), gs)
            return gs.speedLimit
        }
        // in the shadow, straight at the moon: h / 2 instead of the shadow law
        near("in the shadow", 1.5e6 / 2.0, limit(port, 1.5e6, 1.0), 1e-6)
        near("Roblox's shadow law", 3e6 * Math.pow(1.5e6 / 1.8e6, 0.75), limit(unguarded, 1.5e6, 1.0), 1e-3)
        assertEquals(LimitZone.SHADOW, gs.limitZone)
        // outside the shadow too, until the cruise law is lower (11.6 Mm out straight in)
        near("cruise zone", 5e6 / 2.0, limit(port, 5e6, 1.0), 1e-6)
        near("far out: the cruise law", SpeedLaw.cruiseLimit(3e6, 2e7 - 1.8e6, 3.5, 1.5e8), limit(port, 2e7, 1.0), 1e-3)
        // oblique: the closing part counts; level and departing: Roblox's law
        near("45 degrees", 1.5e6 / 2.0 / 0.70710678, limit(port, 1.5e6, 0.70710678), 1e-3)
        assertEquals(limit(unguarded, 1.5e6, 0.0), limit(port, 1.5e6, 0.0))
        assertEquals(limit(unguarded, 1.5e6, -1.0), limit(port, 1.5e6, -1.0))
        // into the approach zone without a jump the brake cannot follow: its approachReach is 1 % lower
        near("at the zone's edge", 9.9e5 / 2.0, limit(port, 9.9e5 + 1.0, 1.0), 1.0)
        near("inside the zone", SpeedLaw.approachReach(9.9e5 - 1.0, 2.1e5), limit(port, 9.9e5 - 1.0, 1.0), 1e-3)
        assertTrue(limit(port, 9.9e5 - 1.0, 1.0) > 0.98 * 9.9e5 / 2.0)
        // a big body's shadow (6 Mm or more) is not touched outside its approach zone: Luna
        val luna = FlightWorlds.body("Luna", 1.74e6, minApproach = 12f, shadowFactor = 4.0)
        val lunaSys = FlightWorlds.system(luna)
        for (h in doubleArrayOf(4e6, 6e6, 8e6, 3e7)) {
            port.getState(lunaSys, DVec3(1.74e6 + h, 0.0, 0.0), DVec3(-1e5, 0.0, 0.0), gs)
            val a = gs.speedLimit
            unguarded.getState(lunaSys, DVec3(1.74e6 + h, 0.0, 0.0), DVec3(-1e5, 0.0, 0.0), gs)
            assertEquals(gs.speedLimit, a, "Luna at $h m")
        }
    }

    @Test
    fun aDiveAtTheNearBandArrivesAtFourteenMetresPerSecond() {
        // 02 9.5: full throttle straight at the ground from 60 km (the far law), the 0.4 s brake
        // takes the band's edge, the ship reaches the surface at the minimum approach speed
        val t = FlightWorlds.trip(FlightLaw.PORT, 6.36e6 + 60_000.0 + 1.74e6, 1.74e6, 6.36e6, 0.0)
        println("dive: $t")
        near("arrival speed", 14.0, t.endSpeed, 0.5)
    }

    @Test
    fun portTripTimesMatchTheLawScript() {
        // trips.py (scratch): the port's law (R = surface, far x S, 0.5 c, the near band), 50 m
        // above the departure surface at rest, to B's surface (frame-exact, ±2 frames)
        data class Ref(val name: String, val d: Double, val ra: Double, val rb: Double, val ground: Double, val shadow: Double, val peak: Double)
        val s = 3000.0
        val refs = listOf(
            Ref("Earth->Venus", 150_950.0, 2120.0, 2020.0, 112.5500, 66.2500, 65_463_513.4),
            Ref("Earth->Mars", 247_380.0, 2120.0, 1130.0, 107.4000, 69.2500, 116_276_390.9),
            Ref("Earth->Luna", 128_000.0, 2120.0, 580.0, 98.0333, 65.6500, 58_317_965.0),
            Ref("Earth->Jupiter", 1_475_190.0, 2120.0, 9320.0, 160.7833, 92.9167, 1.5e8),
        )
        for (r in refs) {
            val t = FlightWorlds.trip(FlightLaw.PORT, r.d * s, r.ra * s, r.rb * s, 50.0, maxT = 600.0)
            println("S = 3000 ${r.name}: $t")
            near("${r.name} to the ground", r.ground, t.ground, 2.0 / 60.0 + 1e-6)
            near("${r.name} to the shadow edge", r.shadow, t.shadow, 2.0 / 60.0 + 1e-6)
            near("${r.name} peak", r.peak, t.peak, r.peak * 1e-4)
            near("${r.name} arrives at the minimum approach speed", 14.0, t.endSpeed, 1e-6)
            assertTrue(t.peak <= FlightLaw.HALF_LIGHT, "never faster than 0.5 c")
        }
        // DECISIONS 2: neighbours about a minute to the target's mass-shadow edge
        for (r in refs.take(3)) {
            val t = FlightWorlds.trip(FlightLaw.PORT, r.d * s, r.ra * s, r.rb * s, 50.0, maxT = 600.0)
            assertTrue(t.shadow in 52.0..72.0, "${r.name}: ${t.shadow} s to the shadow edge")
        }
    }
}
