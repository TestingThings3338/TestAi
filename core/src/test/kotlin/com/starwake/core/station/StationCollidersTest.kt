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
    fun grazingMovesAreStopped() {
        // a 40 x 40 x 10 m wall (face at z = 5) and ShipStation's default probe (r = 10.5), starting 5 cm off the
        // face, where Roblox's slide response leaves the ship, moving 4.3 m (260 m/s at 60 fps) at a shallow angle
        // into the wall: the move ends inside the wall from 0.67 degrees on, and the sweep must say where it touched
        val c = one(StationColliders.SHAPE_BLOCK, cf(0f, 0f, 0f), 40f, 40f, 10f)
        val hit = StationHit()
        val r = 10.5f
        val gap = 0.05f
        val len = 4.3f
        for (deg in listOf(0.5, 0.7, 1.0, 2.0, 3.0, 4.0, 5.0, 8.0, 10.0, 20.0, 45.0, 90.0)) {
            val a = Math.toRadians(deg)
            val z0 = 5f + r + gap
            val y0 = -10f
            val y1 = (y0 + len * cos(a)).toFloat()
            val z1 = (z0 - len * sin(a)).toFloat()
            val into = len * sin(a).toFloat()
            val h = c.sphereCast(0f, y0, z0, 0f, y1, z1, r, null, hit)
            if (into <= gap) {
                assertFalse(h, "$deg deg: the move ends ${gap - into} m short of the wall")
                continue
            }
            assertTrue(h, "$deg deg: the move ends ${into - gap} m inside the wall and the sweep missed it")
            near(gap / into, hit.fraction, 2e-4f, "$deg deg: fraction")
            near(1f, hit.nz, 1e-5f, "$deg deg: normal")
            // at the reported fraction the sphere just touches
            val y = y0 + (y1 - y0) * hit.fraction
            val z = z0 + (z1 - z0) * hit.fraction
            near(0f, c.clearance(0f, y, z, r, 1f, null), 2e-3f, "$deg deg: gap at the contact")
        }
    }

    @Test
    fun touchingAndLeavingOrSlidingIsFree() {
        val c = one(StationColliders.SHAPE_BLOCK, cf(0f, 0f, 0f), 40f, 40f, 10f)
        val hit = StationHit()
        val r = 10.5f
        // resting on the face (within 0.5 mm): lifting off, sliding along it and moving in
        val z0 = 5f + r + 0.0005f
        assertFalse(c.sphereCast(0f, 0f, z0, 0f, 0f, z0 + 5f, r, null, hit), "lifting off")
        assertFalse(c.sphereCast(0f, 0f, z0, 0f, 6f, z0, r, null, hit), "sliding along")
        assertTrue(c.sphereCast(0f, 0f, z0, 0f, 6f, z0 - 1f, r, null, hit), "pressing in")
        near(0f, hit.fraction, 1e-6f, "pressing in stops at once")
        // passing beside a ball, cylinder and wedge within 1 cm: no contact; 1 cm closer: contact
        val ball = one(StationColliders.SHAPE_BALL, cf(0f, 0f, 0f), 10f, 10f, 10f)
        assertFalse(ball.sphereCast(-50f, 5f + 2f + 0.01f, 0f, 50f, 5f + 2f + 0.01f, 0f, 2f, null, hit), "past the ball")
        assertTrue(ball.sphereCast(-50f, 5f + 2f - 0.01f, 0f, 50f, 5f + 2f - 0.01f, 0f, 2f, null, hit), "grazing the ball")
        near(0.5f, hit.fraction, 0.01f, "ball graze near the middle of the move")
        val cyl = one(StationColliders.SHAPE_CYLINDER, cf(0f, 0f, 0f), 20f, 8f, 8f)
        assertFalse(cyl.sphereCast(0f, -30f, 4f + 2f + 0.01f, 0f, 30f, 4f + 2f + 0.01f, 2f, null, hit), "past the cylinder")
        assertTrue(cyl.sphereCast(0f, -30f, 4f + 2f - 0.01f, 0f, 30f, 4f + 2f - 0.01f, 2f, null, hit), "grazing the cylinder")
    }

    @Test
    fun sweepsAgreeWithTheContactsAlongTheMove() {
        // every shape, turned at random, swept at random (many shallow): a reported hit is a touch (gap ~0) with
        // nothing touched before it; no hit means the sphere never sinks in along the whole move
        val rng = java.util.Random(1234)
        val shapes = intArrayOf(
            StationColliders.SHAPE_BLOCK, StationColliders.SHAPE_BALL, StationColliders.SHAPE_CYLINDER, StationColliders.SHAPE_WEDGE,
        )
        val hit = StationHit()
        var hits = 0
        var misses = 0
        repeat(4000) { n ->
            val shape = shapes[n % shapes.size]
            val sx = 4f + rng.nextFloat() * 40f
            val sy = 4f + rng.nextFloat() * 40f
            val sz = 4f + rng.nextFloat() * 40f
            val c = one(shape, cf(0f, 0f, 0f, rng.nextFloat() * 360f), sx, if (shape == StationColliders.SHAPE_BALL) sx else sy, if (shape == StationColliders.SHAPE_BALL) sx else sz)
            val r = 0.5f + rng.nextFloat() * 10f
            // start somewhere around it, outside
            var x0: Float; var y0: Float; var z0: Float
            do {
                x0 = (rng.nextFloat() - 0.5f) * 120f
                y0 = (rng.nextFloat() - 0.5f) * 120f
                z0 = (rng.nextFloat() - 0.5f) * 120f
            } while (c.clearance(x0, y0, z0, r, 1e9f, null) < 0.01f)
            // half of the moves aim at the collider's centre, slightly off, and reach 0.3..1.6 of the way;
            // the rest roll a direction and a length
            val dist = sqrt(x0 * x0 + y0 * y0 + z0 * z0)
            var len = 0.5f + rng.nextFloat() * 60f
            var dx: Float; var dy: Float; var dz: Float
            if (n % 2 == 0) {
                dx = -x0 + (rng.nextFloat() - 0.5f) * 30f
                dy = -y0 + (rng.nextFloat() - 0.5f) * 30f
                dz = -z0 + (rng.nextFloat() - 0.5f) * 30f
                len = dist * (0.3f + rng.nextFloat() * 1.3f)
            } else {
                dx = rng.nextFloat() - 0.5f; dy = rng.nextFloat() - 0.5f; dz = rng.nextFloat() - 0.5f
            }
            val dl = sqrt(dx * dx + dy * dy + dz * dz)
            val x1 = x0 + dx / dl * len
            val y1 = y0 + dy / dl * len
            val z1 = z0 + dz / dl * len
            val h = c.sphereCast(x0, y0, z0, x1, y1, z1, r, null, hit)
            val end = if (h) hit.fraction else 1f
            // nothing touched before the end (sampled every 2 cm), and at a hit the sphere touches
            val steps = maxOf(2, (len * end / 0.02f).toInt())
            for (k in 0 until steps) {
                val s = end * k / steps
                val g = c.clearance(x0 + (x1 - x0) * s, y0 + (y1 - y0) * s, z0 + (z1 - z0) * s, r, 1e9f, null)
                assertTrue(g > -2e-3f, "case $n shape $shape: sinks ${-g} m in at $s of the move (hit $h at ${hit.fraction})")
            }
            if (h) {
                hits++
                val g = c.clearance(x0 + (x1 - x0) * end, y0 + (y1 - y0) * end, z0 + (z1 - z0) * end, r, 1e9f, null)
                assertTrue(abs(g) < 3e-3f, "case $n shape $shape: gap $g at the reported contact")
                val nl = hit.nx * hit.nx + hit.ny * hit.ny + hit.nz * hit.nz
                assertTrue(abs(nl - 1f) < 1e-3f, "case $n: normal length $nl")
                // the normal faces the incoming sphere
                assertTrue(hit.nx * (x1 - x0) + hit.ny * (y1 - y0) + hit.nz * (z1 - z0) <= 1e-3f * len, "case $n: normal along the move")
            } else {
                misses++
            }
        }
        assertTrue(hits > 800 && misses > 800, "$hits hits, $misses misses")
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
