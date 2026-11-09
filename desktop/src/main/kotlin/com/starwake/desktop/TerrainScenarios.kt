package com.starwake.desktop

import com.starwake.core.content.Content
import com.starwake.core.content.Primitives
import com.starwake.core.content.StarField
import com.starwake.core.game.ShipState
import com.starwake.core.game.StarSystem
import com.starwake.core.gfx.BodyRenderer
import com.starwake.core.gfx.Camera
import com.starwake.core.gfx.GL
import com.starwake.core.gfx.Gl
import com.starwake.core.gfx.GpuMesh
import com.starwake.core.gfx.SceneFrame
import com.starwake.core.gfx.SceneRenderer
import com.starwake.core.gfx.ShaderProgram
import com.starwake.core.gfx.Shaders
import com.starwake.core.gfx.TerrainGpu
import com.starwake.core.gfx.TerrainLight
import com.starwake.core.gfx.TerrainRenderer
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.terrain.FieldScratch
import com.starwake.core.terrain.PlanetSurface
import com.starwake.core.terrain.SurfaceSample
import com.starwake.core.terrain.TerrainPlanet
import com.starwake.core.terrain.TileFormat
import com.starwake.core.terrain.TileWorkerPool
import org.lwjgl.opengles.GLES20
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seamless planet terrain in the harness (04-planet-surface.md §10.5): three test planets built
 * from the Sol selections (Earth: TEMPERATE WORLD at 6,360 km; Luna: AIRLESS REGOLITH at 1,740 km,
 * S = 3000; Mars: RED DESERT at 3,390 km), each with its impostor (sky, limb, the terrain's own far
 * colours as its texture) and the terrain.
 *
 * - `terrain`: static views down one descent path at 20,000 km … 20 m above the ground, looking
 *   down and towards the horizon, on HIGH and LOW (each view waits until its tiles are built);
 *   then a flight-speed descent per planet under the speed laws (DECISIONS 2/3) in real time: every
 *   frame gives the workers 16.7 ms of wall time and takes what is finished, nothing waits for
 *   completion, so the shots show what a player would see.
 * - `terrain-perf`: the same descents on a no-op GL (the engine's CPU cost) with per-frame
 *   game-thread time, allocations, uploads, worker time per tile, tiles resident/drawn, triangles,
 *   draw calls, slices, memory; plus a few llvmpipe frames.
 */
object TerrainScenarios {
    class TestPlanet(
        val label: String,
        val name: String,
        val radius: Double,
        val bodyClass: String,
        val seed: Int,
        val gravityG: Double,
        val type: StarSystem.BodyType,
        val atmo: FloatArray,
        val atmoThickness: Float,
    )

    /** Sol data at S = 3000 (03-world.md §2.1): radius, class, seed, gravity; impostor air per DECISIONS 17b (limb = 3 × thickness). */
    val PLANETS = listOf(
        TestPlanet("earth", "Earth", 2120.0 * 3000, "Terrestrial World", 3, 1.0, StarSystem.BodyType.TERRAN, floatArrayOf(0.35f, 0.55f, 1.0f), (3 * 26_000.0 / (2120.0 * 3000)).toFloat()),
        TestPlanet("luna", "Luna", 580.0 * 3000, "Moon", 94, 0.165, StarSystem.BodyType.ROCK, floatArrayOf(0.6f, 0.6f, 0.6f), 0f),
        TestPlanet("mars", "Mars", 1130.0 * 3000, "Rocky Body", 4, 0.378, StarSystem.BodyType.ROCK, floatArrayOf(0.85f, 0.6f, 0.45f), (3 * 22_000.0 / (1130.0 * 3000)).toFloat()),
    )

    val ALTITUDES = doubleArrayOf(20_000_000.0, 2_000_000.0, 300_000.0, 60_000.0, 15_000.0, 3_000.0, 600.0, 120.0, 20.0)

    private fun altLabel(h: Double): String = when {
        h >= 1e6 -> "%05dkm".format((h / 1000).toInt())
        h >= 1e3 -> "%05dkm".format((h / 1000).toInt())
        else -> "%05dm".format(h.toInt())
    }

    /** Everything one planet needs on one GL context. */
    private class Scene(env: ScenarioEnv, gl: Gl, val tp: TestPlanet, quality: Int, pool: TileWorkerPool, bake: Boolean, seam: Boolean = false) {
        val planet = TerrainPlanet.forBody(tp.name, tp.radius, tp.bodyClass, tp.seed, quality, pool, systemName = "Sol", gravityG = tp.gravityG)
        val system = StarSystem()
        val target = DoubleArray(3)
        val east = DoubleArray(3)
        val north = DoubleArray(3)
        val sunDir = Vec3()
        val centre = DVec3()
        val fullscreen = GpuMesh(gl, Primitives.fullscreenTriangle, intArrayOf(2))
        val bodies: BodyRenderer
        val renderer = TerrainRenderer(gl, quality)
        val gpu: TerrainGpu = renderer.attach(planet)
        val light = TerrainLight()
        val camera = Camera()

        init {
            renderer.debugView = System.getenv("TERRAIN_DEBUG")?.toIntOrNull() ?: 0
        }

        init {
            planet.awaitReady()
            if (seam) seamTarget(planet, tp, target) else chooseTarget(planet, tp, target)
            frame(target, east, north)
            // sun 35° above the target's eastern horizon, a little from the south
            val el = 35.0 * PI / 180
            var sx = target[0] * sin(el) + (east[0] * 0.92 - north[0] * 0.39) * cos(el)
            var sy = target[1] * sin(el) + (east[1] * 0.92 - north[1] * 0.39) * cos(el)
            var sz = target[2] * sin(el) + (east[2] * 0.92 - north[2] * 0.39) * cos(el)
            val sl = sqrt(sx * sx + sy * sy + sz * sz)
            sx /= sl; sy /= sl; sz /= sl
            sunDir.set(sx.toFloat(), sy.toFloat(), sz.toFloat())
            // the star stays at the origin: the planet sits 1.1e9 m from it, opposite the sun direction
            val dist = 1.1e9
            centre.set(-sx * dist, -sy * dist, -sz * dist)
            system.bodies.removeAll { it.type != StarSystem.BodyType.STAR }
            system.bodies += StarSystem.Body(tp.name, tp.type, tp.radius, DVec3(centre.x, centre.y, centre.z), Quat(), 0f, false, atmo = tp.atmo, atmoThickness = tp.atmoThickness, seed = tp.seed)
            bodies = BodyRenderer(gl, system, quality, fullscreen)
            if (bake) {
                val t0 = System.nanoTime()
                val w = if (quality == 0) 512 else 1024
                val img = planet.bakeEquirect(w, w / 2)
                bodies.setSurfaceTexture(1, img, w, w / 2)
                env.report.note("%s q%d: impostor texture from the terrain functions, %dx%d in %.0f ms".format(tp.label, quality, w, w / 2, (System.nanoTime() - t0) / 1e6))
            }
            planet.setBodyPose(centre, Quat())
            light.setAir(planet.profile, tp.radius)
            light.setSun(sunDir.x, sunDir.y, sunDir.z)
            camera.far = 40000f
        }

        fun dispose() {
            gpu.dispose()
            renderer.dispose()
            bodies.dispose()
            fullscreen.dispose()
            planet.release()
        }
    }

    /** A point worth descending to: a coast with mountains (Earth), a mare edge (Luna), rough highlands (Mars). */
    fun chooseTarget(planet: TerrainPlanet, tp: TestPlanet, out: DoubleArray) {
        val field = planet.field
        val fs = FieldScratch()
        val s = SurfaceSample()
        val n = 6000
        val golden = PI * (3.0 - sqrt(5.0))
        var best = -1e30
        val probe = DoubleArray(3)
        for (i in 0 until n) {
            val y = 1.0 - 2.0 * (i + 0.5) / n
            if (kotlin.math.abs(y) > 0.75) continue
            val r = sqrt(1 - y * y)
            val a = golden * i
            val x = r * cos(a)
            val z = r * sin(a)
            field.collect(x * tp.radius - 1e4, y * tp.radius - 1e4, z * tp.radius - 1e4, x * tp.radius + 1e4, y * tp.radius + 1e4, z * tp.radius + 1e4, 20_000.0, fs)
            field.sample(x, y, z, 20_000.0, fs, s)
            val m = s.macro
            var lo = 1e9
            var hi = -1e9
            for (k in 0 until 8) {
                // neighbours ~120 km away
                val ang = k * PI / 4
                tangentOffset(x, y, z, ang, 120_000.0 / tp.radius, probe)
                field.sample(probe[0], probe[1], probe[2], 20_000.0, fs, s)
                lo = min(lo, s.macro.height)
                hi = max(hi, s.macro.height)
            }
            val score = when (tp.label) {
                "earth" -> (1.0 - kotlin.math.abs(m.wet - 0.5) * 2) * 2.0 + min(hi, 3500.0) / 1000.0 - m.dry * 2 - m.cold * 2
                "luna" -> min(hi - lo, 4000.0) / 1000.0 - kotlin.math.abs(m.height + 400.0) / 2000.0
                else -> min(hi - lo, 5000.0) / 1000.0 - m.frost * 3
            }
            if (score > best) {
                best = score
                out[0] = x; out[1] = y; out[2] = z
            }
        }
    }

    /**
     * A cube-sphere seam worth looking at: the dry point with the most relief among the 8 cube
     * corners (three faces meet) and the 12 face edges (sampled), corners preferred.
     */
    fun seamTarget(planet: TerrainPlanet, tp: TestPlanet, out: DoubleArray) {
        val field = planet.field
        val fs = FieldScratch()
        val s = SurfaceSample()
        val probe = DoubleArray(3)
        val v = DoubleArray(3)
        var best = -1e30
        for (axis in 0 until 3) for (si in intArrayOf(-1, 1)) for (sj in intArrayOf(-1, 1)) for (k in 0..40) {
            // edge along [axis]: the other two coordinates at ±1 (equi-angular parameter along it)
            val t = kotlin.math.tan((k / 20.0 - 1.0) * PI / 4)
            val i = (axis + 1) % 3
            val j = (axis + 2) % 3
            v[axis] = t; v[i] = si.toDouble(); v[j] = sj.toDouble()
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            val x = v[0] / l
            val y = v[1] / l
            val z = v[2] / l
            field.collect(x * tp.radius - 2e3, y * tp.radius - 2e3, z * tp.radius - 2e3, x * tp.radius + 2e3, y * tp.radius + 2e3, z * tp.radius + 2e3, 2_000.0, fs)
            field.sample(x, y, z, 2_000.0, fs, s)
            if (s.isWater || s.macro.frost > 0.3) continue
            var lo = 1e9
            var hi = -1e9
            for (q in 0 until 8) {
                tangentOffset(x, y, z, q * PI / 4, 30_000.0 / tp.radius, probe)
                field.sample(probe[0], probe[1], probe[2], 2_000.0, fs, s)
                lo = min(lo, s.macro.height)
                hi = max(hi, s.macro.height)
            }
            val corner = k == 0 || k == 40
            val score = min(hi - lo, 3000.0) / 1000.0 + (if (corner) 0.75 else 0.0)
            if (score > best) {
                best = score
                out[0] = x; out[1] = y; out[2] = z
            }
        }
    }

    /** The direction (x, y, z) moved by [angle] radians of arc towards bearing [bearing] (0 = east). */
    private fun tangentOffset(x: Double, y: Double, z: Double, bearing: Double, angle: Double, out: DoubleArray) {
        val e = DoubleArray(3)
        val nn = DoubleArray(3)
        frame(doubleArrayOf(x, y, z), e, nn)
        val tx = e[0] * cos(bearing) + nn[0] * sin(bearing)
        val ty = e[1] * cos(bearing) + nn[1] * sin(bearing)
        val tz = e[2] * cos(bearing) + nn[2] * sin(bearing)
        out[0] = x * cos(angle) + tx * sin(angle)
        out[1] = y * cos(angle) + ty * sin(angle)
        out[2] = z * cos(angle) + tz * sin(angle)
    }

    /** East (along the parallel) and north at unit direction u. */
    private fun frame(u: DoubleArray, east: DoubleArray, north: DoubleArray) {
        var ex = u[2]
        var ey = 0.0
        var ez = -u[0]
        val l = sqrt(ex * ex + ey * ey + ez * ez)
        ex /= l; ey /= l; ez /= l
        east[0] = ex; east[1] = ey; east[2] = ez
        north[0] = u[1] * ez - u[2] * ey
        north[1] = u[2] * ex - u[0] * ez
        north[2] = u[0] * ey - u[1] * ex
    }

    // ------------------------------------------------------------------ rendering

    private class Sky(gl: Gl) {
        val stars = GpuMesh(gl, StarField.build(4500), intArrayOf(3, 4))
        val prog = ShaderProgram(gl, "stars", Shaders.starVs, Shaders.starFs)
        fun dispose() {
            stars.dispose(); prog.dispose()
        }
    }

    private class Shot {
        var visible = 0
        var drawCalls = 0
        var triangles = 0L
        var slices = 0
        var renderMs = 0.0
        var hidden = false
    }

    /** One frame: stars, impostor (surface hidden once the terrain covers it), terrain slices. */
    private fun render(gl: Gl, sc: Scene, sky: Sky, w: Int, h: Int, time: Float, out: Shot) {
        val c = sc.camera
        c.update(w, h)
        val t0 = System.nanoTime()
        gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
        gl.viewport(0, 0, w, h)
        gl.depthMask(true)
        gl.clearColor(0f, 0f, 0f, 1f)
        gl.clear(GL.COLOR_BUFFER_BIT or GL.DEPTH_BUFFER_BIT)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.CULL_FACE)
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE)
        sky.prog.use()
        gl.uniformMatrix4fv(sky.prog.u("u_viewProj"), c.viewProj.m, 0)
        gl.uniform1f(sky.prog.u("u_pxScale"), h / 1080f)
        gl.uniform1f(sky.prog.u("u_bright"), 1f - sc.bodies.skyFade())
        sky.stars.draw(GL.POINTS)
        gl.disable(GL.BLEND)
        val hidden = sc.planet.surfaceReady()
        sc.bodies.setSurfaceHidden(1, hidden, sc.planet.field.minRadius)
        sc.bodies.render(c, time, 1f)
        gl.viewport(0, 0, w, h)
        sc.renderer.prepare(sc.gpu, c)
        sc.renderer.drawSlices(sc.gpu, c, sc.light, time)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.CULL_FACE)
        GLES20.glFinish()
        out.renderMs = (System.nanoTime() - t0) / 1e6
        out.visible = sc.renderer.visibleCount
        out.drawCalls = sc.renderer.drawCalls
        out.triangles = sc.renderer.trianglesDrawn
        out.slices = sc.renderer.sliceCount
        out.hidden = hidden
    }

    /** Camera above the target at [alt] m above the floor, looking down (tilted 12° north) or to the horizon (north). */
    private fun placeCamera(sc: Scene, alt: Double, view: String) {
        val u = sc.target
        val floor = sc.planet.surface.floorAt(u[0], u[1], u[2])
        val r = floor + alt
        sc.camera.pos.set(sc.centre.x + u[0] * r, sc.centre.y + u[1] * r, sc.centre.z + u[2] * r)
        val n = sc.north
        val pitch = if (view == "down") {
            -(90.0 - 12.0) * PI / 180
        } else {
            val dip = acos(min(1.0, sc.planet.radius / (sc.planet.radius + alt)))
            -dip - sc.camera.fovY / 6.0
        }
        val fx = n[0] * cos(pitch) + u[0] * sin(pitch)
        val fy = n[1] * cos(pitch) + u[1] * sin(pitch)
        val fz = n[2] * cos(pitch) + u[2] * sin(pitch)
        sc.camera.rot.lookRotation(fx.toFloat(), fy.toFloat(), fz.toFloat(), u[0].toFloat(), u[1].toFloat(), u[2].toFloat())
    }

    /** Runs frames (update + uploads) until every wanted tile is resident or [maxMs] passed. */
    private fun settle(sc: Scene, maxMs: Long): Int {
        val end = System.currentTimeMillis() + maxMs
        var frames = 0
        var quiet = 0
        while (System.currentTimeMillis() < end) {
            sc.camera.update(1, 1)
            sc.planet.update(sc.camera.pos, 1.0 / 60)
            sc.renderer.prepare(sc.gpu, sc.camera)
            frames++
            if (sc.planet.tree.wantedCount == 0 && sc.planet.pool.idle()) {
                if (++quiet >= 3) break
            } else {
                quiet = 0
            }
            Thread.sleep(1)
        }
        return frames
    }

    // ------------------------------------------------------------------ "terrain"

    /** Debug filters (environment): TERRAIN_PLANETS=earth,luna  TERRAIN_QUALITIES=2  TERRAIN_ALTS=0,3,8  TERRAIN_DESCENT=0. */
    private fun envList(name: String): List<String>? = System.getenv(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

    fun run(env: ScenarioEnv) {
        val opts = env.opts
        val onlyPlanets = envList("TERRAIN_PLANETS")
        val qualities = envList("TERRAIN_QUALITIES")?.map { it.toInt() }?.toIntArray() ?: intArrayOf(2, 0)
        val altIdx = envList("TERRAIN_ALTS")?.map { it.toInt() }
        val views = envList("TERRAIN_VIEWS") ?: listOf("down", "horizon")
        val doDescent = System.getenv("TERRAIN_DESCENT") != "0"
        val doSeams = System.getenv("TERRAIN_SEAMS") != "0"
        val planets = PLANETS.filter { onlyPlanets == null || it.label in onlyPlanets }
        val r = env.report
        val outDir = opts.outDir
        val w = opts.width
        val h = opts.height
        GlSession(env, debug = opts.glDebug).use { s ->
            val gl = s.gl
            val sky = Sky(gl)
            for (quality in qualities) {
                val qn = if (quality == 2) "high" else "low"
                val pool = TileWorkerPool(TileWorkerPool.threadsFor(quality), TileWorkerPool.stagingFor(quality))
                for (tp in planets) {
                    val sc = Scene(env, gl, tp, quality, pool, bake = true)
                    val u = sc.target
                    r.note("%s %s: style %s (%s), Lmax %d, target (%.4f, %.4f, %.4f), floor %.1f m above R".format(
                        tp.label, qn, sc.planet.style.key, sc.planet.profile.title, sc.planet.maxLevel, u[0], u[1], u[2],
                        sc.planet.surface.floorAt(u[0], u[1], u[2]) - tp.radius))
                    val shots = ArrayList<File>()
                    for ((ai, alt) in ALTITUDES.withIndex()) {
                        if (altIdx != null && ai !in altIdx) continue
                        for (view in views) {
                            placeCamera(sc, alt, view)
                            val t0 = System.nanoTime()
                            val frames = settle(sc, 20_000)
                            val settleMs = (System.nanoTime() - t0) / 1e6
                            val shot = Shot()
                            render(gl, sc, sky, w, h, 0f, shot)
                            s.drainErrors(0)
                            val name = "terrain-$qn-${tp.label}-${altLabel(alt)}-$view"
                            val f = s.screenshot(name)
                            shots += f
                            val t = sc.planet.tree
                            val alt2 = sc.planet.surface.altitudeAt(sc.camera.pos)
                            r.note("  %-34s alt %.0f m: settle %d frames %.0f ms, resident %d drawn %d visible %d, %d draws %.2f M tris, %d slices, render %.0f ms, impostor surface %s".format(
                                name, alt2, frames, settleMs, t.residentCount, t.drawCount, shot.visible, shot.drawCalls, shot.triangles / 1e6,
                                shot.slices, shot.renderMs, if (shot.hidden) "hidden" else "shown"))
                        }
                    }
                    montage(shots, File(outDir, "terrain-$qn-${tp.label}-montage.png"), 4)
                    if (quality == 2) sceneShot(env, s, sc, w, h)
                    sc.dispose()
                    if (quality == 2 && doSeams && tp.label != "mars") seamShots(env, s, gl, sky, tp, pool, w, h)
                }
                pool.shutdown()
            }
            // flight-speed descents (HIGH), real time, nothing waits for completion
            val pool = TileWorkerPool(TileWorkerPool.threadsFor(2), TileWorkerPool.stagingFor(2))
            for (tp in if (doDescent) planets else emptyList()) {
                val sc = Scene(env, gl, tp, 2, pool, bake = true)
                descent(env, s, sc, sky, w, h, "terrain-flight-${tp.label}", null)
                sc.dispose()
            }
            pool.shutdown()
            sky.dispose()
        }
    }

    /**
     * Cube-sphere seams: the camera above a cube corner (or face edge, [seamTarget]) at 3 km,
     * 600 m and 120 m, down and to the horizon, plus the normals view at 600 m: tiles of up to
     * three faces meet under the camera.
     */
    private fun seamShots(env: ScenarioEnv, s: GlSession, gl: Gl, sky: Sky, tp: TestPlanet, pool: TileWorkerPool, w: Int, h: Int) {
        val r = env.report
        val sc = Scene(env, gl, tp, 2, pool, bake = false, seam = true)
        val u = sc.target
        val f = com.starwake.core.terrain.CubeSphere.faceOf(u[0], u[1], u[2])
        val ab = DoubleArray(2)
        com.starwake.core.terrain.CubeSphere.coords(f, u[0], u[1], u[2], ab)
        r.note("  %s seam target (%.4f, %.4f, %.4f): face %d at (%.4f, %.4f)%s".format(tp.label, u[0], u[1], u[2], f, ab[0], ab[1],
            if (kotlin.math.abs(kotlin.math.abs(ab[0]) - 1) < 1e-9 && kotlin.math.abs(kotlin.math.abs(ab[1]) - 1) < 1e-9) ", a cube corner (three faces)" else ", a face edge"))
        val shots = ArrayList<File>()
        for (alt in doubleArrayOf(3_000.0, 600.0, 120.0)) for (view in listOf("down", "horizon")) {
            placeCamera(sc, alt, view)
            settle(sc, 20_000)
            val shot = Shot()
            render(gl, sc, sky, w, h, 0f, shot)
            s.drainErrors(0)
            val name = "terrain-high-${tp.label}-seam-${altLabel(alt)}-$view"
            shots += s.screenshot(name)
            r.note("  %-36s resident %d, %d draws %.2f M tris".format(name, sc.planet.tree.residentCount, shot.drawCalls, shot.triangles / 1e6))
        }
        placeCamera(sc, 600.0, "down")
        settle(sc, 20_000)
        sc.renderer.debugView = 3
        render(gl, sc, sky, w, h, 0f, Shot())
        shots += s.screenshot("terrain-high-${tp.label}-seam-00600m-down-normals")
        sc.renderer.debugView = 0
        montage(shots, File(env.opts.outDir, "terrain-high-${tp.label}-seam-montage.png"), 4)
        sc.dispose()
    }

    /**
     * The terrain inside the game's own render pass: a [SceneRenderer] with this planet as the
     * terrain body, the ship parked 2 m above the ground (levelled to it) and the chase camera
     * behind: the ship and the terrain share the near depth slice.
     */
    private fun sceneShot(env: ScenarioEnv, s: GlSession, sc: Scene, w: Int, h: Int) {
        val gl = s.gl
        val platform = DesktopPlatform(env.opts.assetsDir, null)
        val content = Content(String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8), platform.readAsset("font/ui.sdf"), sc.system)
        val sr = SceneRenderer(gl, content, 2)
        // one GPU side per planet: the tiles of the scenario's own renderer are dropped and stream
        // again into the scene's
        sc.planet.tree.clear()
        val gpu = sr.attachTerrain(sc.planet)
        val f = SceneFrame()
        f.terrain = gpu
        f.terrainBody = 1
        f.terrainLight.setAir(sc.planet.profile, sc.tp.radius)
        f.terrainLight.setSun(sc.sunDir.x, sc.sunDir.y, sc.sunDir.z)
        f.sunDir.set(sc.sunDir)
        f.drawField = false
        val surf = sc.planet.surface
        val u = sc.target
        val n = sc.north
        val floor = surf.floorAt(u[0], u[1], u[2])
        val ship = ShipState()
        ship.pos.set(sc.centre.x + u[0] * (floor + 2.5), sc.centre.y + u[1] * (floor + 2.5), sc.centre.z + u[2] * (floor + 2.5))
        val hit = com.starwake.core.terrain.FloorHit()
        surf.floorHit(ship.pos, 400.0, hit)
        val gu = hit.normal
        // nose north, levelled to the ground normal
        val d = n[0] * gu.x + n[1] * gu.y + n[2] * gu.z
        val fx = (n[0] - gu.x * d).toFloat()
        val fy = (n[1] - gu.y * d).toFloat()
        val fz = (n[2] - gu.z * d).toFloat()
        ship.rot.lookRotation(fx, fy, fz, gu.x, gu.y, gu.z)
        f.ship = ship
        val c = f.camera
        c.pos.set(ship.pos.x - fx * 28.0 + u[0] * 9.0, ship.pos.y - fy * 28.0 + u[1] * 9.0, ship.pos.z - fz * 28.0 + u[2] * 9.0)
        c.rot.lookRotation(
            (ship.pos.x - c.pos.x + fx * 40.0).toFloat(), (ship.pos.y - c.pos.y + fy * 40.0).toFloat(), (ship.pos.z - c.pos.z + fz * 40.0).toFloat(),
            u[0].toFloat(), u[1].toFloat(), u[2].toFloat(),
        )
        c.update(w, h)
        // stream the tiles around the ship (the game would call update every frame)
        val end = System.currentTimeMillis() + 20_000
        var quiet = 0
        while (System.currentTimeMillis() < end) {
            sc.planet.update(c.pos, 1.0 / 60, ship.pos, ship.vel)
            sc.planet.processCompletions(64, gpu)
            if (sc.planet.tree.wantedCount == 0 && sc.planet.pool.idle()) { if (++quiet > 3) break } else quiet = 0
            Thread.sleep(1)
        }
        sc.planet.update(c.pos, 1.0 / 60, ship.pos, ship.vel)
        sr.render(f)
        sr.render(f)
        GLES20.glFinish()
        s.drainErrors(0)
        s.screenshot("terrain-scene-${sc.tp.label}-ship-on-ground")
        env.report.note("  scene (SceneRenderer + terrain + ship): ${sr.terrain?.sliceCount} terrain slices, ${sr.terrain?.drawCalls} terrain draws in the far slices, ship ${"%.1f".format(surf.altitudeAt(ship.pos))} m above the floor (${PlanetSurface.stateName(surf.groundState(ship.pos))})")
        gpu.dispose()
        sr.dispose()
    }

    // ------------------------------------------------------------------ descent

    /** Speed law (DECISIONS 2/3): far law 1000 + h/2.5 m/s, near band clamp(0.35·h, 260, 4000) below 11.4 km. */
    fun speedLimit(h: Double): Double {
        var v = 1000.0 + h / 2.5
        if (h < 11_400.0) v = min(v, (0.35 * h).coerceIn(260.0, 4000.0))
        return v
    }

    /** Flight-path angle below the horizon: 70° high up, 15° near the ground. */
    private fun glide(h: Double): Double {
        val t = ((h - 1000.0) / (200_000.0 - 1000.0)).coerceIn(0.0, 1.0)
        return (15.0 + 55.0 * t * t * (3 - 2 * t)) * PI / 180
    }

    /** Per-frame record of a descent. */
    class DescentLog(n: Int) {
        val alt = DoubleArray(n)
        val speed = DoubleArray(n)
        val updateNs = LongArray(n)
        val prepareNs = LongArray(n)
        val drawNs = LongArray(n)
        val alloc = LongArray(n)
        val uploads = IntArray(n)
        val resident = IntArray(n)
        val drawn = IntArray(n)
        val visible = IntArray(n)
        val drawCalls = IntArray(n)
        val triangles = LongArray(n)
        val queue = IntArray(n)
        val leafLevel = IntArray(n)
        val ground = IntArray(n)
        var count = 0
    }

    /**
     * A real-time descent along a great circle ending above the target at 20 m: per frame the
     * trajectory advances 1/60 s, the terrain updates and uploads what the workers finished, and
     * the frame then sleeps until 16.7 ms of wall time have passed (the workers get exactly the time
     * a phone frame would give them). Screenshots at the [ALTITUDES] crossings pause the workers
     * while llvmpipe renders. [meter] (perf) records the per-frame engine costs; with [noRender]
     * nothing is drawn.
     */
    private fun descent(env: ScenarioEnv, s: GlSession?, sc: Scene, sky: Sky?, w: Int, h: Int, prefix: String, log: DescentLog?, gl: Gl? = null): DescentLog {
        val r = env.report
        val planet = sc.planet
        val surf = planet.surface
        val R = sc.tp.radius
        // dry run: total ground distance from 20,000 km to 20 m
        val dt = 1.0 / 60
        var alt = ALTITUDES[0]
        var ground = 0.0
        var steps = 0
        while (alt > 20.0 && steps < 100_000) {
            val v = speedLimit(alt)
            val g = glide(alt)
            alt -= v * sin(g) * dt
            ground += v * cos(g) * dt
            steps++
        }
        // start point: the target moved back along a great circle towards the south-west
        val bearing = PI + 0.6
        val start = DoubleArray(3)
        tangentOffset(sc.target[0], sc.target[1], sc.target[2], bearing, ground / R, start)
        // path axis: the rotation that carries start into the target along the bearing's great
        // circle, d × target with d the bearing direction at the target (start × target would flip
        // once the arc passes half the circumference, as the 7,500 km descent does on Luna)
        val bx = sc.east[0] * cos(bearing) + sc.north[0] * sin(bearing)
        val by = sc.east[1] * cos(bearing) + sc.north[1] * sin(bearing)
        val bz = sc.east[2] * cos(bearing) + sc.north[2] * sin(bearing)
        val ax = by * sc.target[2] - bz * sc.target[1]
        val ay = bz * sc.target[0] - bx * sc.target[2]
        val az = bx * sc.target[1] - by * sc.target[0]
        val al = sqrt(ax * ax + ay * ay + az * az)
        val kx = ax / al
        val ky = ay / al
        val kz = az / al
        val rec = log ?: DescentLog(steps + 10)
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val shipVel = Vec3()
        val shipPos = DVec3()
        alt = ALTITUDES[0]
        var travelled = 0.0
        var nextShot = 0
        var frame = 0
        val shot = Shot()
        val pos = DoubleArray(3)
        planet.pool.resetStats()
        val wall0 = System.nanoTime()
        var pausedNs = 0L
        r.note("$prefix: ${"%.0f".format(steps * dt)} s descent, ${"%.0f".format(ground / 1000)} km downrange, $steps frames")
        while (alt > 20.0 && frame < steps + 5) {
            val f0 = System.nanoTime()
            val v = speedLimit(alt)
            val g = glide(alt)
            alt -= v * sin(g) * dt
            if (alt < 20.0) alt = 20.0
            travelled += v * cos(g) * dt
            // position along the great circle
            val ang = travelled / R
            rotate(start, kx, ky, kz, ang, pos)
            // the floor under the camera (exact query) sets the radius: altitudes are above the ground
            val floor = surf.floorAt(pos[0], pos[1], pos[2])
            val rr = floor + alt
            sc.camera.pos.set(sc.centre.x + pos[0] * rr, sc.centre.y + pos[1] * rr, sc.centre.z + pos[2] * rr)
            // flight direction: along the great circle (tangent = axis × pos), pitched down by the glide angle
            val tx = ky * pos[2] - kz * pos[1]
            val ty = kz * pos[0] - kx * pos[2]
            val tz = kx * pos[1] - ky * pos[0]
            val vx = tx * cos(g) - pos[0] * sin(g)
            val vy = ty * cos(g) - pos[1] * sin(g)
            val vz = tz * cos(g) - pos[2] * sin(g)
            shipVel.set((vx * v).toFloat(), (vy * v).toFloat(), (vz * v).toFloat())
            shipPos.set(sc.camera.pos)
            // look ahead and down: between the flight path and the horizon
            val dip = acos(min(1.0, R / (R + alt)))
            val look = -max(dip + sc.camera.fovY / 6.0, g * 0.6)
            val fx = tx * cos(look) + pos[0] * sin(look)
            val fy = ty * cos(look) + pos[1] * sin(look)
            val fz = tz * cos(look) + pos[2] * sin(look)
            sc.camera.rot.lookRotation(fx.toFloat(), fy.toFloat(), fz.toFloat(), pos[0].toFloat(), pos[1].toFloat(), pos[2].toFloat())
            sc.camera.update(w, h)
            // the frame's engine work (game thread)
            val a0 = mx.currentThreadAllocatedBytes
            val u0 = System.nanoTime()
            planet.update(sc.camera.pos, dt, shipPos, shipVel, 16.7f)
            val u1 = System.nanoTime()
            sc.renderer.prepare(sc.gpu, sc.camera)
            val u2 = System.nanoTime()
            var d1 = u2
            if (gl != null && s == null) {
                // perf on a no-op GL: submit the draws too
                sc.renderer.drawSlices(sc.gpu, sc.camera, sc.light, frame / 60f)
                d1 = System.nanoTime()
            }
            val a1 = mx.currentThreadAllocatedBytes
            val i = rec.count
            if (i < rec.alt.size) {
                rec.alt[i] = alt
                rec.speed[i] = v
                rec.updateNs[i] = u1 - u0
                rec.prepareNs[i] = u2 - u1
                rec.drawNs[i] = d1 - u2
                rec.alloc[i] = a1 - a0
                rec.uploads[i] = planet.lastUploads
                rec.resident[i] = planet.tree.residentCount
                rec.drawn[i] = planet.tree.drawCount
                rec.visible[i] = sc.renderer.visibleCount
                rec.drawCalls[i] = sc.renderer.drawCalls
                rec.triangles[i] = sc.renderer.trianglesDrawn
                rec.queue[i] = planet.pool.queued()
                val leaf = planet.tree.leafAt(pos[0], pos[1], pos[2])
                rec.leafLevel[i] = if (leaf >= 0) planet.tree.level[leaf] else -1
                rec.ground[i] = surf.groundState(shipPos)
                rec.count = i + 1
            }
            // screenshots at the altitude marks (workers paused while llvmpipe renders)
            if (s != null && sky != null && nextShot < ALTITUDES.size && alt <= ALTITUDES[nextShot] * 1.0001) {
                val p0 = System.nanoTime()
                planet.pool.pause()
                render(s.gl, sc, sky, w, h, frame / 60f, shot)
                s.drainErrors(frame.toLong())
                val name = "$prefix-${"%02d".format(nextShot)}-${altLabel(ALTITUDES[nextShot])}"
                s.screenshot(name)
                val t = planet.tree
                r.note("  %-36s t=%.2f s alt %.0f m v %.0f m/s: resident %d drawn %d visible %d, queue %d, leaf under ship L%d (%s), %d draws %.2f M tris, %d slices".format(
                    name, frame * dt, alt, v, t.residentCount, t.drawCount, shot.visible, planet.pool.queued(), rec.leafLevel[max(0, rec.count - 1)],
                    PlanetSurface.stateName(rec.ground[max(0, rec.count - 1)]), shot.drawCalls, shot.triangles / 1e6, shot.slices))
                planet.pool.resume()
                nextShot++
                pausedNs += System.nanoTime() - p0
            }
            frame++
            // the rest of the 16.7 ms frame belongs to the workers
            val spent = System.nanoTime() - f0
            val left = 16_666_667L - spent
            if (left > 0) Thread.sleep(left / 1_000_000, (left % 1_000_000).toInt())
        }
        // the last view: still flying (20 m), one more shot after the final frame
        if (s != null && sky != null && nextShot < ALTITUDES.size) {
            planet.pool.pause()
            render(s.gl, sc, sky, w, h, frame / 60f, shot)
            s.screenshot("$prefix-${"%02d".format(nextShot)}-${altLabel(ALTITUDES[nextShot])}")
            planet.pool.resume()
        }
        val wall = (System.nanoTime() - wall0 - pausedNs) / 1e9
        val built = planet.pool.tilesBuilt
        r.note("  $prefix: ${frame} frames in %.1f s wall (excl. shots), %d tiles built (%.0f/s), worker %.2f ms/tile avg, %.2f max".format(
            wall, built, built / wall, planet.pool.buildNanosTotal / 1e6 / max(1L, built), planet.pool.buildNanosMax / 1e6))
        // the leaf under the ship against the level the split rule asks for at that height (one level of
        // lag allowed: the split happens when the children are in), and the spec's flyover check
        // (below 300 m the finest level, after the first second there)
        var behind = 0
        var lowFrames = 0
        var coarse = 0
        var firstLow = -1
        for (k in 0 until rec.count) {
            val want = expectedLevel(planet, rec.alt[k])
            if (rec.leafLevel[k] < want - 1) behind++
            if (rec.alt[k] < 300.0) {
                if (firstLow < 0) firstLow = k
                if (k - firstLow >= 60) {
                    lowFrames++
                    if (rec.leafLevel[k] != planet.maxLevel) coarse++
                }
            }
        }
        r.note("  $prefix: leaf under the ship more than one level behind the split rule in %d of %d frames; below 300 m (after 1 s): %d frames, not L%d in %d".format(
            behind, rec.count, lowFrames, planet.maxLevel, coarse))
        return rec
    }

    /** The level the split rule selects for the tile under a camera [alt] m above it: d < ρ·edge(L − 1). */
    private fun expectedLevel(planet: TerrainPlanet, alt: Double): Int {
        val v = kotlin.math.ln(PI * planet.radius * planet.rho / (2.0 * max(alt, 0.1))) / kotlin.math.ln(2.0)
        return (kotlin.math.floor(v).toInt() + 1).coerceIn(0, planet.maxLevel)
    }

    private fun rotate(v: DoubleArray, kx: Double, ky: Double, kz: Double, a: Double, out: DoubleArray) {
        val c = cos(a)
        val s = sin(a)
        val d = kx * v[0] + ky * v[1] + kz * v[2]
        val cx = ky * v[2] - kz * v[1]
        val cy = kz * v[0] - kx * v[2]
        val cz = kx * v[1] - ky * v[0]
        out[0] = v[0] * c + cx * s + kx * d * (1 - c)
        out[1] = v[1] * c + cy * s + ky * d * (1 - c)
        out[2] = v[2] * c + cz * s + kz * d * (1 - c)
    }

    // ------------------------------------------------------------------ "terrain-perf"

    fun perf(env: ScenarioEnv) {
        val r = env.report
        val opts = env.opts
        val ea = ManagementFactory.getRuntimeMXBean().inputArguments
        if ("-XX:-DoEscapeAnalysis" !in ea) r.note("note: escape analysis is on; for ART-like allocation counts run with -Pnoea")
        // 1) the engine's own CPU cost: no-op GL, real workers in real time
        for (quality in intArrayOf(2, 0)) {
            val qn = if (quality == 2) "HIGH" else "LOW"
            val pool = TileWorkerPool(TileWorkerPool.threadsFor(quality), TileWorkerPool.stagingFor(quality))
            for (tp in PLANETS) {
                val gl = NoopGl()
                val sc = Scene(env, gl, tp, quality, pool, bake = false)
                // JIT warm-up of the selection, culling and draw submission (not counted)
                for (k in 0 until 3) {
                    placeCamera(sc, ALTITUDES[k * 3], "horizon")
                    sc.camera.update(opts.width, opts.height)
                    sc.planet.update(sc.camera.pos, 1.0 / 60)
                    sc.renderer.prepare(sc.gpu, sc.camera)
                    sc.renderer.drawSlices(sc.gpu, sc.camera, sc.light, 0f)
                }
                sc.planet.tree.clear()
                val log = descent(env, null, sc, null, opts.width, opts.height, "perf-$qn-${tp.label}", null, gl)
                summarize(env, "$qn ${tp.label}", sc, log, pool, gl)
                if (quality == 2) queryCost(env, sc)
                sc.dispose()
            }
            pool.shutdown()
        }
        // 2) llvmpipe frames at a few altitudes (software rasteriser: not a phone GPU)
        GlSession(env, debug = false).use { s ->
            val sky = Sky(s.gl)
            val pool = TileWorkerPool(3, 32)
            val sc = Scene(env, s.gl, PLANETS[0], 2, pool, bake = false)
            for (alt in doubleArrayOf(300_000.0, 15_000.0, 600.0, 20.0)) {
                placeCamera(sc, alt, "horizon")
                settle(sc, 20_000)
                val shot = Shot()
                render(s.gl, sc, sky, opts.width, opts.height, 0f, shot)
                var best = Double.MAX_VALUE
                for (k in 0 until 3) {
                    render(s.gl, sc, sky, opts.width, opts.height, 0f, shot)
                    best = min(best, shot.renderMs)
                }
                for (mode in intArrayOf(TerrainRenderer.DEPTH_CLEAR, TerrainRenderer.DEPTH_RANGE)) {
                    sc.renderer.depthMode = mode
                    var b = Double.MAX_VALUE
                    for (k in 0 until 3) {
                        render(s.gl, sc, sky, opts.width, opts.height, 0f, shot)
                        b = min(b, shot.renderMs)
                    }
                    r.note("llvmpipe earth %.0f m, depth mode %s: %.1f ms/frame (stars + impostor + terrain, %d slices, %d draws, %.2f M tris)".format(
                        alt, if (mode == 0) "CLEAR" else "RANGE", b, shot.slices, shot.drawCalls, shot.triangles / 1e6))
                }
                sc.renderer.depthMode = TerrainRenderer.DEPTH_CLEAR
                s.screenshot("terrain-perf-earth-${altLabel(alt)}")
            }
            sc.dispose()
            pool.shutdown()
            sky.dispose()
        }
    }

    /**
     * The ground queries per physics substep (§9.10): a low pass at 260 m/s sampled at 240 Hz
     * (consecutive points 1.1 m apart, 2 m above the floor) and scattered points within ±20 km
     * (every query collects its own features). Time per call and bytes allocated.
     */
    private fun queryCost(env: ScenarioEnv, sc: Scene) {
        val r = env.report
        val surf = sc.planet.surface
        val u = sc.target
        val e = sc.east
        val R = sc.tp.radius
        val n = 20_000
        val pts = DoubleArray(n * 3)
        val dir = DoubleArray(3)
        for (k in 0 until n) {
            // along the parallel, 260/240 m per substep
            val a = k * (260.0 / 240.0) / R
            dir[0] = u[0] * cos(a) + e[0] * sin(a)
            dir[1] = u[1] * cos(a) + e[1] * sin(a)
            dir[2] = u[2] * cos(a) + e[2] * sin(a)
            val rr = surf.floorAt(dir[0], dir[1], dir[2]) + 2.0
            pts[k * 3] = sc.centre.x + dir[0] * rr
            pts[k * 3 + 1] = sc.centre.y + dir[1] * rr
            pts[k * 3 + 2] = sc.centre.z + dir[2] * rr
        }
        val rnd = java.util.Random(7)
        val m = 2_000
        val cold = DoubleArray(m * 3)
        for (k in 0 until m) {
            val a = (rnd.nextDouble() * 2 - 1) * 20_000.0 / R
            val b = (rnd.nextDouble() * 2 - 1) * 20_000.0 / R
            val nn = sc.north
            var x = u[0] + e[0] * a + nn[0] * b
            var y = u[1] + e[1] * a + nn[1] * b
            var z = u[2] + e[2] * a + nn[2] * b
            val l = sqrt(x * x + y * y + z * z)
            x /= l; y /= l; z /= l
            val rr = surf.floorAt(x, y, z) + 2.0
            cold[k * 3] = sc.centre.x + x * rr
            cold[k * 3 + 1] = sc.centre.y + y * rr
            cold[k * 3 + 2] = sc.centre.z + z * rr
        }
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val p = DVec3()
        val hit = com.starwake.core.terrain.FloorHit()
        val c = PlanetSurface.Contact()
        val vel = Vec3()
        var sink = 0.0
        val names = arrayOf("altitudeAt", "floorHit (point, normal)", "groundState", "contact", "floorHit, scattered points")
        val ns = DoubleArray(names.size)
        val bytes = LongArray(names.size)
        for (rep in 0 until 2) {     // the first round warms the JIT
            for (q in names.indices) {
                val count = if (q == 4) m else n
                val src = if (q == 4) cold else pts
                val a0 = mx.currentThreadAllocatedBytes
                val t0 = System.nanoTime()
                for (k in 0 until count) {
                    p.set(src[k * 3], src[k * 3 + 1], src[k * 3 + 2])
                    when (q) {
                        0 -> sink += surf.altitudeAt(p)
                        1, 4 -> if (surf.floorHit(p, PlanetSurface.GROUND_PROBE, hit)) sink += hit.altitude
                        2 -> sink += surf.groundState(p)
                        else -> {
                            vel.set(0f, -3f, 0f)
                            if (surf.contact(p, vel, 2.5, c)) sink += c.altitude
                        }
                    }
                }
                ns[q] = (System.nanoTime() - t0).toDouble() / count
                bytes[q] = mx.currentThreadAllocatedBytes - a0
            }
        }
        for (q in names.indices) {
            r.note("    query %-28s %7.2f us/call, %d B allocated over %d calls".format(names[q], ns[q] / 1000.0, bytes[q], if (q == 4) m else n))
        }
        if (sink == 42.0) r.note("")
    }

    private fun summarize(env: ScenarioEnv, label: String, sc: Scene, log: DescentLog, pool: TileWorkerPool, gl: NoopGl) {
        val r = env.report
        val n = log.count
        if (n == 0) return
        fun stats(a: LongArray): String {
            val s = a.copyOfRange(0, n).sorted()
            return "avg %.3f p50 %.3f p95 %.3f max %.3f ms".format(s.average() / 1e6, s[n / 2] / 1e6, s[((n - 1) * 0.95).toInt()] / 1e6, s.last() / 1e6)
        }
        r.note("[$label] $n frames")
        r.note("    update (quadtree, priorities, submit, eviction): ${stats(log.updateNs)}")
        r.note("    prepare (uploads, culling, slices):            ${stats(log.prepareNs)}")
        r.note("    draw submission (no-op GL):                    ${stats(log.drawNs)}")
        val total = LongArray(n) { log.updateNs[it] + log.prepareNs[it] + log.drawNs[it] }
        r.note("    game thread total:                             ${stats(total)}")
        val alloc = log.alloc.copyOfRange(0, n)
        val allocFrames = alloc.indices.filter { alloc[it] > 0 }.take(8).joinToString(", ") { "#$it (${alloc[it]} B)" }
        r.note("    allocated on the game thread: %d B total, %d of %d frames allocated, max %d B%s".format(
            alloc.sum(), alloc.count { it > 0 }, n, alloc.maxOrNull() ?: 0, if (allocFrames.isEmpty()) "" else "; frames $allocFrames"))
        val up = log.uploads.copyOfRange(0, n)
        r.note("    uploads: %d total, max %d/frame".format(up.sum(), up.maxOrNull() ?: 0))
        r.note("    tiles resident max %d, drawn max %d, visible max %d, draw calls max %d, triangles max %.2f M, queue max %d".format(
            log.resident.copyOfRange(0, n).max(), log.drawn.copyOfRange(0, n).max(), log.visible.copyOfRange(0, n).max(),
            log.drawCalls.copyOfRange(0, n).max(), log.triangles.copyOfRange(0, n).max() / 1e6, log.queue.copyOfRange(0, n).max()))
        val slotsMb = sc.planet.tree.slotCapacity * TileFormat.TILE_BYTES / 1e6
        val usedMb = sc.gpu.gpuBytes / 1e6
        val nodes = sc.planet.tree.nodeCapacity
        val cpuMb = (nodes * (4 * 8 + 14 * 8 + 4) + pool.staging.size * TileFormat.TILE_BYTES + pool.threads * 400_000) / 1e6
        r.note("    memory: GPU tile VBOs %.1f MB used of %.1f MB (%d slots x %d B), CPU node pool + staging + builders ~%.1f MB".format(
            usedMb, slotsMb, sc.planet.tree.slotCapacity, TileFormat.TILE_BYTES, cpuMb))
        r.note("    workers: %d threads, %d tiles, %.2f ms/tile avg, %.2f ms max; no-op GL calls %d".format(
            pool.threads, pool.tilesBuilt, pool.buildNanosTotal / 1e6 / max(1L, pool.tilesBuilt), pool.buildNanosMax / 1e6, gl.calls))
    }

    // ------------------------------------------------------------------ montage

    /** A contact sheet of [files] (columns [cols]) at a quarter size, for review. */
    fun montage(files: List<File>, out: File, cols: Int) {
        if (files.isEmpty()) return
        val first = ImageIO.read(files[0])
        val tw = first.width / 4
        val th = first.height / 4
        val rows = (files.size + cols - 1) / cols
        val img = BufferedImage(tw * cols, th * rows, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        for ((i, f) in files.withIndex()) {
            val src = ImageIO.read(f)
            g.drawImage(src, (i % cols) * tw, (i / cols) * th, tw, th, null)
            g.color = java.awt.Color.YELLOW
            g.drawString(f.nameWithoutExtension, (i % cols) * tw + 6, (i / cols) * th + 16)
        }
        g.dispose()
        ImageIO.write(img, "png", out)
        println("  [montage] ${out.path}")
    }
}
