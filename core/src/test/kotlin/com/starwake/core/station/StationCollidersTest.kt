package com.starwake.core.station

import com.starwake.core.content.StationModel
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sphere queries against station colliders: exact contacts (normal, depth) on each Roblox shape,
 * the swept test, spinning groups and build stages, and the real models (the docking route through
 * the mouth is clear, floors and hulls are solid).
 */
class StationCollidersTest {
    private val assets: File = listOf(File("../app/src/main/assets"), File("app/src/main/assets")).first { File(it, "stations").isDirectory }
    private fun model(t: String) = StationModel.load(t) { File(assets, it).readBytes() }

    private fun cf(x: Float, y: Float, z: Float, yawDeg: Float = 0f): FloatArray {
        val a = Math.toRadians(yawDeg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        // rotation about Y, row-major (Roblox R00..R22)
        return floatArrayOf(x, y, z, c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }

    private fun one(shape: Int, frame: FloatArray, sx: Float, sy: Float, sz: Float, group: Int = 0) =
        StationColliders(intArrayOf(shape), intArrayOf(group), frame, floatArrayOf(sx, sy, sz))

    private fun near(e: Float, a: Float, tol: Float, what: String) = assertTrue(abs(e - a) <= tol, "$what: expected $e, got $a")

    @Test
    fun blockContactOutsideAndInside() {
        val c = one(StationColliders.SHAPE_BLOCK, cf(0f, 0f, 0f), 20f, 10f, 40f)
        val out = StationContact()
        // 3 m above the top face with a 5 m sphere: 2 m deep, normal up
        assertTrue(c.sphereContact(1f, 8f, 2f, 5f, null, out))
        near(2f, out.depth, 1e-4f, "depth")
        near(1f, out.ny, 1e-5f, "normal y")
        near(5f, out.py, 1e-4f, "contact on the top face")
        // just clear
        assertFalse(c.sphereContact(1f, 10.01f, 2f, 5f, null, out))
        // edge: diagonal normal
        assertTrue(c.sphereContact(13f, 8f, 0f, 5f, null, out))
        near(0.7071f, out.nx, 1e-3f, "edge normal x")
        near(0.7071f, out.ny, 1e-3f, "edge normal y")
        near(5f - sqrt(18f), out.depth, 1e-3f, "edge depth")
        // centre inside: out through the nearest face (y, 5 m away)
        assertTrue(c.sphereContact(0f, 1f, 0f, 2f, null, out))
        near(1f, out.ny, 1e-5f, "inside normal")
        near(4f + 2f, out.depth, 1e-4f, "inside depth = distance to the face + radius")
    }

    @Test
    fun rotatedBlock() {
        val c = one(StationColliders.SHAPE_BLOCK, cf(100f, 0f, 0f, 90f), 20f, 10f, 40f)
        val out = StationContact()
        // turned 90 degrees about Y: the 40 m side now runs along X
        assertTrue(c.sphereContact(100f + 21f, 0f, 0f, 2f, null, out))
        near(1f, out.nx, 1e-4f, "normal along +X")
        near(1f, out.depth, 1e-3f, "depth")
        assertFalse(c.sphereContact(100f, 0f, 12.1f, 2f, null, out))
        assertTrue(c.sphereContact(100f, 0f, 11.9f, 2f, null, out))
        near(1f, out.nz, 1e-4f, "normal along +Z")
    }

    @Test
    fun ballCylinderAndWedge() {
        val out = StationContact()
        val ball = one(StationColliders.SHAPE_BALL, cf(0f, 0f, 0f), 10f, 10f, 10f)
        assertTrue(ball.sphereContact(0f, 0f, 6f, 2f, null, out))
        near(1f, out.depth, 1e-4f, "ball depth")
        near(1f, out.nz, 1e-5f, "ball normal")
        // cylinder along X: length 20, diameter min(Y, Z) = 8
        val cyl = one(StationColliders.SHAPE_CYLINDER, cf(0f, 0f, 0f), 20f, 8f, 12f)
        assertTrue(cyl.sphereContact(0f, 5f, 0f, 2f, null, out))
        near(1f, out.depth, 1e-4f, "mantle depth (radius 4, not 6)")
        near(1f, out.ny, 1e-5f, "mantle normal")
        assertFalse(cyl.sphereContact(0f, 0f, 6.5f, 2f, null, out)) // Z side is round too: radius 4
        assertTrue(cyl.sphereContact(11f, 0f, 0f, 2f, null, out))
        near(1f, out.nx, 1e-5f, "cap normal")
        near(1f, out.depth, 1e-4f, "cap depth")
        // wedge 10 x 10 x 10: the slope runs from front-bottom (-Z, -Y) to back-top (+Z, +Y)
        val wedge = one(StationColliders.SHAPE_WEDGE, cf(0f, 0f, 0f), 10f, 10f, 10f)
        // above the cut-away front-top corner: only the slope is near
        assertTrue(wedge.sphereContact(0f, 3f, -3f, 5f, null, out))
        near(0f, out.nx, 1e-4f, "slope normal x")
        near(0.7071f, out.ny, 1e-3f, "slope normal y")
        near(-0.7071f, out.nz, 1e-3f, "slope normal z")
        near(5f - 6f * 0.7071f, out.depth, 1e-3f, "slope depth")
        // the full back face is solid up to the top
        assertTrue(wedge.sphereContact(0f, 4f, 6f, 2f, null, out))
        near(1f, out.nz, 1e-4f, "back face normal")
        near(1f, out.depth, 1e-3f, "back face depth")
        // inside, near the bottom
        assertTrue(wedge.sphereContact(0f, -4.5f, 2f, 1f, null, out))
        near(-1f, out.ny, 1e-4f, "bottom normal")
    }

    @Test
    fun sphereCastFindsTheFirstContactAndIgnoresStartOverlaps() {
        val c = StationColliders(
            intArrayOf(StationColliders.SHAPE_BLOCK, StationColliders.SHAPE_BLOCK),
            intArrayOf(0, 0),
            cf(0f, 0f, 50f) + cf(0f, 0f, 80f),
            floatArrayOf(40f, 40f, 10f, 40f, 40f, 10f),
        )
        val hit = StationHit()
        // from z = 0 towards +Z with a 5 m sphere: the first wall's face is at z = 45, contact at 40
        assertTrue(c.sphereCast(0f, 0f, 0f, 0f, 0f, 100f, 5f, null, hit))
        near(0.4f, hit.fraction, 2e-4f, "fraction")
        near(-1f, hit.nz, 1e-4f, "normal faces the sphere")
        assertEquals(0, hit.collider)
        // passing beside it
        assertFalse(c.sphereCast(30f, 0f, 0f, 30f, 0f, 100f, 5f, null, hit))
        // starting inside the first wall (Roblox's Spherecast ignores it): the second one is found
        assertTrue(c.sphereCast(0f, 0f, 50f, 0f, 0f, 100f, 5f, null, hit))
        assertEquals(1, hit.collider)
        near((70f - 50f) / 50f, hit.fraction, 2e-4f, "fraction to the second wall")
    }

    @Test
    fun groupsMoveTheirColliders() {
        // a block 100 m out on +X in group 1, which turns about Y through the origin
        val c = one(StationColliders.SHAPE_BLOCK, cf(100f, 0f, 0f), 10f, 10f, 10f, group = 1)
        val frames = GroupFrames(2)
        val out = StationContact()
        assertTrue(c.sphereContact(100f, 0f, 0f, 1f, frames, out))
        // a quarter turn about +Y takes +X to -Z
        frames.set(1, 0f, 1f, 0f, (Math.PI / 2).toFloat(), 0f, 0f, 0f)
        assertFalse(c.sphereContact(100f, 0f, 0f, 1f, frames, out))
        assertTrue(c.sphereContact(0f, 0f, -100f - 5.5f, 1f, frames, out))
        near(-1f, out.nz, 1e-4f, "normal turned with the group")
        near(-105f, out.pz, 1e-3f, "contact point turned with the group")
        // not solid (a build stage not built yet): ignored
        frames.solid[1] = false
        assertFalse(c.sphereContact(0f, 0f, -105.5f, 1f, frames, out))
    }

    @Test
    fun dockingRouteThroughTheMouthIsClear() {
        val hit = StationHit()
        val out = StationContact()
        for (t in StationModel.TYPES) {
            val m = model(t)
            val st = StationInstance(m)
            val f = st.frames
            val r = 10.5f // ShipStation.probeFor's default probe radius
            val p = FloatArray(3)
            m.approachPoint(StationModel.APPROACH_OUTER, p)
            val ox = p[0]; val oy = p[1]; val oz = p[2]
            m.approachPoint(StationModel.APPROACH_INNER, p)
            // Outer -> Mouth -> Inner on the axis, then to the hover point 34 m over each pad
            assertFalse(m.colliders.sphereCast(ox, oy, oz, p[0], p[1], p[2], r, f, hit), "$t: Outer -> Inner blocked by collider ${hit.collider}")
            for (pad in m.pads) {
                val hy = pad.y + 10.5f + 34f
                assertFalse(m.colliders.sphereCast(p[0], p[1], p[2], pad.x, hy, pad.z, r, f, hit), "$t: Inner -> over pad ${pad.index} blocked")
                assertFalse(m.colliders.sphereContact(pad.x, hy, pad.z, r, f, out), "$t: hover point over pad ${pad.index} touches ${out.collider}")
                // down onto the pad: the hangar floor stops the probe at about the pad top
                assertTrue(m.colliders.sphereCast(pad.x, hy, pad.z, pad.x, pad.y - 40f, pad.z, r, f, hit), "$t: no floor under pad ${pad.index}")
                assertTrue(hit.ny > 0.99f, "$t: floor normal ${hit.ny}")
                val restY = hy + (pad.y - 40f - hy) * hit.fraction - r
                assertTrue(restY in pad.y - 3f..pad.y + 1f, "$t pad ${pad.index}: probe bottom rests at $restY, pad top ${pad.y}")
            }
            // the hull is solid: flying in from behind stops at the back of the station
            assertTrue(m.colliders.sphereCast(0f, 0f, 3000f, 0f, 0f, 0f, r, f, hit), "$t: no hull behind the hangar")
        }
    }

    @Test
    fun ringCollidersSpinWithTheRing() {
        val m = model("RingStation")
        val st = StationInstance(m, seed = 0)
        val ring = m.colliders
        // find a ring collider and a point inside it at rest
        val box = FloatArray(6)
        var idx = -1
        for (i in 0 until ring.count) if (ring.group(i) == 1) { idx = i; break }
        ring.box(idx, box)
        val atRest = GroupFrames(m.groups.size)
        val out = StationContact()
        assertTrue(ring.sphereContact(box[0], box[1], box[2], 1f, atRest, out))
        // turn the ring by 90 degrees (Roblox: 2 deg/s, so 45 s): the collider has moved away
        st.update(45f, Float.NaN, 0f, 0f)
        val moved = st.angles[1]
        assertTrue(moved != 0f)
        val g = m.groups[1]
        val turned = GroupFrames(m.groups.size).also { it.set(1, g.axisX, g.axisY, g.axisZ, (Math.PI / 2).toFloat(), g.centreX, g.centreY, g.centreZ) }
        val c = cos(Math.PI / 2).toFloat()
        val s = sin(Math.PI / 2).toFloat()
        // rotate the rest point by +90 degrees about Z: (x, y) -> (x c - y s, x s + y c)
        val rx = box[0] * c - box[1] * s
        val ry = box[0] * s + box[1] * c
        assertTrue(ring.sphereContact(rx, ry, box[2], 1f, turned, out), "the collider moved with the ring")
    }

    @Test
    fun siteStagesCollideOnlyOnceBuilt() {
        val m = model(StationModel.SITE)
        val st = StationInstance(m)
        val c = m.colliders
        val box = FloatArray(6)
        val out = StationContact()
        for (stage in 1..4) {
            val i = (0 until c.count).first { c.group(it) == stage }
            c.box(i, box)
            st.setSiteStages(stage - 1, building = true)
            assertFalse(c.sphereContact(box[0], box[1], box[2], 0.5f, st.frames, out), "stage $stage collides before it is built")
            st.setSiteStages(stage, building = false)
            assertTrue(c.sphereContact(box[0], box[1], box[2], 0.5f, st.frames, out), "stage $stage does not collide once built")
        }
    }

    @Test
    fun queriesDoNotAllocate() {
        val m = model("HollowStation") // the most colliders (324)
        val st = StationInstance(m)
        val out = StationContact()
        val hit = StationHit()
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        val id = Thread.currentThread().id
        var n = 0
        repeat(2000) { // warm up (JIT)
            if (m.colliders.sphereContact(85f, -62f, -130f, 10.5f, st.frames, out)) n++
            if (m.colliders.sphereCast(0f, 0f, -1270f, 85f, -30f, -130f, 10.5f, st.frames, hit)) n++
            st.update(1f / 60f, 0f, 0f, -800f)
        }
        val before = bean.getThreadAllocatedBytes(id)
        val t0 = System.nanoTime()
        repeat(1000) {
            // five probe spheres per frame, as ShipStation.collide, plus the frame's state update
            for (k in 0 until 5) {
                // resting on the hangar floor under pad 1 (contacts), and descending onto it (hits)
                if (m.colliders.sphereContact(85f, -65f + k * 0.1f, -130f + k, 10.5f, st.frames, out)) n++
                if (m.colliders.sphereCast(85f, -40f, -130f + k, 85f, -80f, -130f + k, 10.5f, st.frames, hit)) n++
            }
            st.update(1f / 60f, 0f, 0f, -800f)
        }
        val ns = (System.nanoTime() - t0) / 1000.0
        val bytes = bean.getThreadAllocatedBytes(id) - before
        println("collision + update per frame: %.1f us, %d B allocated over 1000 frames (%d hits)".format(ns / 1000.0, bytes, n))
        assertTrue(bytes < 1024, "$bytes B allocated")
    }
}
