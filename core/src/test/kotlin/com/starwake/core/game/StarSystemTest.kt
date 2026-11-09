package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.world.WorldBuilder
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pre-port zones and speed law on Sol (kept until the flight port brings ShipGravity). */
class StarSystemTest {
    private val system = WorldBuilder.sol()
    private val local = ShipSpec.STANDARD.maxSpeed
    private val earth = system.bodies[system.bodyIndex("Earth")]

    private fun at(x: Double, y: Double, z: Double) = DVec3(x, y, z)

    @Test
    fun dockedAtMeridianIsInTheStationZoneWithLocalSpeed() {
        val st = system.stations[SpawnRules.startStation(system)]
        val pos = DVec3()
        SpawnRules.dockedPose(st, ShipSpec.STANDARD, pos, Quat())
        val l = system.locate(pos, Vec3(), local, StarSystem.Locality())
        assertEquals(StarSystem.Zone.STATION_ZONE, l.zone)
        assertEquals(local, l.speedLimit)
        assertEquals("Earth", system.bodies[l.nearestBody].name)
    }

    @Test
    fun speedLimitGrowsWithDistanceAndCapsAtHalfLightSpeed() {
        var last = 0f
        // fly straight up out of the ecliptic from Earth's surface, away from everything
        for (alt in doubleArrayOf(1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 3e9)) {
            val p = at(earth.pos.x, earth.pos.y + earth.radius + alt, earth.pos.z)
            val l = system.locate(p, Vec3(0f, 1f, 0f), local, StarSystem.Locality())
            assertTrue(l.speedLimit >= last, "limit must not shrink with altitude: $alt -> ${l.speedLimit}")
            assertTrue(l.speedLimit <= StarSystem.MAX_SPEED)
            last = l.speedLimit
        }
        assertEquals(StarSystem.MAX_SPEED, last)
        assertEquals(1.5e8f, StarSystem.MAX_SPEED, "the cruise cap is 0.5 c (DECISIONS 2)")
    }

    @Test
    fun departingIsFasterThanApproaching() {
        val p = at(earth.pos.x, earth.pos.y + earth.radius + 2e6, earth.pos.z)
        val away = system.locate(p, Vec3(0f, 1000f, 0f), local, StarSystem.Locality()).speedLimit
        val toward = system.locate(p, Vec3(0f, -1000f, 0f), local, StarSystem.Locality()).speedLimit
        assertTrue(away > toward * 1.5f, "away $away toward $toward")
    }

    @Test
    fun aStationHoldsTheSpeedOnlyWhileTheShipClosesOnIt() {
        val st = system.stations[0].pos
        // the unit direction from Earth's centre out through MERIDIAN
        var ux = st.x - earth.pos.x
        var uy = st.y - earth.pos.y
        var uz = st.z - earth.pos.z
        val n = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= n; uy /= n; uz /= n
        fun limitAt(dist: Double, vx: Double, vy: Double, vz: Double) =
            system.locate(at(st.x + ux * dist, st.y + uy * dist, st.z + uz * dist), Vec3(vx.toFloat(), vy.toFloat(), vz.toFloat()), local, StarSystem.Locality()).speedLimit
        // 50 km beyond MERIDIAN: flying back at it, the station's clearance (46 km) holds the limit
        val closing = limitAt(50_000.0, -ux * 1000, -uy * 1000, -uz * 1000)
        assertTrue(closing < 46_000f * 0.61f, "closing on the station: $closing")
        // flying away from it, Earth's 13 Mm altitude sets the limit (Mm/s)
        val leaving = limitAt(50_000.0, ux * 1000, uy * 1000, uz * 1000)
        assertTrue(leaving > 4e6f, "leaving the station: $leaving")
        // inside the station zone the station's limit holds whatever the heading
        val inZone = limitAt(6_000.0, ux * 1000, uy * 1000, uz * 1000)
        assertTrue(inZone <= 2_000f * 0.61f + 1f, "in the zone: $inZone")
        // the hand-over is gradual: sweeping the heading from straight back to straight away, and
        // the distance out of the zone, the limit never jumps by more than 4x per 2 degrees / 100 m
        val px = -uz
        val pz = ux
        val pn = sqrt(px * px + pz * pz)
        var last = -1f
        var deg = 180.0
        while (deg >= 0.0) {
            val a = Math.toRadians(deg)
            val c = kotlin.math.cos(a)
            val sn = kotlin.math.sin(a)
            val lim = limitAt(50_000.0, (ux * c + px / pn * sn) * 1000, uy * c * 1000, (uz * c + pz / pn * sn) * 1000)
            if (last > 0f) assertTrue(lim / last < 4f && last / lim < 4f, "jump at $deg deg: $last -> $lim")
            last = lim
            deg -= 2.0
        }
        last = -1f
        var d = 7_000.0
        while (d < 14_000.0) {
            val lim = limitAt(d, ux * 1000, uy * 1000, uz * 1000)
            if (last > 0f) assertTrue(lim / last < 4f, "jump at $d m: $last -> $lim")
            last = lim
            d += 100.0
        }
    }

    @Test
    fun zonesNearAPlanet() {
        val r = earth.radius
        fun zoneAt(alt: Double, radial: Float): StarSystem.Zone {
            // a unit direction from Earth below the orbital plane (MERIDIAN hangs 24 degrees above it)
            val h = sqrt(earth.pos.x * earth.pos.x + earth.pos.z * earth.pos.z)
            val n = sqrt(1.0 + 0.25)
            val ux = -earth.pos.x / h / n
            val uy = -0.5 / n
            val uz = -earth.pos.z / h / n
            val p = at(earth.pos.x + ux * (r + alt), earth.pos.y + uy * (r + alt), earth.pos.z + uz * (r + alt))
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
    fun systemEdgeIsTheScaledFieldRadius() {
        // Ship.FieldRadius 9,000,000 studs x 3000 = 27 Gm
        assertEquals(2.7e10, system.edgeRadius, 1.0)
        val l = system.locate(at(0.0, 2.8e10, 0.0), Vec3(), local, StarSystem.Locality())
        assertEquals(StarSystem.Zone.SYSTEM_EDGE, l.zone)
    }

    @Test
    fun planetsCastShadows() {
        val dir = Vec3()
        // MERIDIAN stands a quarter turn from the Sun's direction: in daylight
        assertTrue(system.sunlight(system.stations[0].pos, dir) > 0.99f)
        // directly behind Earth (as seen from the star)
        val g = earth.pos
        val d = sqrt(g.x * g.x + g.y * g.y + g.z * g.z)
        val behind = at(g.x / d * (d + earth.radius * 3), g.y / d * (d + earth.radius * 3), g.z / d * (d + earth.radius * 3))
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
        val p = at(earth.pos.x, earth.pos.y + earth.radius + 2e6, earth.pos.z)
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
