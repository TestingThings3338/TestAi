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
     * The scheduler's height (§9.6: path first and the near boost below 1,500 m, the descent focus at
     * the 900 m level-off) is the height above the ground, and its focus lies on the ground: over
     * Earth's highest ground and Mars' lowest (kilometres above and below the reference sphere;
     * Earth's floor never goes below its sea).
     */
    @Test
    fun focusUsesTheHeightAboveTheGround() {
        val earth = TerrainPlanet.forBody("Earth", 2120.0 * 3000, "Terrestrial World", 3, 2, pool, systemName = "Sol")
        val mars = TerrainPlanet.forBody("Mars", 1130.0 * 3000, "Rocky Body", 4, 2, pool, systemName = "Sol")
        for ((p, highest) in listOf(earth to true, mars to false)) {
            p.setBodyPose(DVec3(), Quat())
            val n = 3000
            val golden = Math.PI * (3.0 - sqrt(5.0))
            var best = if (highest) -1e9 else 1e9
            val d = DoubleArray(3)
            for (i in 0 until n) {
                val y = 1.0 - 2.0 * (i + 0.5) / n
                val r = sqrt(1 - y * y)
                val x = r * kotlin.math.cos(golden * i)
                val z = r * kotlin.math.sin(golden * i)
                val f = p.surface.floorAt(x, y, z) - p.radius
                if (if (highest) f > best else f < best) { best = f; d[0] = x; d[1] = y; d[2] = z }
            }
            assertTrue(kotlin.math.abs(best) > 1500, "${p.name}: ground kilometres from the reference sphere ($best m)")
            val floor = p.surface.floorAt(d[0], d[1], d[2])
            var ex = d[2]
            var ez = -d[0]
            val el = sqrt(ex * ex + ez * ez)
            ex /= el; ez /= el
            // level flight 100 m up at 260 m/s
            val ship = DVec3(d[0] * (floor + 100), d[1] * (floor + 100), d[2] * (floor + 100))
            p.update(ship, 1.0 / 60, ship, Vec3((ex * 260).toFloat(), 0f, (ez * 260).toFloat()), 16f)
            val f = p.tree.focus
            assertEquals(100.0, f.height, 0.5, "height above the ground ${floor - p.radius} m above R")
            assertTrue(f.height < TerrainQuadtree.PATH_HEIGHT && f.hasFocus)
            val fr = sqrt(f.focusX * f.focusX + f.focusY * f.focusY + f.focusZ * f.focusZ)
            assertEquals(p.surface.floorAt(f.focusX / fr, f.focusY / fr, f.focusZ / fr), fr, 1e-6, "the focus is on the ground")
            // 2.5 s ahead: 650 m along the tangent at the ship
            fun angleTo(s: DVec3): Double {
                val sr = sqrt(s.x * s.x + s.y * s.y + s.z * s.z)
                val fl = sqrt(f.focusX * f.focusX + f.focusY * f.focusY + f.focusZ * f.focusZ)
                return kotlin.math.acos(((f.focusX * s.x + f.focusY * s.y + f.focusZ * s.z) / (fl * sr)).coerceIn(-1.0, 1.0))
            }
            assertEquals(kotlin.math.atan(650.0 / (floor + 100)), angleTo(ship), 1e-6, "2.5 s ahead")
            // a descent 3 km above the ground: the focus is where it reaches the level-off height
            // (900 m above the ground): 300 / 100 × (3000 − 900) + 300 = 6,600 m ahead
            val ship2 = DVec3(d[0] * (floor + 3000), d[1] * (floor + 3000), d[2] * (floor + 3000))
            val v = Vec3((ex * 300 - d[0] * 100).toFloat(), (-d[1] * 100).toFloat(), (ez * 300 - d[2] * 100).toFloat())
            p.update(ship2, 1.0 / 60, ship2, v, 16f)
            assertEquals(3000.0, f.height, 0.5)
            assertEquals(kotlin.math.atan(6600.0 / (floor + 3000)), angleTo(ship2), 2e-5, "descent focus")
            p.release()
        }
    }

    /** The equirect bake started right after the planet waits for the mean colour table (no band of raw colours). */
    @Test
    fun bakeWaitsForTheMeanColours() {
        val p = TerrainPlanet.forBody("Earth", 2120.0 * 3000, "Terrestrial World", 3, 2, pool, systemName = "Sol")
        val early = p.bakeEquirect(128, 64)
        p.awaitReady()
        val late = p.bakeEquirect(128, 64)
        var differ = 0
        for (i in 0 until 128 * 64 * 4) if (early.get(i) != late.get(i)) differ++
        assertEquals(0, differ, "bytes differing between a bake at creation and one after the table")
        p.release()
    }

    /**
     * GL context loss: the planet drops its tiles without GL calls, attaches to a new renderer and
     * streams into it; until the roots are back the impostor shades the surface.
     */
    @Test
    fun gpuLostThenAttachedAgain() {
        val p = TerrainPlanet("Luna", 1.74e6, luna, 0, pool)
        p.awaitReady()
        p.setBodyPose(DVec3(), Quat())
        val cam = DVec3(0.0, 0.0, 1.74e6 + 3e6)
        val r1 = TerrainRenderer(FakeGl(), 0)
        val gpu1 = r1.attach(p)
        var f = 0
        while (!p.surfaceReady() && f++ < 3000) {
            p.update(cam, 1.0 / 60)
            p.processCompletions(64, gpu1)
            Thread.sleep(1)
        }
        assertTrue(p.surfaceReady(), "roots resident")
        assertTrue(runCatching { r1.attach(p) }.isFailure, "a planet with tiles cannot be attached twice")
        p.onGpuLost()
        assertEquals(0, p.tree.residentCount)
        assertTrue(!p.surfaceReady(), "the impostor shades the surface again")
        val gl2 = FakeGl()
        val r2 = TerrainRenderer(gl2, 0)
        val gpu2 = r2.attach(p)
        f = 0
        while (!p.surfaceReady() && f++ < 3000) {
            p.update(cam, 1.0 / 60)
            p.processCompletions(64, gpu2)
            Thread.sleep(1)
        }
        assertTrue(p.surfaceReady(), "the roots are back")
        assertTrue(gl2.uploads > 0, "tiles go to the new GPU state")
        r1.dispose()
        gpu2.dispose()
        r2.dispose()
        p.release()
    }

    /** The ρ controller works against the selected frame rate (DECISIONS 21a: 60 fps default, 120 Hz selectable). */
    @Test
    fun frameBudgetFollowsTheTargetRate() {
        val p = TerrainPlanet("Luna", 1.74e6, luna, 0, pool)
        p.setBodyPose(DVec3(), Quat())
        val cam = DVec3(0.0, 0.0, 2e7)
        repeat(120) { p.update(cam, 1.0 / 60, frameMs = 12f) }
        assertEquals(p.quality.rho, p.rho, 1e-9, "12 ms frames are within the 60 fps budget")
        p.frameBudgetMs = 1000f / 120f
        repeat(120) { p.update(cam, 1.0 / 120, frameMs = 12f) }
        assertEquals(p.quality.rho * 0.8, p.rho, 1e-6, "12 ms frames are over the 120 Hz budget: ρ drops to 80 %")
        p.release()
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
