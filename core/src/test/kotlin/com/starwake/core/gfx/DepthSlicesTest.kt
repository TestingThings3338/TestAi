package com.starwake.core.gfx

import com.starwake.core.math.Rng
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DepthSlicesTest {
    // 2400x1080 at 55 degrees vertical: the flight camera
    private val ty = tan(27.5 * Math.PI / 180).toFloat()
    private val tx = ty * 2400f / 1080f
    private val reach = 250f

    // home station and field bounding radii (SceneRenderer: mesh + lights, rocks at any spin)
    private val stationR = 925f
    private val fieldR = 2300f

    @Test
    fun spheresBehindOrBesideTheViewAreCulled() {
        val s = DepthSlices(4)
        s.begin()
        assertEquals(-1, s.add(0f, 0f, -2000f, 900f, tx, ty), "behind")
        assertEquals(-1, s.add(5000f, 0f, 1000f, 900f, tx, ty), "far to the right")
        assertEquals(-1, s.add(0f, -3000f, 1000f, 900f, tx, ty), "far below")
        // camera inside the sphere: always kept, whatever the direction
        assertTrue(s.add(0f, 0f, -500f, 900f, tx, ty) >= 0)
        // touching the right edge of the view
        assertTrue(s.add(tx * 10_000f + 500f, 0f, 10_000f, 900f, tx, ty) >= 0)
        assertEquals(2, s.count)
    }

    @Test
    fun spawnIsOneSlice() {
        // camera 1.5 km from the field centre (inside its sphere), the station 2.8 km ahead-left
        val s = DepthSlices(4)
        s.begin()
        val field = s.add(0f, 0f, 1500f, fieldR, tx, ty)
        val station = s.add(-2000f, 250f, 1900f, stationR, tx, ty)
        s.plan(reach)
        assertFalse(s.split)
        assertFalse(s.isFar(field))
        assertFalse(s.isFar(station))
    }

    @Test
    fun dockingIsOneSlice() {
        // inside the ring station's radius: the station, and the field it overlaps, stay with the camera
        val s = DepthSlices(4)
        s.begin()
        val station = s.add(0f, 0f, 300f, stationR, tx, ty)
        val field = s.add(1500f, 0f, 2600f, fieldR, tx, ty)
        s.plan(reach)
        assertFalse(s.split)
        assertFalse(s.isFar(station))
        assertFalse(s.isFar(field))
    }

    @Test
    fun stationAndFieldThirtyKilometresOutShareTheFarSlice() {
        val s = DepthSlices(4)
        s.begin()
        val station = s.add(0f, 0f, 30_000f, stationR, tx, ty)
        val field = s.add(-1500f, 100f, 32_300f, fieldR, tx, ty)
        s.plan(reach)
        assertTrue(s.split)
        assertTrue(s.isFar(station))
        assertTrue(s.isFar(field))
        assertEquals(30_000f - stationR, s.farNear)
        assertEquals(32_300f + fieldR, s.farFar)
        assertEquals(reach, s.nearEnd)
    }

    @Test
    fun overlapsChainIntoTheCameraCluster() {
        // A starts within reach; B overlaps A; C overlaps B; D is clear of all of them
        val s = DepthSlices(4)
        s.begin()
        val d = s.add(0f, 0f, 20_000f, 500f, tx, ty)
        val c = s.add(0f, 0f, 3500f, 1000f, tx, ty)
        val b = s.add(0f, 0f, 1500f, 1200f, tx, ty)
        val a = s.add(0f, 0f, 600f, 500f, tx, ty)
        s.plan(reach)
        assertFalse(s.isFar(a))
        assertFalse(s.isFar(b))
        assertFalse(s.isFar(c))
        assertTrue(s.isFar(d))
        assertEquals(4500f, s.nearEnd)
        assertEquals(19_500f, s.farNear)
    }

    @Test
    fun slicesNeverOverlapInDepth() {
        val rng = Rng(7)
        val s = DepthSlices(8)
        val zMin = FloatArray(8)
        val zMax = FloatArray(8)
        val slot = IntArray(8)
        repeat(2000) {
            s.begin()
            val n = 1 + (rng.nextFloat() * 7).toInt()
            for (i in 0 until n) {
                val r = rng.range(50f, 3000f)
                val z = rng.range(-2000f, 80_000f)
                slot[i] = s.add(rng.signed() * 0.3f * z, rng.signed() * 0.2f * z, z, r, tx, ty)
                zMin[i] = z - r
                zMax[i] = z + r
            }
            s.plan(reach)
            var any = false
            for (i in 0 until n) {
                if (slot[i] < 0) continue
                if (s.isFar(slot[i])) {
                    any = true
                    assertTrue(zMin[i] > s.nearEnd, "far object starts at ${zMin[i]} inside the near slice (ends ${s.nearEnd})")
                    assertTrue(zMin[i] >= s.farNear && zMax[i] <= s.farFar)
                } else {
                    assertTrue(zMax[i] <= s.nearEnd, "near object ends at ${zMax[i]} past the near slice (${s.nearEnd})")
                }
            }
            assertEquals(any, s.split)
        }
    }
}
