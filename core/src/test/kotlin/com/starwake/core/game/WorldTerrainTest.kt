package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.input.InputFrame
import com.starwake.core.math.DVec3
import com.starwake.core.terrain.FloorHit
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The terrain body of the system ([WorldTerrain]) and the flight over its real ground: selection,
 * the height above the ground instead of above the reference sphere, and a landing on the gear.
 */
class WorldTerrainTest {
    private val system = StarSystem.sol()
    private val terrain = WorldTerrain(system, 0)
    private val host = RecordingHost()
    private val input = InputFrame()
    private val earth = system.bodies.indexOfFirst { it.name == "EARTH" }
    private val luna = system.bodies.indexOfFirst { it.name == "LUNA" }

    @AfterTest
    fun shutdown() = terrain.shutdown()

    private fun session(): Session = Session(system, null, Settings(), host, GearRig(), terrain).also {
        it.startInSpace()
        it.setThrottle(0f)
    }

    @Test
    fun theNearestSolidBodyBecomesTheTerrainBody() {
        assertEquals(-1, terrain.body)
        assertNull(terrain.surface(earth))
        // at MERIDIAN, 3.1 radii from the Earth's centre
        assertTrue(terrain.select(system.stations[0].pos))
        assertEquals(earth, terrain.body)
        assertNotNull(terrain.surface(earth))
        assertNull(terrain.surface(luna), "one terrain body at a time")
        assertFalse(terrain.select(system.stations[0].pos), "no change, no new planet")
        val g = terrain.generation
        // by the Moon
        val l = system.bodies[luna]
        assertTrue(terrain.select(DVec3(l.pos.x + l.radius * 2, l.pos.y, l.pos.z)))
        assertEquals(luna, terrain.body)
        assertTrue(terrain.generation > g)
        assertEquals("AIRLESS REGOLITH", terrain.profile(luna)!!.title)
        // deep space: no terrain body
        assertTrue(terrain.select(DVec3(1e9, 5e9, 1e9)))
        assertEquals(-1, terrain.body)
        assertNull(terrain.planet)
    }

    @Test
    fun theAltitudeIsTheHeightAboveTheRealGround() {
        val s = session()
        s.over(earth, 20.0, 40.0, 5_000.0)
        val surface = terrain.surface(earth)!!
        assertEquals(5_000.0, surface.altitudeAt(s.ship.pos), 0.5, "placed above the ground")
        assertEquals(5_000.0, s.here.altitude, 0.5, "the session's altitude is the height above the ground")
        assertEquals(StarSystem.Zone.LOW_ALTITUDE, s.here.zone)
        // the planet-scale layer lifts the ground off the reference sphere somewhere on the way round
        var off = 0.0
        for (lon in 0 until 360 step 20) {
            s.over(earth, 20.0, lon.toDouble(), 5_000.0)
            val b = system.bodies[earth]
            val dx = s.ship.pos.x - b.pos.x
            val dy = s.ship.pos.y - b.pos.y
            val dz = s.ship.pos.z - b.pos.z
            off = maxOf(off, abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - b.radius - 5_000.0))
        }
        assertTrue(off > 100.0, "the ground leaves the sphere by $off m at most")
    }

    @Test
    fun theShipSetsDownOnItsGearOnTheRealGround() {
        val s = session()
        val surface = run {
            s.over(earth, 0.0, 0.0, 1000.0)
            terrain.surface(earth)!!
        }
        // open water is a level floor (one can set down on it, as in Roblox): find some
        val hit = FloorHit()
        var lon = -1.0
        for (l in 0 until 360 step 5) {
            s.over(earth, 5.0, l.toDouble(), 25.0)
            if (surface.floorHit(s.ship.pos, 500.0, hit) && hit.water) {
                lon = l.toDouble()
                break
            }
        }
        assertTrue(lon >= 0.0, "no sea on the 5th parallel")
        s.over(earth, 5.0, lon, 25.0)
        s.landKey()
        assertTrue(s.mode.landing, "landing mode engages in the low band: ${s.debugState()}")
        var t = 0f
        while (t < 90f && !s.mode.landed) {
            input.begin()
            input.lateralY = -1f
            s.frame(1f / 60f, input)
            t += 1f / 60f
        }
        assertTrue(s.mode.landed, "never touched down: ${s.debugState()}")
        assertEquals(earth, s.landedBody)
        input.begin()
        s.frame(1f / 60f, input)
        assertEquals(s.spec.groundClearance.toDouble(), surface.altitudeAt(s.ship.pos), 0.05, "standing on the gear on the floor")
        assertEquals(1f, s.hull, 0f)
        // and up again
        s.landKey()
        var up = 0f
        while (up < 3f) {
            input.begin()
            s.frame(1f / 60f, input)
            up += 1f / 60f
        }
        assertFalse(s.mode.landed)
        assertTrue(surface.altitudeAt(s.ship.pos) > s.spec.groundClearance + 10.0, "lifted off: ${s.debugState()}")
    }

    @Test
    fun aDiveIntoGroundThatIsNotDrawnYetDoesNotWreckTheShip() {
        val s = session()
        s.over(earth, 30.0, 10.0, 300.0)
        s.toggleFlightAssist()
        val b = system.bodies[earth]
        // straight down at 120 m/s; no tile is resident (nothing renders here), so the ground is unseen
        s.ship.vel.set(
            ((b.pos.x - s.ship.pos.x) / 6.36e6 * 120.0).toFloat(),
            ((b.pos.y - s.ship.pos.y) / 6.36e6 * 120.0).toFloat(),
            ((b.pos.z - s.ship.pos.z) / 6.36e6 * 120.0).toFloat(),
        )
        var t = 0f
        while (t < 6f) {
            input.begin()
            s.frame(1f / 60f, input)
            t += 1f / 60f
        }
        val surface = terrain.surface(earth)!!
        assertTrue(surface.altitudeAt(s.ship.pos) > 0.0, "the hull stays above the ground: ${s.debugState()}")
        assertEquals(1f, s.hull, 0f, "a ship crashes only on ground it can see")
    }
}
