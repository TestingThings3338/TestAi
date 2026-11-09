package com.starwake.core.terrain

import com.starwake.core.gfx.Camera
import com.starwake.core.gfx.TerrainLight
import com.starwake.core.gfx.TerrainRenderer
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The planet as the game uses it: workers, uploads, ground state, precision and allocations. */
class TerrainPlanetTest {
    private val pool = TileWorkerPool(2, 16)

    @AfterTest
    fun stop() = pool.shutdown()

    private val luna = SurfaceProfiles.forBody("Luna", "Moon", 94, "Sol")

    /** Runs frames until the selection has nothing left to ask for (or [maxFrames]). */
    private fun settle(p: TerrainPlanet, cam: DVec3, t0: Double, maxFrames: Int, up: TileUploader): Double {
        var t = t0
        var quiet = 0
        for (f in 0 until maxFrames) {
            p.update(cam, 1.0 / 60)
            p.processCompletions(64, up)
            t += 1.0 / 60
            if (p.tree.wantedCount == 0 && pool.idle()) {
                if (++quiet > 3) break
            } else {
                quiet = 0
            }
            Thread.sleep(1)
        }
        return t
    }

    /** groundState under a descending ship: missing (coarse leaf) → fresh (finest, < 0.6 s) → solid. */
    @Test
    fun groundStateSequence() {
        val p = TerrainPlanet("Luna", 1.74e6, luna, 0, pool)
        p.awaitReady()
        p.setBodyPose(DVec3(), Quat())
        val up = TileUploader { _, _ -> }
        val dir = doubleArrayOf(0.0, 0.0, 1.0)
        val floor = p.surface.floorAt(dir[0], dir[1], dir[2])
        val ship = DVec3(0.0, 0.0, floor + 10)
        // from far away: the leaf under the ship is coarse
        settle(p, DVec3(0.0, 0.0, floor + 2e6), 0.0, 3000, up)
        assertEquals(PlanetSurface.MISSING, p.surface.groundState(ship))
        // camera at the ship: the finest tiles arrive; right after they join the cut they are fresh
        var sawFresh = false
        var sawSolid = false
        val cam = DVec3(0.0, 0.0, floor + 10)
        for (f in 0 until 4000) {
            p.update(cam, 1.0 / 60)
            p.processCompletions(64, up)
            when (p.surface.groundState(ship)) {
                PlanetSurface.FRESH -> {
                    sawFresh = true
                    assertTrue(!sawSolid, "fresh after solid")
                }
                PlanetSurface.SOLID -> sawSolid = true
            }
            if (sawSolid && f > 60) break
            Thread.sleep(1)
        }
        assertTrue(sawFresh, "never fresh")
        assertTrue(sawSolid, "never solid")
        val leaf = p.tree.leafAt(0.0, 0.0, 1.0)
        assertEquals(p.maxLevel, p.tree.level[leaf])
        p.release()
    }

    /**
     * 1 m above Earth's surface at 2.5e9 m from the star: the camera-relative vertex positions the
     * renderer forms (per-tile origin in double → float, plus the float local offset) are exact to
     * well under 1 mm, and stable when the camera moves by 1 mm.
     */
    @Test
    fun precisionFarFromTheOrigin() {
        val earth = SurfaceProfiles.forBody("Earth", "Terrestrial World", 3, "Sol")
        val field = SurfaceField.create(6.36e6, StyleCatalog.chooseStyle(earth)!!, 3, "Terrestrial World")
        field.buildMeanColours()
        val b = TileBuilder(field)
        val st = TileStaging(0, TileFormat.TILE_BYTES)
        val lmax = CubeSphere.maxLevel(6.36e6, 2.5)
        val m = 1 shl lmax
        b.build(4, lmax, m / 2 + 7, m / 2 - 3, st)
        val centre = DVec3(2.1e9, -1.3e9, 0.4e9)
        val d = DoubleArray(3)
        b.vertexDir(16, 16, d)
        val r = b.vertexFloor(16, 16)
        val cam = DVec3(centre.x + d[0] * (r + 1), centre.y + d[1] * (r + 1), centre.z + d[2] * (r + 1))
        var worst = 0.0
        var worstMove = 0.0
        for (step in 0 until 2) {
            val cx = cam.x + step * 0.001
            for (j in 0..32 step 4) for (i in 0..32 step 4) {
                val o = (j * 33 + i) * TileFormat.STRIDE
                // the renderer: origin − camera in double → float, local offset dequantised in float
                val ox = (centre.x + st.cx - cx).toFloat()
                val oy = (centre.y + st.cy - cam.y).toFloat()
                val oz = (centre.z + st.cz - cam.z).toFloat()
                val lx = st.buffer.getShort(o) / 32767f * st.hx
                val ly = st.buffer.getShort(o + 2) / 32767f * st.hy
                val lz = st.buffer.getShort(o + 4) / 32767f * st.hz
                val fx = (ox + lx).toDouble()
                val fy = (oy + ly).toDouble()
                val fz = (oz + lz).toDouble()
                // exact: the same quantised vertex in double
                val ex = centre.x + st.cx + st.buffer.getShort(o) / 32767.0 * st.hx - cx
                val ey = centre.y + st.cy + st.buffer.getShort(o + 2) / 32767.0 * st.hy - cam.y
                val ez = centre.z + st.cz + st.buffer.getShort(o + 4) / 32767.0 * st.hz - cam.z
                worst = max(worst, sqrt((fx - ex) * (fx - ex) + (fy - ey) * (fy - ey) + (fz - ez) * (fz - ez)))
                if (step == 1) worstMove = max(worstMove, abs(fx - ex))
            }
        }
        assertTrue(worst < 1e-4, "camera-relative vertex error $worst m")
        assertTrue(worstMove < 1e-4, "after a 1 mm move: $worstMove m")
    }

    /**
     * The game-thread calls of a frame (update, uploads, culling and drawing on a GL, the ground
     * queries) allocate nothing after warm-up, while the workers stream tiles.
     */
    @Test
    fun noAllocationPerFrame() {
        val p = TerrainPlanet("Luna", 1.74e6, luna, 0, pool)
        p.awaitReady()
        p.setBodyPose(DVec3(1.0e9, 2.0e8, -3.0e8), Quat().setAxisAngle(0.3f, 1f, 0.2f, 0.7f))
        val gl = FakeGl()
        val renderer = TerrainRenderer(gl, 0)
        val gpu = renderer.attach(p)
        val light = TerrainLight()
        light.setAir(p.profile, p.radius)
        val cam = Camera()
        cam.update(1200, 540)
        val hit = FloorHit()
        val contact = PlanetSurface.Contact()
        val up = Vec3()
        val vel = Vec3(10f, -30f, 5f)
        val ship = DVec3()
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        // a slow descent over the surface: new tiles arrive every frame
        val dirX = 0.2
        val dirY = 0.3
        val dirZ = sqrt(1 - dirX * dirX - dirY * dirY)
        val centre = p.surface.centre
        val rot = p.surface.rotation()
        var total = 0L
        var frames = 0
        var allocating = 0
        for (f in 0 until 900) {
            val alt = 200_000.0 * Math.pow(0.99, f.toDouble()) + 20
            val r = 1.74e6 + 2000 + alt
            // body → world
            val bx = dirX * r + f * 3.0
            val by = dirY * r
            val bz = dirZ * r
            ship.set(
                centre.x + rot[0] * bx + rot[1] * by + rot[2] * bz,
                centre.y + rot[3] * bx + rot[4] * by + rot[5] * bz,
                centre.z + rot[6] * bx + rot[7] * by + rot[8] * bz,
            )
            cam.pos.set(ship)
            cam.rot.lookRotation(0.2f, -0.8f, 0.1f, 0f, 1f, 0f)
            cam.update(1200, 540)
            val a0 = mx.currentThreadAllocatedBytes
            p.update(cam.pos, 1.0 / 60, ship, vel, 16f)
            renderer.prepare(gpu, cam)
            renderer.drawSlices(gpu, cam, light, f / 60f)
            p.surface.altitudeAt(ship)
            p.surface.floorHit(ship, 400.0, hit)
            p.surface.groundState(ship)
            p.surface.gravityAt(ship, up)
            p.surface.contact(ship, vel, 12.0, contact)
            p.surface.upAt(ship, up)
            val a1 = mx.currentThreadAllocatedBytes
            if (f >= 300) {
                total += a1 - a0
                frames++
                if (a1 - a0 > 0) allocating++
            }
            Thread.sleep(1)
        }
        println("allocation: $total B over $frames frames ($allocating allocating), uploads ${p.uploadsTotal}, draws ${gl.drawCalls}")
        assertTrue(p.uploadsTotal > 50, "tiles streamed during the test")
        // the counter itself may report a few bytes once; nothing per frame
        assertTrue(total < 1024, "allocated $total B in $frames frames")
        assertTrue(allocating <= 2, "$allocating frames allocated")
        gpu.dispose()
        renderer.dispose()
        p.release()
    }
}
