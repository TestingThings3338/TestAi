package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.flight.Autopilot
import com.starwake.core.flight.FlightConfig
import com.starwake.core.flight.LimitZone
import com.starwake.core.input.Action
import com.starwake.core.input.InputFrame
import com.starwake.core.world.WorldBuilder
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The flight port inside the session (02-flight.md section 2, 3.7 glue, 5.7): the autopilot and its
 * override rules, the collision response (slide, crash thresholds, the plain crash and its
 * respawn), heat death, the gear command, the near band on a real planet, and no allocation in
 * the flight frame.
 */
class FlightSessionTest {
    private val system = WorldBuilder.sol()
    private val host = RecordingHost()
    private val input = InputFrame()

    private fun session(): Session = Session(system, Settings(), host, GearRig()).also { it.startInSpace() }

    private fun fly(s: Session, frames: Int, prepare: (InputFrame) -> Unit = {}) {
        repeat(frames) {
            input.begin()
            prepare(input)
            s.frame(1f / 60f, input)
        }
    }

    private fun altitude(s: Session, body: Int): Double {
        val b = system.bodies[body].pos
        val dx = s.ship.pos.x - b.x
        val dy = s.ship.pos.y - b.y
        val dz = s.ship.pos.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz) - system.bodies[body].radius
    }

    // ------------------------------------------------------------------ the autopilot glue (3.7)

    @Test
    fun theAutopilotNeedsALockAndForcesFlightAssistOn() {
        val s = session()
        s.autopilotButton()
        assertFalse(s.autopilot.engaged)
        assertEquals(Autopilot.NO_TARGET, s.autopilot.status)
        assertEquals(1, s.voice.pending, "No target selected.")
        s.toggleFlightAssist()
        assertFalse(s.thrust.flightAssist)
        s.lock(Session.LOCK_BODY, system.bodyIndex("Luna"))
        s.autopilotButton()
        assertTrue(s.autopilot.engaged)
        assertTrue(s.thrust.flightAssist, "FA forced on: it brakes through the flight computer")
        fly(s, 30)
        assertTrue(s.autopilot.status == Autopilot.ALIGNING || s.autopilot.status == Autopilot.CRUISING, s.autopilot.status)
        s.autopilotButton()
        assertFalse(s.autopilot.engaged)
        assertEquals(Autopilot.STANDBY, s.autopilot.status)
    }

    @Test
    fun theStickKeysOrFlightAssistOffTakeOver() {
        val s = session()
        s.lock(Session.LOCK_BODY, system.bodyIndex("Luna"))
        fun engage() {
            s.autopilotButton()
            assertTrue(s.autopilot.engaged)
            fly(s, 5)
        }
        engage()
        // a resting thumb under the override deflection does not count
        fly(s, 5) { it.aimX = 0.5f }
        assertTrue(s.autopilot.engaged, "0.5 is not an override")
        fly(s, 1) { it.aimX = 0.51f }
        assertEquals(Autopilot.MANUAL_OVERRIDE, s.autopilot.status, "deflection 0.51")
        engage()
        fly(s, 1) { it.keyAxis = 0.2f }
        assertEquals(Autopilot.MANUAL_OVERRIDE, s.autopilot.status, "any key axis")
        engage()
        s.toggleFlightAssist()
        fly(s, 1)
        assertEquals(Autopilot.MANUAL_OVERRIDE, s.autopilot.status, "FA off")
        assertFalse(s.autopilot.engaged)
    }

    @Test
    fun theAutopilotFliesToLunaAndArrives() {
        // MERIDIAN -> Luna: ALIGNING, CRUISING (and BRAKING), ARRIVED 0.2 Luna radii above the surface
        val s = session()
        val luna = system.bodyIndex("Luna")
        s.lock(Session.LOCK_BODY, luna)
        s.autopilotButton()
        val seen = LinkedHashSet<String>()
        var n = 0
        while (s.autopilot.engaged && n < 60 * 400) {
            fly(s, 1)
            seen += s.autopilot.status
            n++
        }
        println("autopilot to Luna: ${seen.joinToString(" -> ")} in ${n / 60.0} s, ${altitude(s, luna) / 1000.0} km above Luna at ${s.ship.speed} m/s")
        assertEquals(Autopilot.ARRIVED, s.autopilot.status)
        assertTrue(Autopilot.ALIGNING in seen && Autopilot.CRUISING in seen, seen.toString())
        val r = system.bodies[luna].radius
        val alt = altitude(s, luna)
        assertTrue(alt <= r * FlightConfig.Autopilot.ARRIVE_DISTANCE && alt >= r * FlightConfig.Autopilot.STOP_ALTITUDE * 0.9, "arrived at $alt m")
        assertTrue(s.ship.speed <= FlightConfig.Autopilot.ARRIVE_SPEED + 1.0)
        assertEquals(0f, s.throttle, "no flying on with the autopilot's lever")
        assertTrue(n < 60 * 240, "took ${n / 60.0} s")
    }

    @Test
    fun dockingOrLandingTakesTheAutopilotOff() {
        val s = session()
        s.lock(Session.LOCK_STATION, 0)
        s.autopilotButton()
        assertTrue(s.autopilot.engaged)
        assertTrue(s.requestDocking(0))
        assertFalse(s.autopilot.engaged)
        val t = session()
        t.debugTeleportLow()
        t.lock(Session.LOCK_BODY, system.bodyIndex("Earth"))
        t.autopilotButton()
        assertTrue(t.autopilot.engaged)
        t.landKey()
        fly(t, 1)
        assertEquals(Autopilot.ATMOSPHERE, t.autopilot.status)
    }

    // ------------------------------------------------------------------ the near band and the ground (9.5, 5.7)

    @Test
    fun aDiveAtEarthComesDownAtLandingSpeedAndTheGearDeploys() {
        val s = session()
        val earth = system.bodyIndex("Earth")
        s.near(earth, 20_000.0)
        s.debugFace(earth)
        s.setThrottle(1f)
        var minAlt = Double.MAX_VALUE
        var gearAt = -1.0
        var maxBelow100 = 0.0
        assertFalse(s.gearWanted(), "gear up at 20 km")
        var n = 0
        while (n < 60 * 120) {
            if (n % 30 == 0) s.debugFace(earth)
            fly(s, 1)
            n++
            val alt = altitude(s, earth)
            minAlt = minOf(minAlt, alt)
            if (alt < 100.0) maxBelow100 = maxOf(maxBelow100, s.ship.speed)
            if (gearAt < 0.0 && s.gearWanted()) gearAt = alt
            if (alt < s.spec.reach + 0.5) break
        }
        println("dive: lowest %.1f m, gear down at %.1f m, at most %.1f m/s below 100 m".format(minAlt, gearAt, maxBelow100))
        assertFalse(s.mode.crashing, "no crash")
        assertTrue(minAlt > -1.0, "never through the surface")
        assertTrue(maxBelow100 <= 51.0, "the approach law near the ground: $maxBelow100 m/s")
        assertTrue(gearAt in 0.0..61.0, "the gear is commanded down below 60 m: $gearAt")
        assertEquals(LimitZone.SURFACE, s.gs.limitZone)
    }

    @Test
    fun hittingTheGroundFasterThan38MetresPerSecondCrashesAndRespawnsAtAStation() {
        val s = session()
        val earth = system.bodyIndex("Earth")
        s.near(earth, 300.0)
        // straight down at 60 m/s with Flight Assist off (the brake still applies: start inside the hull reach)
        s.toggleFlightAssist()
        val b = system.bodies[earth]
        val ux = (s.ship.pos.x - b.pos.x) / (b.radius + 300.0)
        val uy = (s.ship.pos.y - b.pos.y) / (b.radius + 300.0)
        val uz = (s.ship.pos.z - b.pos.z) / (b.radius + 300.0)
        s.debugDrop(0.5)
        s.ship.vel.set(-ux * 60.0, -uy * 60.0, -uz * 60.0)
        fly(s, 2)
        assertTrue(s.mode.crashing, "60 m/s into the ground")
        assertEquals(Session.CRASH_GROUND, s.crashCause)
        assertTrue(s.crashFade > 0f)
        // the plain crash: black at 0.3 s, respawn in front of the nearest station, back at 2.8 s
        fly(s, 30)
        assertTrue(s.crashFade >= 1f)
        val st = system.stations[SpawnRules.nearestStation(system, s.ship.pos)]
        val dx = s.ship.pos.x - st.pos.x
        val dy = s.ship.pos.y - st.pos.y
        val dz = s.ship.pos.z - st.pos.z
        assertTrue(sqrt(dx * dx + dy * dy + dz * dz) < 3000.0, "in front of a station")
        assertEquals(0.0, s.ship.speed)
        assertEquals(1.0, s.systems.fuelFraction)
        // flight input is blocked until the crash ends
        fly(s, 10) { it.leverSet = true; it.lever = 1f }
        assertEquals(0f, s.throttle)
        fly(s, 60 * 3)
        assertFalse(s.mode.crashing)
        assertEquals(0f, s.crashFade)
        assertEquals(1, s.crashes)
    }

    @Test
    fun aSlowContactSlidesAndALevelOneWithTheGearDownLands() {
        val s = session()
        s.debugTeleportLow()
        s.debugDrop(5.0)
        s.setThrottle(0f)
        // 10 m/s straight down, level, gear down (below 60 m): a touchdown, no crash
        val earth = s.gs.body
        val b = system.bodies[earth]
        val d = sqrt((s.ship.pos.x - b.pos.x).let { it * it } + (s.ship.pos.y - b.pos.y).let { it * it } + (s.ship.pos.z - b.pos.z).let { it * it })
        s.ship.vel.set(-(s.ship.pos.x - b.pos.x) / d * 10.0, -(s.ship.pos.y - b.pos.y) / d * 10.0, -(s.ship.pos.z - b.pos.z) / d * 10.0)
        var n = 0
        while (!s.mode.landed && n < 600) {
            fly(s, 1) { it.lateralY = -1f }
            n++
        }
        assertTrue(s.mode.landed, "touched down")
        assertFalse(s.mode.crashing)
        assertEquals(0.0, s.ship.speed)
    }

    @Test
    fun aGiantHoldsTheShipAtItsCloudTops() {
        val s = session()
        val jupiter = system.bodyIndex("Jupiter")
        s.near(jupiter, 200.0)
        s.debugFace(jupiter)
        s.setThrottle(1f)
        var minAlt = Double.MAX_VALUE
        var blocked = false
        repeat(60 * 20) {
            if (it % 30 == 0) s.debugFace(jupiter)
            fly(s, 1)
            minAlt = minOf(minAlt, altitude(s, jupiter))
            if (s.message == Session.LANDING_BLOCKED_GAS) blocked = true
        }
        assertFalse(s.mode.crashing, "no solid ground: the barrier holds")
        assertTrue(minAlt > -1.0, "never through the cloud tops: $minAlt")
        assertTrue(minAlt < 20.0, "the dive reached the cloud tops: $minAlt")
        assertTrue(blocked, "LANDING BLOCKED at the barrier")
    }

    // ------------------------------------------------------------------ stations (5.7)

    @Test
    fun ramMingAStationFasterThan45MetresPerSecondCrashesSlowerSlides() {
        val slow = session()
        slow.debugBehindStation(0, 400.0, 30f)
        fly(slow, 60 * 20)
        assertFalse(slow.mode.crashing, "30 m/s slides")
        assertTrue(slow.stationClearance() > -2.5f && slow.stationClearance() < 5f, "at the hull: ${slow.stationClearance()}")
        val fast = session()
        fast.debugBehindStation(0, 400.0, 120f)
        var crashed = false
        repeat(60 * 6) {
            fly(fast, 1)
            if (fast.mode.crashing) crashed = true
        }
        assertTrue(crashed, "120 m/s into the hull")
        assertEquals(Session.CRASH_STATION, fast.crashCause)
    }

    // ------------------------------------------------------------------ heat (3.6)

    @Test
    fun theShipBurnsUpAt200PercentHeat() {
        val s = session()
        s.systems.setHeat(1.99)
        fly(s, 1)
        assertFalse(s.mode.crashing)
        // Roblox's order: the systems cool the ship (about 0.003 a frame here), then the check
        s.systems.setHeat(2.0)
        fly(s, 1)
        assertFalse(s.mode.crashing, "cooled below 200 % before the check")
        s.systems.setHeat(2.01)
        fly(s, 1)
        assertTrue(s.mode.crashing)
        assertEquals(Session.CRASH_HEAT, s.crashCause)
        fly(s, 60)
        assertEquals(0.0, s.systems.heat, "the respawn cools the ship")
    }

    @Test
    fun aBoostHeatsTheShip() {
        val s = session()
        s.requestBoost()
        fly(s, 1)
        assertTrue(s.thrust.boosting)
        assertEquals(s.profile.boostHeat, s.systems.heat, 1e-3)
    }

    // ------------------------------------------------------------------ the attitude indicator (DECISIONS 15)

    @Test
    fun theAttitudeIndicatorShowsInTheAirBelow50KmOnAirlessBodiesAndWithTheGear() {
        val s = session()
        fly(s, 2)
        assertFalse(s.attitudeShown, "not in open space")
        // Earth: inside its air (26 km, a third of the 78 km limb)
        s.near(system.bodyIndex("Earth"), 20_000.0)
        fly(s, 2)
        assertTrue(s.attitudeShown, "20 km over Earth: in the air")
        s.near(system.bodyIndex("Earth"), 30_000.0)
        fly(s, 2)
        assertFalse(s.attitudeShown, "30 km over Earth: above the air")
        // Luna has no air: below 50 km
        s.near(system.bodyIndex("Luna"), 45_000.0)
        fly(s, 2)
        assertTrue(s.attitudeShown, "45 km over Luna")
        s.near(system.bodyIndex("Luna"), 55_000.0)
        fly(s, 2)
        assertFalse(s.attitudeShown, "55 km over Luna")
        // the gear out: always
        s.gearForce = true
        fly(s, 2)
        assertTrue(s.attitudeShown, "the gear is out")
        s.gearForce = null
        fly(s, 2)
        assertFalse(s.attitudeShown, "the gear up again (no rig model here: at once)")
    }

    // ------------------------------------------------------------------ the gear command (3.8)

    @Test
    fun theGearFollowsTheAltitudeOverSolidGroundAndTheRigTiming() {
        // the VARROW with its real gear rig (doors and legs)
        val assets = listOf(java.io.File("../app/src/main/assets"), java.io.File("app/src/main/assets")).first { java.io.File(it, "ships").isDirectory }
        val entry = com.starwake.core.content.ShipCatalog.all[0]
        val m = com.starwake.core.content.ShipModel.load(entry.id) { java.io.File(assets, it).readBytes() }
        assertTrue(m.hasDoors)
        val rig = GearRig().also { it.setModel(m, true) }
        val s = Session(system, Settings(), host, rig)
        s.setShip(entry, ShipSpec.fromModel(entry, m), m)
        s.startInSpace()
        s.setView(ShipView.EXTERNAL) // the cockpit moves it at once
        s.near(system.bodyIndex("Earth"), 2000.0)
        fly(s, 300)
        assertTrue(s.gearRig.stowed, "up at 2 km")
        s.debugDrop(30.0)
        assertTrue(s.gearWanted())
        // extend: doors first (0.6 s), then legs (1.6 s)
        fly(s, 36)
        assertEquals(0f, s.gearRig.doorT, 0.03f)
        assertTrue(s.gearRig.legT > 0.95f)
        fly(s, 96)
        assertEquals(1f, s.gearRig.downFraction, 1e-6f)
        // in the cockpit the gear moves at once
        s.near(system.bodyIndex("Earth"), 2000.0)
        s.setView(ShipView.COCKPIT)
        fly(s, 1)
        assertTrue(s.gearRig.stowed)
        // over a giant the gear stays up (no ground to stand on)
        s.near(system.bodyIndex("Jupiter"), 30.0)
        assertFalse(s.gearWanted())
    }

    // ------------------------------------------------------------------ input (DECISIONS 19)

    @Test
    fun theTouchLeverIsAbsoluteAndTheFullStopIsHeld() {
        val s = session()
        fly(s, 1) { it.leverSet = true; it.lever = 0.8f }
        assertEquals(0.8f, s.throttle)
        fly(s, 1) { it.leverSet = true; it.lever = -0.6f }
        assertEquals(-0.25f, s.throttle, "clamped at -25 %")
        fly(s, 1) { it.leverSet = true; it.lever = 1f }
        // the full stop runs the lever to zero in 1.2 s
        fly(s, 72) { it.hold(Action.FULL_STOP) }
        assertEquals(0f, s.throttle, 1e-4f)
        // the pad's W/S ramp: 25 %/s
        fly(s, 60) { it.throttleAxis = 1f }
        assertEquals(0.25f, s.throttle, 1e-3f)
    }

    // ------------------------------------------------------------------ allocation (9.7)

    @Test
    fun theFlightFrameDoesNotAllocate() {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        val s = session()
        s.lock(Session.LOCK_BODY, system.bodyIndex("Mars"))
        // warm up: every path once (cruise, autopilot, collisions, gear)
        s.setThrottle(1f)
        fly(s, 600) { it.aimX = 0.6f; it.keyAxis = 0.3f; it.lateralY = 0.5f }
        s.autopilotButton()
        fly(s, 300)
        val a = mx.currentThreadAllocatedBytes
        fly(s, 2000) { it.aimX = 0.3f }
        s.autopilotButton()
        fly(s, 2000) { it.aimY = -0.7f; it.leverSet = true; it.lever = 0.5f }
        val b = mx.currentThreadAllocatedBytes
        val per = (b - a) / 4000.0
        assertTrue(per < 16.0, "the flight frame allocates %.1f bytes per frame".format(per))
    }
}
