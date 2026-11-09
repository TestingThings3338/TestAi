package com.starwake.core.game

import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.TestAsserts.assertVec
import com.starwake.core.content.ShipCatalog
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Roblox camera composition of 01-core-loop.md 2.3 and its look-around (9.4 ShipCameraTest). */
class ShipCameraTest {
    /** A ship far out (double precision matters) and turned, so ship space ≠ world space. */
    private val shipPos = DVec3(1.1e9, -2.5e8, 7.0e8)
    private val shipRot = Quat().setAxisAngle(0.3f, 1f, -0.2f, 2.1f)

    /** Camera position in ship space after an update. */
    private fun eye(c: ShipCamera): Vec3 {
        val w = Vec3((c.pos.x - shipPos.x).toFloat(), (c.pos.y - shipPos.y).toFloat(), (c.pos.z - shipPos.z).toFloat())
        return shipRot.rotateInverse(w, Vec3())
    }

    /** Camera forward (-Z) in ship space. */
    private fun forward(c: ShipCamera): Vec3 {
        val w = c.rot.rotate(0f, 0f, -1f, Vec3())
        return shipRot.rotateInverse(w, Vec3())
    }

    private fun camera(lookX: Float = 0f, lookY: Float = 0f, external: Boolean = false): ShipCamera =
        ShipCamera().also {
            it.setExternal(external)
            it.setLook(lookX, lookY)
            it.update(shipPos, shipRot)
        }

    @Test
    fun cockpitAtTheSeatLookingAhead() {
        val c = camera()
        assertVec(0f, 0.5f, 0f, eye(c), 1e-4f, "the eye is SeatOffset above the ship origin")
        assertVec(0f, 0f, -1f, forward(c), 1e-5f)
    }

    @Test
    fun lookingRightSwingsTheEyeAroundTheNeck() {
        // look (90, 0): eye (0.3, 0.5, 0.3), looking along +X (01 2.3 worked example)
        val c = camera(90f, 0f)
        assertVec(0.3f, 0.5f, 0.3f, eye(c), 1e-4f)
        assertVec(1f, 0f, 0f, forward(c), 1e-5f)
        // look (0, 80): eye (0, 0.506, 0.593)
        val up = camera(0f, 80f)
        assertVec(0f, 0.5062f, 0.5926f, eye(up), 2e-4f)
        assertVec(0f, sin(80f * DEG2RAD), -cos(80f * DEG2RAD), forward(up), 1e-5f)
    }

    @Test
    fun externalViewPerShip() {
        val varrow = ShipCatalog.all[0]
        val c = ShipCamera()
        c.setExternalCamera(varrow.cameraX, varrow.cameraY, varrow.cameraZ, varrow.cameraPitchDeg)
        c.setExternal(true)
        c.update(shipPos, shipRot)
        assertVec(0f, 16f, 95f, eye(c), 2e-3f, "VARROW: (0, 16, 95)")
        assertVec(0f, sin(-8f * DEG2RAD), -cos(-8f * DEG2RAD), forward(c), 1e-5f, "pitched -8°")
        // looking round 180° outside: (0, 16, -95), facing back at the ship
        c.setLook(180f, 0f)
        c.update(shipPos, shipRot)
        assertVec(0f, 16f, -95f, eye(c), 2e-3f)
        val f = forward(c)
        assertTrue(f.z > 0.98f, "faces back (+Z): $f")
        // every ship's offset reaches the camera unchanged
        for (e in ShipCatalog.all) {
            c.setExternalCamera(e.cameraX, e.cameraY, e.cameraZ, e.cameraPitchDeg)
            c.setLook(0f, 0f)
            c.update(shipPos, shipRot)
            assertVec(e.cameraX, e.cameraY, e.cameraZ, eye(c), 2e-3f, e.name)
        }
    }

    @Test
    fun limitsPerViewAndClampOnSwitch() {
        val c = ShipCamera()
        c.setExternal(true)
        c.addLook(170f, 50f)
        assertEquals(170f, c.lookX, "outside: ±180")
        c.addLook(30f, 30f)
        assertEquals(180f, c.lookX)
        assertEquals(60f, c.lookY, "outside: up 60")
        // back into the cockpit: 170 -> 140 at once, up to 80 (the 60 stays)
        c.setLook(170f, -55f)
        c.setExternal(false)
        assertEquals(140f, c.lookX)
        assertEquals(-55f, c.lookY)
        c.addLook(0f, -20f)
        assertEquals(-70f, c.lookY, "cockpit: down 70")
        c.addLook(-400f, 200f)
        assertEquals(-140f, c.lookX)
        assertEquals(80f, c.lookY)
    }

    @Test
    fun theLookReturnsWithTauPoint12AndSnaps() {
        val c = ShipCamera()
        c.setLook(100f, -40f)
        // held: stays
        c.updateLook(0.5f, looking = true)
        assertEquals(100f, c.lookX)
        // released: after 0.12 s it is down to e^-1
        repeat(12) { c.updateLook(0.01f, looking = false) }
        assertNear(100f * exp(-1f), c.lookX, 1e-3f)
        assertNear(-40f * exp(-1f), c.lookY, 1e-3f)
        // and snaps to zero below 0.05°
        repeat(200) { c.updateLook(0.01f, looking = false) }
        assertEquals(0f, c.lookX)
        assertEquals(0f, c.lookY)
        // the debug hold keeps it
        c.setLook(30f, 0f)
        c.lookHold = true
        c.updateLook(1f, looking = false)
        assertEquals(30f, c.lookX)
    }

    @Test
    fun shakeAndHeadOffsetMoveTheCamera() {
        val c = ShipCamera()
        c.shake.set(0.1f, -0.2f, 0f)
        c.update(shipPos, shipRot)
        // camera space shake on an unturned cockpit camera = ship space
        assertVec(0.1f, 0.3f, 0f, eye(c), 1e-4f)
        c.shake.set(0f, 0f, 0f)
        c.headPos.set(0f, 0f, 0.05f)
        c.update(shipPos, shipRot)
        assertVec(0f, 0.5f, 0.05f, eye(c), 1e-4f)
    }
}
