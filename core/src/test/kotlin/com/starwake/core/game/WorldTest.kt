package com.starwake.core.game

import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.TestAsserts.assertSameRotation
import com.starwake.core.TestAsserts.assertUnit
import com.starwake.core.TestAsserts.assertVec
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.DVec3
import com.starwake.core.math.PI_F
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChaseCameraTest {
    private fun shipAt(q: Quat): ShipState = ShipState().also {
        it.pos.set(1000.0, -250.0, 5000.0)
        it.rot.set(q)
    }

    /** Camera position relative to the ship, in ship space. */
    private fun offsetInShipSpace(cam: ChaseCamera, ship: ShipState): Vec3 {
        val w = Vec3((cam.pos.x - ship.pos.x).toFloat(), (cam.pos.y - ship.pos.y).toFloat(), (cam.pos.z - ship.pos.z).toFloat())
        return ship.rot.rotateInverse(w, Vec3())
    }

    private val tilted = Quat().setAxisAngle(0.3f, 1f, -0.4f, 1.9f)

    @Test
    fun chaseModeSitsBehindAndAboveTheShip() {
        for (q in listOf(Quat(), tilted)) {
            val ship = shipAt(q)
            val cam = ChaseCamera()
            cam.snapTo(ship)
            assertSameRotation(ship.rot, cam.rot, 1e-6f)
            val o = offsetInShipSpace(cam, ship)
            assertTrue(o.z > 10f, "camera must be behind the ship (+Z in ship space): $o")
            assertTrue(o.y > 2f, "camera must be above the ship: $o")
            assertNear(0f, o.x, 1e-3f)
            assertVec(0f, 4.0f, 16.5f, o, 1e-3f)
        }
    }

    @Test
    fun theShipIsInViewSlightlyBelowCentre() {
        val ship = shipAt(tilted)
        val cam = ChaseCamera()
        cam.snapTo(ship)
        val view = cam.renderRotation(Quat())
        val toShip = Vec3((ship.pos.x - cam.pos.x).toFloat(), (ship.pos.y - cam.pos.y).toFloat(), (ship.pos.z - cam.pos.z).toFloat())
        val local = view.rotateInverse(toShip, Vec3()) // camera space, looking down -Z
        assertTrue(local.z < 0f, "ship must be in front of the camera: $local")
        val elevation = atan2(local.y, -local.z)
        assertTrue(elevation < 0f && elevation > -0.4f, "ship should sit a little below centre, elevation $elevation rad")
        assertNear(0f, local.x, 1e-3f)
    }

    @Test
    fun configureCirclesTheHullCentre() {
        val ship = shipAt(Quat())
        val cam = ChaseCamera()
        cam.configure(Vec3(0f, -2f, 10f), Vec3(0f, 16f, 85f), -8f * DEG2RAD)
        cam.snapTo(ship)
        // the offset is from the centre, which is itself offset from the origin
        assertVec(0f, 14f, 95f, offsetInShipSpace(cam, ship), 1e-3f)
        assertNear(kotlin.math.sqrt(16f * 16f + 85f * 85f), cam.viewDistance, 1e-3f)
    }

    @Test
    fun speedPullsTheCameraBack() {
        val ship = shipAt(Quat())
        val cam = ChaseCamera()
        cam.snapTo(ship)
        cam.update(ship, 1f / 60f, 1f, 220f)
        assertNear(16.5f + 3f, offsetInShipSpace(cam, ship).z, 1e-3f)
    }

    @Test
    fun rotationLagsThenCatchesUp() {
        val ship = shipAt(Quat())
        val cam = ChaseCamera()
        cam.snapTo(ship)
        ship.rot.setAxisAngle(0f, 1f, 0f, -PI_F / 2f) // ship snaps 90° right
        cam.update(ship, 1f / 60f, 0f, 220f)
        val d = abs(cam.rot.dot(ship.rot))
        assertTrue(d < 0.999f && cam.rot.dot(Quat()) < 0.9999f, "camera should be part-way through the turn (|dot| = $d)")
        repeat(120) { cam.update(ship, 1f / 60f, 0f, 220f) }
        assertSameRotation(ship.rot, cam.rot, 1e-5f)
        assertVec(0f, 4.0f, 16.5f, offsetInShipSpace(cam, ship), 1e-2f)
    }

    @Test
    fun shakeDisplacesThenDecays() {
        val ship = shipAt(Quat())
        val cam = ChaseCamera()
        cam.snapTo(ship)
        cam.addShake(2f)
        var maxDev = 0f
        repeat(30) {
            cam.update(ship, 1f / 60f, 0f, 220f)
            val o = offsetInShipSpace(cam, ship)
            maxDev = maxOf(maxDev, abs(o.x), abs(o.y - 4.0f))
        }
        assertTrue(maxDev > 0.1f, "shake should move the camera, max deviation $maxDev")
        repeat(300) { cam.update(ship, 1f / 60f, 0f, 220f) }
        assertVec(0f, 4.0f, 16.5f, offsetInShipSpace(cam, ship), 1e-3f)
    }
}

class OrbitCameraTest {
    private fun checkLooksAtTarget(cam: OrbitCamera) {
        val target = DVec3(12.0, -3.0, 400.0)
        val pos = DVec3()
        val rot = Quat()
        cam.apply(target, pos, rot)
        val to = Vec3((target.x - pos.x).toFloat(), (target.y - pos.y).toFloat(), (target.z - pos.z).toFloat())
        assertNear(cam.distance, to.length(), 1e-3f, "orbit distance")
        val fwd = rot.rotate(0f, 0f, -1f, Vec3())
        assertVec(to.normalize(), fwd, 1e-4f, "camera must look at the target (yaw ${cam.yaw}, pitch ${cam.pitch})")
        assertTrue(rot.rotate(0f, 1f, 0f, Vec3()).y > 0f, "camera must stay upright")
        assertUnit(rot)
    }

    @Test
    fun orbitCameraLooksAtTheTarget() {
        val cam = OrbitCamera()
        checkLooksAtTarget(cam)
        for (yaw in listOf(-3f, -1f, 0f, 0.5f, 2.35f, 6f)) for (pitch in listOf(-0.9f, -0.3f, 0f, 0.12f, 0.9f)) {
            cam.yaw = yaw
            cam.pitch = pitch
            checkLooksAtTarget(cam)
        }
    }

    @Test
    fun dragRotatesAndClampsPitch() {
        val cam = OrbitCamera()
        val yaw = cam.yaw
        cam.drag(100f, 0f, 500f)
        assertNear(yaw - 0.2f, cam.yaw, 1e-6f)
        assertEquals(0f, cam.idle)
        cam.drag(0f, 10_000f, 500f)
        assertEquals(0.9f, cam.pitch)
        cam.drag(0f, -10_000f, 500f)
        assertEquals(-0.9f, cam.pitch)
        checkLooksAtTarget(cam)
    }

    @Test
    fun flingCoastsAndIdleAutoSpinResumes() {
        val cam = OrbitCamera()
        cam.drag(0f, 0f, 500f)
        cam.fling(500f, 0f, 500f) // 1 rad/s to the left
        val yaw0 = cam.yaw
        repeat(60) { cam.update(1f / 60f) }
        val coasted = yaw0 - cam.yaw
        assertTrue(coasted in 0.25f..0.4f, "fling should coast and decay, moved $coasted rad")
        // after a long idle the camera spins on its own and returns to the default pitch
        cam.pitch = 0.8f
        repeat(60 * 30) { cam.update(1f / 60f) }
        val before = cam.yaw
        repeat(60) { cam.update(1f / 60f) }
        assertNear(cam.autoSpin, cam.yaw - before, 1e-3f)
        assertNear(0.28f, cam.pitch, 0.05f)
    }
}
