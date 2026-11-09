package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarSystemTest {
    private val system = StarSystem()
    private val local = ShipSpec.STANDARD.maxSpeed

    private fun at(x: Double, y: Double, z: Double) = DVec3(x, y, z)

    @Test
    fun homeIsInTheStationZoneWithLocalSpeed() {
        val l = system.locate(system.home, Vec3(), local, StarSystem.Locality())
        assertEquals(StarSystem.Zone.STATION_ZONE, l.zone)
        assertEquals(local, l.speedLimit)
        assertEquals("PLANET III", system.bodies[l.nearestBody].name)
    }

    @Test
    fun speedLimitGrowsWithDistanceAndCapsAtHalfLightSpeed() {
        val giant = system.bodies[1]
        var last = 0f
        // fly straight out from the giant's surface, away from everything
        for (alt in doubleArrayOf(1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 3e9)) {
            val p = at(giant.pos.x, giant.pos.y + giant.radius + alt, giant.pos.z)
            val l = system.locate(p, Vec3(0f, 1f, 0f), local, StarSystem.Locality())
            assertTrue(l.speedLimit >= last, "limit must not shrink with altitude: $alt -> ${l.speedLimit}")
            assertTrue(l.speedLimit <= StarSystem.MAX_SPEED)
            last = l.speedLimit
        }
        assertEquals(StarSystem.MAX_SPEED, last)
    }

    @Test
    fun departingIsFasterThanApproaching() {
        val giant = system.bodies[1]
        val p = at(giant.pos.x, giant.pos.y + giant.radius + 2e6, giant.pos.z)
        val away = system.locate(p, Vec3(0f, 1000f, 0f), local, StarSystem.Locality()).speedLimit
        val toward = system.locate(p, Vec3(0f, -1000f, 0f), local, StarSystem.Locality()).speedLimit
        assertTrue(away > toward * 1.5f, "away $away toward $toward")
    }

    @Test
    fun zonesNearAPlanet() {
        val p2 = system.bodies.first { it.name == "PLANET II" }
        val r = p2.radius
        fun zoneAt(alt: Double, radial: Float): StarSystem.Zone {
            // a unit direction from the planet, pointing below the orbital plane (away from the outpost)
            val h = sqrt(p2.pos.x * p2.pos.x + p2.pos.z * p2.pos.z)
            val n = sqrt(1.0 + 0.25)
            val ux = -p2.pos.x / h / n
            val uy = -0.5 / n
            val uz = -p2.pos.z / h / n
            val p = at(p2.pos.x + ux * (r + alt), p2.pos.y + uy * (r + alt), p2.pos.z + uz * (r + alt))
            val v = Vec3((ux * radial).toFloat(), (uy * radial).toFloat(), (uz * radial).toFloat())
            return system.locate(p, v, local, StarSystem.Locality()).zone
        }
        assertEquals(StarSystem.Zone.LOW_ALTITUDE, zoneAt(20_000.0, 0f))
        assertEquals(StarSystem.Zone.GRAVITY_WELL, zoneAt(r, 0f))
        assertEquals(StarSystem.Zone.APPROACH, zoneAt(r * 5, -50_000f))
        assertEquals(StarSystem.Zone.DEPARTURE, zoneAt(r * 5, 50_000f))
        assertEquals(StarSystem.Zone.OPEN_SPACE, zoneAt(r * 5, 0f))
    }

    @Test
    fun systemEdge() {
        val l = system.locate(at(0.0, 0.0, 2.9e9), Vec3(), local, StarSystem.Locality())
        assertEquals(StarSystem.Zone.SYSTEM_EDGE, l.zone)
    }

    @Test
    fun planetsCastShadows() {
        val dir = Vec3()
        // sunlit at home
        assertTrue(system.sunlight(system.home, dir) > 0.99f)
        // directly behind the giant (as seen from the star)
        val g = system.bodies[1]
        val d = sqrt(g.pos.x * g.pos.x + g.pos.y * g.pos.y + g.pos.z * g.pos.z)
        val behind = at(g.pos.x / d * (d + g.radius * 3), g.pos.y / d * (d + g.radius * 3), g.pos.z / d * (d + g.radius * 3))
        assertEquals(0f, system.sunlight(behind, dir))
        // the direction points at the star
        val toStar = Vec3(-behind.x.toFloat(), -behind.y.toFloat(), -behind.z.toFloat()).normalize()
        assertTrue(dir.dot(toStar) > 0.9999f)
    }

    @Test
    fun dynamicLimitLetsTheShipCruiseAndSlowDown() {
        val model = FlightModel(ShipSpec.STANDARD)
        val s = ShipState()
        val c = ControlInput().also { it.throttle = 1f }
        repeat(60 * 15) { model.step(s, c, 1f / 60f, StarSystem.MAX_SPEED) }
        assertTrue(s.speed > StarSystem.MAX_SPEED * 0.97f, "cruise ${s.speed}")
        assertTrue(s.speed <= StarSystem.MAX_SPEED * 1.0001f)
        // arriving: the limit collapses to local speed; the ship must slow down quickly and smoothly
        var t = 0f
        while (s.speed > local * 1.01f && t < 30f) {
            model.step(s, c, 1f / 60f, local)
            t += 1f / 60f
        }
        // braking is exponential (0.9 of the speed per second): ~15 s from 150 Mm/s even when the
        // limit collapses at once; in flight the approach law lowers it gradually
        assertTrue(t < 18f, "took $t s to slow from the cruise cap")
        assertTrue(s.speed <= local * 1.01f)
    }

    @Test
    fun aSmallHeadingChangeNeverMakesTheLimitJump() {
        val giant = system.bodies[1]
        val p = at(giant.pos.x, giant.pos.y + giant.radius + 2e6, giant.pos.z)
        var last = -1f
        // sweep the heading from 6 deg up to 6 deg down at 1.2 Mm/s, in 0.5 deg steps
        var deg = 6.0
        while (deg >= -6.0) {
            val a = Math.toRadians(deg)
            val v = Vec3((kotlin.math.cos(a) * 1.2e6).toFloat(), (kotlin.math.sin(a) * 1.2e6).toFloat(), 0f)
            val lim = system.locate(p, v, local, StarSystem.Locality()).speedLimit
            if (last > 0f) assertTrue(kotlin.math.abs(lim - last) / last < 0.05f, "jump at $deg deg: $last -> $lim")
            last = lim
            deg -= 0.5
        }
    }
}
