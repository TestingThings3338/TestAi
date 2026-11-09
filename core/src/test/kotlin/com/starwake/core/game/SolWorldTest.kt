package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.content.StationMesh
import com.starwake.core.input.InputFrame
import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sol at real scale ([SolData], DECISIONS 1) and the physics of a place: gravity by the inverse
 * square, the air's drag and heat, flown by a [Session] over smooth spheres (no terrain attached).
 */
class SolWorldTest {
    private val system = StarSystem.sol()
    private val host = RecordingHost()
    private val input = InputFrame()

    private fun body(name: String) = system.bodies.first { it.name == name }

    private fun index(name: String) = system.bodies.indexOfFirst { it.name == name }

    private fun dist(a: DVec3, b: DVec3): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** A flight in Sol with the lever at zero (a free-flight start sets it to 30 %). */
    private fun session(): Session = Session(system, null, Settings(), host, GearRig()).also {
        it.startInSpace()
        it.setThrottle(0f)
    }

    private fun run(s: Session, seconds: Float, dt: Float = 1f / 60f) {
        var t = 0f
        while (t < seconds) {
            input.begin()
            s.frame(dt, input)
            t += dt
        }
    }

    @Test
    fun theRockyBodiesHaveTheirRealSizes() {
        assertEquals(6_360_000.0, body("EARTH").radius, 1.0)
        assertEquals(1_740_000.0, body("LUNA").radius, 1.0)
        assertEquals(3_390_000.0, body("MARS").radius, 1.0)
        assertEquals(384_000_000.0, dist(body("LUNA").pos, body("EARTH").pos), 384_000_000.0 * 0.01)
        assertEquals(18, system.bodies.size, "the star, 8 planets and 9 moons")
        assertEquals(StarSystem.BodyType.STAR, system.bodies[0].type)
        assertFalse(system.hasField, "no asteroid field in Sol (DECISIONS 13)")
    }

    @Test
    fun solidBodiesCarryTerrainAndGiantsDoNot() {
        for (name in listOf("MERCURY", "VENUS", "EARTH", "LUNA", "MARS", "IO", "EUROPA", "TITAN", "TRITON")) {
            assertTrue(body(name).hasTerrain, "$name has a surface")
        }
        for (name in listOf("SOL", "JUPITER", "SATURN", "URANUS", "NEPTUNE")) assertFalse(body(name).hasTerrain, "$name has none")
        assertEquals("Terrestrial World", body("EARTH").bodyClass)
        assertEquals(3, body("EARTH").seed, "planet seeds are their index from 1")
        assertEquals(94, body("LUNA").seed, "moon seeds are planet · 31 + moon")
        assertTrue(body("EARTH").cloudCover > 0.3f && body("LUNA").cloudCover == 0f)
    }

    @Test
    fun meridianHangsOverTheEarthAndIsTheHomeStation() {
        val i = SpawnRules.startStation(system)
        val st = system.stations[i]
        assertEquals("MERIDIAN STATION", st.name)
        assertEquals(index("EARTH"), st.parent)
        assertEquals(19_800_000.0, dist(st.pos, body("EARTH").pos), 1000.0)
        // the slot looks towards the planet: a launch flies out with the Earth ahead
        val port = DVec3()
        val out = Vec3()
        SpawnRules.stationPort(st, port, out, Vec3())
        val e = body("EARTH").pos.relativeTo(st.pos, Vec3()).normalize()
        assertTrue(out.dot(e) > 0.9f, "the slot faces the Earth: ${out.dot(e)}")
        assertEquals(6, system.stations.size)
        assertEquals(StationMesh.radius(st.model).toDouble(), st.radius, 1e-6)
        // home is in front of the station, in its zone
        assertTrue(dist(system.home, st.pos) < StarSystem.STATION_ZONE_RADIUS)
    }

    @Test
    fun gravityFollowsTheInverseSquare() {
        val earth = body("EARTH")
        assertEquals(9.81, earth.gravityAt(earth.radius), 1e-9)
        assertEquals(9.81 / 4, earth.gravityAt(2 * earth.radius), 1e-9)
        assertEquals(9.81, earth.gravityAt(0.5 * earth.radius), 1e-9, "no stronger inside")
        assertEquals(0.165 * 9.81, body("LUNA").gravityAt(body("LUNA").radius), 1e-9)
    }

    @Test
    fun theAirThinsExponentiallyAndEnds() {
        val earth = body("EARTH")
        assertEquals(1.225, earth.airDensityAt(0.0), 1e-9)
        assertEquals(1.225 / Math.E, earth.airDensityAt(earth.airScaleHeight), 1e-9)
        assertEquals(0.0, earth.airDensityAt(150_000.0), 0.0)
        assertEquals(0.0, body("LUNA").airDensityAt(0.0), 0.0, "airless")
        assertTrue(body("MARS").airDensityAt(0.0) < 0.05 && body("VENUS").airDensityAt(0.0) > 50.0)
    }

    @Test
    fun withFlightAssistOffTheShipFallsAtTheLocalGravity() {
        val s = session()
        val earth = index("EARTH")
        s.over(earth, 0.0, 0.0, 100_000.0)
        s.toggleFlightAssist()
        run(s, 1f)
        val g = body("EARTH").gravityAt(body("EARTH").radius + 100_000.0)
        assertEquals(-g, s.here.radialSpeed.toDouble(), 0.3, "falling at ${s.here.radialSpeed} m/s after 1 s")
        assertEquals(g, s.gravity.toDouble(), 0.05)
    }

    @Test
    fun flightAssistHoldsTheShipAgainstGravity() {
        val s = session()
        s.over(index("EARTH"), 0.0, 0.0, 50_000.0)
        val before = s.here.altitude
        run(s, 3f)
        assertTrue(abs(s.here.altitude - before) < 1.0, "sank ${before - s.here.altitude} m with Flight Assist on")
        assertTrue(s.ship.speed < 0.5f)
    }

    @Test
    fun aCircularOrbitHoldsItsHeight() {
        val s = session()
        val earth = body("EARTH")
        s.over(index("EARTH"), 0.0, 0.0, 300_000.0)
        s.toggleFlightAssist()
        val r = earth.radius + 300_000.0
        val v = sqrt(earth.gravityAt(r) * r).toFloat()
        // level, along the nose
        val nose = Vec3()
        s.ship.rot.rotate(0f, 0f, -1f, nose)
        s.ship.vel.set(nose).scale(v)
        run(s, 120f, 1f / 30f)
        assertEquals(300_000.0, s.here.altitude, 3_000.0, "orbit height after two minutes at $v m/s")
        assertEquals(v, s.ship.speed, v * 0.01f)
    }

    @Test
    fun theAirSlowsAFastShip() {
        val s = session()
        s.over(index("EARTH"), 0.0, 0.0, 5_000.0)
        s.toggleFlightAssist()
        val nose = Vec3()
        s.ship.rot.rotate(0f, 0f, -1f, nose)
        s.ship.vel.set(nose).scale(1000f)
        run(s, 1f)
        assertTrue(s.ship.speed < 720f, "still ${s.ship.speed} m/s after a second in thick air")
        assertTrue(s.ship.speed > 300f, "drag is not a wall: ${s.ship.speed} m/s")
        assertTrue(s.airDensity > 0.5f)
    }

    @Test
    fun anEntryAtOrbitalSpeedGlowsAndHeatsTheHull() {
        val s = session()
        s.over(index("EARTH"), 0.0, 0.0, 55_000.0)
        s.toggleFlightAssist()
        val nose = Vec3()
        s.ship.rot.rotate(0f, 0f, -1f, nose)
        s.ship.vel.set(nose).scale(9000f)
        run(s, 1.5f)
        assertTrue(s.entryGlow > 0.4f, "glow ${s.entryGlow}")
        assertTrue(s.heat > 0.05f, "heat ${s.heat}")
        // a slow flight at the same height stays cold
        val slow = session()
        slow.over(index("EARTH"), 0.0, 0.0, 55_000.0)
        run(slow, 1.5f)
        assertEquals(0f, slow.entryGlow, 0.01f)
    }

    @Test
    fun theStarBurnsAHullThatComesTooCloseAndTheShipIsRecoveredAtAStation() {
        val s = session()
        val star = system.bodies[0]
        s.teleport(star.pos.x + star.radius * 1.05, star.pos.y, star.pos.z, 0f, 0f, -1f, true)
        var lost = false
        var t = 0f
        while (t < 120f && !lost) {
            input.begin()
            s.frame(1f / 30f, input)
            t += 1f / 30f
            lost = s.destruction.isActive()
        }
        assertTrue(lost, "the hull never burned: heat ${s.heat} hull ${s.hull}")
        run(s, 0.1f)
        assertEquals(Branch.DESTROYED, s.mode.branch)
        assertTrue(s.blackout > 0f)
        run(s, Session.WRECK_TIME + 0.5f)
        assertFalse(s.destruction.isActive())
        assertEquals(1f, s.hull, 0f)
        val near = SpawnRules.nearestStation(system, s.ship.pos)
        assertTrue(dist(s.ship.pos, system.stations[near].pos) < 5_000.0, "recovered in front of a station")
        assertTrue(s.message!!.startsWith("RECOVERED"))
    }

    @Test
    fun aFreshStartIsDockedAtMeridian() {
        val s = Session(system, null, Settings(), host, GearRig())
        s.startDocked()
        assertTrue(s.mode.docked)
        assertEquals("MERIDIAN STATION", system.stations[s.dockStation].name)
        assertEquals(index("EARTH"), s.here.nearestBody)
    }
}
