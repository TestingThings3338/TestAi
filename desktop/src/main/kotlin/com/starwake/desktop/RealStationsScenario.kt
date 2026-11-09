package com.starwake.desktop

import com.starwake.core.content.Content
import com.starwake.core.content.ShipModel
import com.starwake.core.content.StationModel
import com.starwake.core.game.GearRig
import com.starwake.core.gfx.SceneFrame
import com.starwake.core.gfx.SceneRenderer
import com.starwake.core.gfx.ShipRenderer
import com.starwake.core.gfx.StationRenderer
import com.starwake.core.gfx.GL
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.DVec3
import com.starwake.core.math.Mat4
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.station.StationInstance
import org.lwjgl.opengles.GLES20
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * "stations-real": the six Roblox stations and the construction site from the converted assets
 * (assets/stations, tools/roblox/convert_stations.py) through StationRenderer, over the real
 * background from SceneRenderer, with a VARROW parked on pad 1 for scale: the approach, the mouth,
 * a side and a rear view, inside the hangar, at the pads, then at the distances of detail levels 1
 * and 2 and the 60 km marker. The site is also shown with its stages part-built (orange hologram).
 * Logs load times, triangles and draw calls per detail level.
 */
object RealStationsScenario {
    private class View(
        val name: String,
        /** Camera eye and target in station space (metres). */
        val ex: Float, val ey: Float, val ez: Float,
        val tx: Float, val ty: Float, val tz: Float,
        val fov: Float = 60f,
        /** Camera up vector in station space (top-down views need one other than +Y). */
        val ux: Float = 0f, val uy: Float = 1f, val uz: Float = 0f,
        /**
         * Worst case for the paint's depth: the scene's near slice (near plane 0.5 m) with half the
         * depth range, as SceneRenderer uses when it splits the scene.
         */
        val nearSlice: Boolean = false,
    )

    /** Sol station names and ShipStationLore accents per model (MERIDIAN on the Spire, ...). */
    private val SOL = mapOf(
        "SpireStation" to Triple("MERIDIAN STATION", intArrayOf(80, 170, 255), 1),
        "FoundryStation" to Triple("TRANQUILITY YARDS", intArrayOf(255, 170, 70), 2),
        "HollowStation" to Triple("ARES DEEP", intArrayOf(255, 96, 70), 3),
        "RefineryStation" to Triple("GALILEAN REFINERY", intArrayOf(250, 210, 90), 4),
        "GardenStation" to Triple("TITAN HYDROPONICS", intArrayOf(110, 230, 140), 5),
        "RingStation" to Triple("HELIOS FORGE", intArrayOf(255, 130, 40), 6),
        "Site" to Triple("LODESTAR ANCHORAGE", intArrayOf(255, 122, 0), 7),
    )

    fun run(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val gl = s.gl
            val w = opts.width
            val h = opts.height
            val platform = DesktopPlatform(opts.assetsDir)
            val content = Content(String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8), platform.readAsset("font/ui.sdf"))
            val scene = SceneRenderer(gl, content, 2)
            val envSize = 512
            val envTex = StationsScenario.bakeEnvironment(gl, envSize)
            val envLod = ln(envSize.toFloat()) / ln(2f) - 1.5f
            val stations = StationRenderer(gl, content.font, content.fontAtlas)
            val ships = ShipRenderer(gl)
            val ship = ShipModel.load("varrow") { platform.readAsset(it) }
            ships.setModel(ship)
            val rig = GearRig()
            rig.setModel(ship, down = true)
            s.drainErrors(0)

            val f = SceneFrame()
            f.ship = null
            f.quality = 2
            f.pxScale = h / 1080f
            f.dustIntensity = 0f
            f.drawField = false
            val home = content.system.home
            // 5,000 km from the old home position: no stand-in station or rock field in the frame,
            // the ringed giant in the sky behind some of the views
            val stationPos = DVec3(home.x, home.y + 5.0e6, home.z)
            // the mouth (-Z) turned towards the sun, 35 degrees off, so faces read in light and shade
            val sun = Vec3().set(f.sunDir)
            val rot = Quat().setAxisAngle(0f, 1f, 0f, 35f * DEG2RAD)
            val fwd = Vec3()
            rot.rotate(sun.x, 0.15f, sun.z, fwd)
            val stationRot = Quat().lookRotation(fwd.x, fwd.y, fwd.z, 0f, 1f, 0f)
            val pose = Mat4()
            val shipPose = Mat4()
            val shipRot = Quat()
            val rel = Vec3()
            val eye = Vec3()
            val tgt = Vec3()
            val tmp = Vec3()
            val padPos = FloatArray(3)
            val padRot = FloatArray(9)
            var frame = 0L

            for (type in StationModel.TYPES.toList() + StationModel.SITE) {
                val t0 = System.nanoTime()
                val model = StationModel.load(type) { platform.readAsset(it) }
                val t1 = System.nanoTime()
                val gpu = stations.upload(model)
                val (name, accent, seed) = SOL.getValue(type)
                val signs = stations.signs(model, name)
                GLES20.glFinish()
                val t2 = System.nanoTime()
                val st = StationInstance(model, seed)
                st.setAccent(accent[0], accent[1], accent[2])
                if (!model.isSite) {
                    st.setPadState(1, StationInstance.PAD_CLAMPED)
                    st.setPadState(2, StationInstance.PAD_ASSIGNED)
                }
                st.update(1.3f, Float.NaN, 0f, 0f)
                val m = model.mesh
                r.note(
                    "%-16s parse %.1f ms, upload + signs %.1f ms; %d vertices, %d KB GPU, triangles LOD0 %d / LOD1 %d / LOD2 %d, %d groups, %d lamp slots, %d colliders, %d lights, %d labels (%d glyphs), radius %.0f m, bounds %.0f x %.0f x %.0f m"
                        .format(
                            type, (t1 - t0) / 1e6, (t2 - t1) / 1e6, m.vertexCount, (m.vertices.size + m.indices.size) / 1024,
                            m.triangles(0), m.triangles(1), m.triangles(2), model.groups.size - 1, model.lamps.size,
                            model.colliders.count, model.lights.size, model.labels.size, signs.glyphs, model.boundingRadius,
                            model.bounds[3] - model.bounds[0], model.bounds[4] - model.bounds[1], model.bounds[5] - model.bounds[2],
                        ),
                )
                check(m.triangles(0) >= m.triangles(1) && m.triangles(1) >= m.triangles(2) && m.triangles(2) > 0) { "$type: LOD triangle counts" }
                // the parked ship on pad 1, nose to the mouth (ShipStation.padPose with the probe's ground height)
                val ground = ship.rbxBounds[6] - ship.eyeY
                val parked = model.padPose(1, ground, padPos, padRot)
                val R = model.boundingRadius
                val views = ArrayList<View>()
                if (!model.isSite) {
                    views += View("approach", -0.45f * R, 0.22f * R, -1.35f * R, 0f, -20f, -250f)
                    views += View("mouth", 0f, 10f, -1150f, 0f, -10f, -370f, 50f)
                    views += View("side", -1.9f * R, 0.45f * R, 0.25f * R, 0f, 0f, 0f)
                    views += View("rear", 1.0f * R, 0.65f * R, 1.75f * R, 0f, 0f, 0f)
                    views += View("hangar", -25f, -30f, -395f, 70f, -62f, 40f, 70f)
                    views += View("pads", -60f, -10f, -240f, 85f, -73f, -100f, 62f)
                    // straight down on pads 1 and 4, screen up = +Z: what a pilot entering the hangar sees
                    views += View("padtop", 0f, 40f, -150f, 0f, -73f, -150f, 80f, 0f, 0f, 1f)
                    views += View("nearslice", -0.3f * R, 0.12f * R, -0.62f * R, 0f, 0.05f * R, 0.2f * R, 65f, nearSlice = true)
                    views += View("lod1", -3500f, 1800f, -5200f, 0f, 0f, 0f, 30f)
                    views += View("lod2", -14000f, 6000f, -22000f, 0f, 0f, 0f, 12f)
                    views += View("marker", -40000f, 15000f, -55000f, 0f, 0f, 0f, 30f)
                } else {
                    views += View("approach", -0.55f * R, 0.3f * R, -1.4f * R, 0f, 0f, 0f)
                    views += View("side", -1.9f * R, 0.45f * R, 0.25f * R, 0f, 0f, 0f)
                    views += View("stages", 1.1f * R, 0.5f * R, -1.3f * R, 0f, 0f, 0f)
                    views += View("complete", 1.1f * R, 0.5f * R, -1.3f * R, 0f, 0f, 0f)
                    views += View("lod1", -3500f, 1800f, -5200f, 0f, 0f, 0f, 30f)
                    views += View("marker", -40000f, 15000f, -55000f, 0f, 0f, 0f, 30f)
                }
                for (v in views) {
                    if (model.isSite) {
                        when (v.name) {
                            "stages" -> st.setSiteStages(1, true)
                            "complete" -> st.setSiteStages(4, false)
                            else -> st.setSiteStages(2, false)
                        }
                    }
                    frame++
                    f.time = 2f + frame / 60f
                    stationRot.rotate(v.ex, v.ey, v.ez, eye)
                    stationRot.rotate(v.tx, v.ty, v.tz, tgt)
                    val cam = f.camera
                    cam.fovY = v.fov * DEG2RAD
                    val dist = sqrt(v.ex * v.ex + v.ey * v.ey + v.ez * v.ez)
                    val inside = dist < R * 1.2f
                    cam.near = if (inside || v.nearSlice) 0.5f else maxOf(0.5f, (dist - R) * 0.9f)
                    cam.far = dist + R * 1.2f + 1000f
                    cam.pos.set(stationPos.x + eye.x, stationPos.y + eye.y, stationPos.z + eye.z)
                    stationRot.rotate(v.ux, v.uy, v.uz, tmp)
                    cam.rot.lookRotation(tgt.x - eye.x, tgt.y - eye.y, tgt.z - eye.z, tmp.x, tmp.y, tmp.z)
                    cam.update(w, h)
                    // PAPI lamps see the camera as the ship; lamps and spin advance a little per view
                    st.update(0.37f, v.ex, v.ey, v.ez)
                    scene.render(f) // background: sky, stars, sun, planets
                    stationPos.relativeTo(cam.pos, rel)
                    pose.setTRS(rel.x, rel.y, rel.z, stationRot, 1f)
                    val lod = StationRenderer.lodFor(dist)
                    val depthFraction = if (v.nearSlice) 0.5f else 1f
                    gl.depthRange(0f, depthFraction)
                    gl.enable(GL.DEPTH_TEST)
                    gl.depthFunc(GL.LEQUAL)
                    gl.enable(GL.CULL_FACE)
                    gl.cullFace(GL.BACK)
                    stations.resetStats()
                    ships.resetStats()
                    if (lod < StationRenderer.LOD_MARKER) {
                        stations.begin(f, envTex, envLod)
                        stations.drawOpaque(gpu, st, pose, cam.viewProj, lod, depthFraction)
                        if (parked && dist < 3000f) {
                            // ship pose: station rotation * pad rotation (identity on every model)
                            stationRot.rotate(padPos[0], padPos[1], padPos[2], tmp)
                            shipRot.set(stationRot)
                            shipPose.setTRS(rel.x + tmp.x, rel.y + tmp.y, rel.z + tmp.z, shipRot, 1f)
                            ships.begin(cam.viewProj, f, envTex, envLod, 0.5f)
                            ships.draw(ShipModel.PASS_OPAQUE, ShipModel.VIEW_EXTERIOR, shipPose, rig)
                        }
                        stations.drawTransparent(gpu, st, signs, pose, cam.viewProj, lod, depthFraction)
                        if (parked && dist < 3000f) {
                            gl.enable(GL.BLEND)
                            gl.blendFunc(GL.ONE, GL.ONE_MINUS_SRC_ALPHA)
                            gl.depthMask(false)
                            ships.begin(cam.viewProj, f, envTex, envLod, 0.5f)
                            ships.draw(ShipModel.PASS_GLASS, ShipModel.VIEW_EXTERIOR, shipPose, rig)
                            gl.depthMask(true)
                            gl.disable(GL.BLEND)
                        }
                    } else {
                        val a = accent
                        stations.drawMarker(cam, cam.viewProj, rel.x, rel.y, rel.z, MARKER_PX, a[0] / 255f, a[1] / 255f, a[2] / 255f)
                    }
                    gl.disable(GL.DEPTH_TEST)
                    gl.disable(GL.CULL_FACE)
                    GLES20.glFinish()
                    s.drainErrors(frame)
                    val file = "real-${type.removeSuffix("Station").lowercase()}-${v.name}"
                    s.screenshot(file)
                    r.note(
                        "  %-22s %6.0f m, LOD %s: %d station draw calls, %d triangles%s".format(
                            file, dist, if (lod == StationRenderer.LOD_MARKER) "marker" else lod.toString(), stations.drawCalls,
                            stations.triangles, if (ships.drawCalls > 0) " (+ ship ${ships.drawCalls} calls, ${ships.triangles} triangles)" else "",
                        ),
                    )
                }
                if (type == "SpireStation") perf(s, r, stations, gpu, st, signs, f, stationPos, stationRot, envTex, envLod, content.fontAtlas)
                signs.dispose()
                gpu.dispose()
            }
            stations.dispose()
            ships.dispose()
            gl.deleteTexture(envTex)
            scene.dispose()
            s.drainErrors(frame)
        }
    }

    /** Far marker size (px) beyond 60 km. */
    private const val MARKER_PX = 9f

    /**
     * The station's per-frame CPU work in the worst view (MERIDIAN, inside the hangar, detail level
     * 0 with signs and lights): instance update (spin, lamps, PAPI), five probe sweeps against the
     * colliders and the renderer's draw calls; bytes allocated per frame (run with -Pnoea for
     * ART-like numbers). Measured twice: through Mesa (llvmpipe shades vertices inside the draw
     * call on this thread, so that number is the harness's, not a phone's) and through [NoopGl],
     * which leaves only the engine's own CPU cost.
     */
    private fun perf(
        s: GlSession, r: ScenarioReport, stations: StationRenderer, gpu: StationRenderer.ModelGpu, st: StationInstance,
        signs: StationRenderer.Signs, f: SceneFrame, stationPos: DVec3, stationRot: Quat, envTex: Int, envLod: Float, atlas: ByteArray,
    ) {
        val cam = f.camera
        val rel = Vec3()
        val eye = Vec3()
        stationRot.rotate(-25f, -30f, -395f, eye)
        cam.fovY = 70f * DEG2RAD
        cam.near = 0.5f
        cam.far = 5000f
        cam.pos.set(stationPos.x + eye.x, stationPos.y + eye.y, stationPos.z + eye.z)
        stationRot.rotate(95f, -32f, 435f, rel)
        cam.rot.lookRotation(rel.x, rel.y, rel.z, 0f, 1f, 0f)
        cam.update(s.opts.width, s.opts.height)
        measure("Mesa llvmpipe", s.gl, r, stations, gpu, st, signs, f, stationPos, stationRot, envTex, envLod) { GLES20.glFinish() }
        s.drainErrors(0)
        // the same frames with no driver underneath
        val noop = NoopGl()
        val font = stations.font
        val cpu = StationRenderer(noop, font, atlas)
        val cpuGpu = cpu.upload(st.model)
        val cpuSigns = cpu.signs(st.model, signs.name)
        noop.resetCounters()
        measure("engine only (no-op GL)", noop, r, cpu, cpuGpu, st, cpuSigns, f, stationPos, stationRot, envTex, envLod) {}
        r.note("  no-op GL: %.1f GL calls per frame".format(noop.calls / 360.0))
        cpu.dispose()
    }

    private inline fun measure(
        label: String, gl: com.starwake.core.gfx.Gl, r: ScenarioReport, stations: StationRenderer, gpu: StationRenderer.ModelGpu,
        st: StationInstance, signs: StationRenderer.Signs, f: SceneFrame, stationPos: DVec3, stationRot: Quat, envTex: Int,
        envLod: Float, finish: () -> Unit,
    ) {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        val cam = f.camera
        val rel = Vec3()
        val pose = Mat4()
        val hit = com.starwake.core.station.StationHit()
        val frames = 300
        val warm = 60
        val nanos = LongArray(frames)
        val bytes = LongArray(frames)
        var calls = 0
        var tris = 0
        var hits = 0
        var allocFrames = 0
        for (i in 0 until frames + warm) {
            val a0 = mx.currentThreadAllocatedBytes
            val t0 = System.nanoTime()
            // a ship 300 m into the hangar, sliding towards pad 1, its probe swept against the colliders
            val sx = i * 0.2f
            st.update(1f / 60f, sx, -40f, -300f)
            for (k in 0 until 5) {
                if (st.model.colliders.sphereCast(sx, -60f + k, -300f, sx + 1f, -70f + k, -297f, 10.5f, st.frames, hit)) hits++
            }
            stationPos.relativeTo(cam.pos, rel)
            pose.setTRS(rel.x, rel.y, rel.z, stationRot, 1f)
            stations.resetStats()
            stations.begin(f, envTex, envLod)
            gl.enable(GL.DEPTH_TEST)
            gl.enable(GL.CULL_FACE)
            stations.drawOpaque(gpu, st, pose, cam.viewProj, 0)
            stations.drawTransparent(gpu, st, signs, pose, cam.viewProj, 0)
            val t1 = System.nanoTime()
            val a1 = mx.currentThreadAllocatedBytes
            finish()
            if (i >= warm) {
                nanos[i - warm] = t1 - t0
                bytes[i - warm] = a1 - a0
                if (a1 - a0 > 64) allocFrames++
                calls = stations.drawCalls
                tris = stations.triangles
            }
        }
        val sorted = nanos.sorted()
        val where = StringBuilder()
        for (i in 0 until frames) if (bytes[i] > 64 && where.length < 120) where.append(" #").append(i).append(':').append(bytes[i]).append('B')
        if (where.isNotEmpty()) r.note("  frames that allocated:" + where)
        r.note(
            "perf %s (MERIDIAN hangar, LOD 0, %d frames): update + 5 probe sweeps + draws avg %.3f ms, p50 %.3f, p95 %.3f, max %.3f; %d draw calls, %d triangles; %d B allocated in %d frames (%d frames over 64 B), %d probe hits"
                .format(
                    label, frames, nanos.average() / 1e6, sorted[frames / 2] / 1e6, sorted[(frames * 95) / 100] / 1e6, sorted.last() / 1e6,
                    calls, tris, bytes.sum(), frames, allocFrames, hits,
                ),
        )
    }
}
