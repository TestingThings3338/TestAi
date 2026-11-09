package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.content.StationMesh
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.world.WorldBuilder
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spawn rules of 01-core-loop.md 2.1.4-2.1.5 and 4.6 (9.4 SpawnRulesTest). */
class SpawnRulesTest {
    private val system = WorldBuilder.sol()

    private fun dist(a: DVec3, b: DVec3): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun forward(q: Quat) = q.rotate(0f, 0f, -1f, Vec3())

    @Test
    fun solArrivalAtScale3000() {
        // Sol: danger radius 92,000 studs -> 276,000 km at S = 3000; safe = 1.15 x danger
        val p = SpawnRules.arrivalPoint(92_000.0 * 3000.0, 30_000.0 * 3000.0, DVec3())
        assertNear(0f, p.x.toFloat(), 1e-3f)
        assertEquals(19.044e6, p.y, 1.0)
        assertEquals(-317.4e6, p.z, 1.0)
        // the larger of the two radii decides
        val q = SpawnRules.arrivalPoint(1.0e6, 2.0e6, DVec3())
        assertEquals(-2.3e6, q.z, 1e-6)
    }

    @Test
    fun starArrivalFacesTheStarClearOfBodies() {
        val pos = DVec3()
        val rot = Quat()
        SpawnRules.starArrival(system, pos, rot)
        val star = system.bodies[system.star]
        // Sol: danger = max(heat 37,000, shadow 92,000) studs x 3000; safe = 1.15 x danger
        assertEquals(0.0, pos.x, 1e-3)
        assertEquals(19.044e6, pos.y, 1.0)
        assertEquals(-317.4e6, pos.z, 1.0)
        val toStar = Vec3((star.pos.x - pos.x).toFloat(), (star.pos.y - pos.y).toFloat(), (star.pos.z - pos.z).toFloat()).normalize()
        assertTrue(forward(rot).dot(toStar) > 0.9999f, "faces the star")
        for (b in system.bodies) assertTrue(dist(pos, b.pos) >= 1.5 * b.radius, "clear of ${b.name}")
    }

    @Test
    fun clearOfBodiesPushesOutAlongTheSameRay() {
        val b = system.bodies[system.bodyIndex("Earth")]
        // 1.4 r on +X: pushed to 3 r on +X
        val p = DVec3(b.pos.x + 1.4 * b.radius, b.pos.y, b.pos.z)
        SpawnRules.clearOfBodies(system, p)
        assertEquals(b.pos.x + 3.0 * b.radius, p.x, 1.0)
        assertEquals(b.pos.y, p.y, 1e-3)
        // 1.6 r: left alone
        val q = DVec3(b.pos.x, b.pos.y - 1.6 * b.radius, b.pos.z)
        SpawnRules.clearOfBodies(system, q)
        assertEquals(b.pos.y - 1.6 * b.radius, q.y, 1e-6)
        // the very centre: straight up to 3 r
        val c = DVec3(b.pos.x, b.pos.y, b.pos.z)
        SpawnRules.clearOfBodies(system, c)
        assertEquals(b.pos.y + 3.0 * b.radius, c.y, 1.0)
    }

    @Test
    fun stationSpawnIsInFrontOfTheMouthFacingTheStation() {
        for (st in system.stations) {
            val pos = DVec3()
            val rot = Quat()
            SpawnRules.stationSpawn(st, pos, rot)
            val toStation = Vec3((st.pos.x - pos.x).toFloat(), (st.pos.y - pos.y).toFloat(), (st.pos.z - pos.z).toFloat()).normalize()
            assertTrue(forward(rot).dot(toStation) > 0.9999f, "${st.name}: faces the station root")
            // on the slot's side, out past the mouth
            val port = DVec3()
            val dir = Vec3()
            SpawnRules.stationPort(st, port, dir, Vec3())
            val out = (pos.x - port.x) * dir.x + (pos.y - port.y) * dir.y + (pos.z - port.z) * dir.z
            assertEquals(SpawnRules.STATION_SPAWN_DISTANCE, out, 1e-3, st.name)
        }
    }

    @Test
    fun dockedPoseParksTheHullCentreInTheSlot() {
        val spec = ShipSpec.STANDARD
        for (st in system.stations) {
            val pos = DVec3()
            val rot = Quat()
            SpawnRules.dockedPose(st, spec, pos, rot)
            val port = DVec3()
            val dir = Vec3()
            SpawnRules.stationPort(st, port, dir, Vec3())
            // nose into the slot (against the outward direction)
            val f = forward(rot)
            assertTrue(f.dot(dir) < -0.9999f, "${st.name}: nose in")
            // the hull's centre on the slot axis, parkDepth inside the entrance
            val c = rot.rotate(spec.centreX, spec.centreY, spec.centreZ, Vec3())
            val cx = pos.x + c.x - port.x
            val cy = pos.y + c.y - port.y
            val cz = pos.z + c.z - port.z
            val along = cx * dir.x + cy * dir.y + cz * dir.z
            assertEquals(-SpawnRules.slotParkDepth(st, spec), along, 1e-2, st.name)
            val off = sqrt(maxOf(0.0, cx * cx + cy * cy + cz * cz - along * along))
            assertTrue(off < 1e-2, "${st.name}: on the axis ($off m off)")
            val size = StationMesh.slotSize(st.model, Vec3())
            assertTrue(-along < size.z, "inside the tunnel")
        }
    }

    @Test
    fun aNewPilotStartsDockedAtTheHomeStation() {
        val i = SpawnRules.startStation(system)
        assertEquals("SOL-MERIDIAN", system.stations[i].id)
        assertEquals("MERIDIAN STATION", system.stations[i].name)
        val host = RecordingHost()
        val s = Session(system, Settings(), host, GearRig())
        s.startDocked()
        assertTrue(s.mode.docked)
        assertEquals(PlayerState.DOCKED, s.mode.state)
        assertEquals(i, s.dockStation)
        val pos = DVec3()
        SpawnRules.dockedPose(system.stations[i], s.spec, pos, Quat())
        assertTrue(dist(pos, s.ship.pos) < 1e-6, "parked in the slot")
        assertEquals(0f, s.ship.speed)
        assertEquals(0f, s.throttle)
        // the services open with the reveal, not before
        assertEquals(-1, host.servicesOpened)
        s.afterReveal(welcome = true)
        assertEquals(i, host.servicesOpened)
    }

    @Test
    fun recoveryIsInFrontOfTheNearestStation() {
        val pos = DVec3()
        val rot = Quat()
        // a crash near ARES DEEP
        val crash = DVec3().set(system.stations[2].pos)
        crash.x += 5_000.0
        val used = SpawnRules.recoveryPose(system, crash, pos, rot)
        assertEquals(2, used)
        assertTrue(dist(pos, system.stations[2].pos) < 2_000.0)
    }
}

/** A [SessionHost] that records what the session asked for. */
class RecordingHost : SessionHost {
    var servicesOpened = -1
    var servicesClosed = 0
    var releases = 0
    val haptics = ArrayList<Int>()

    override fun openServices(station: Int) {
        servicesOpened = station
    }

    override fun closeServices() {
        servicesClosed++
    }

    override fun releaseControls() {
        releases++
    }

    override fun haptic(type: Int) {
        haptics += type
    }
}
