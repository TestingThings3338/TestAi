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
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * "stations-real": the six Roblox stations and the construction site from the converted assets
 * (assets/stations, tools/roblox/convert_stations.py) through StationRenderer, over the real
 * background from SceneRenderer, with a VARROW parked on pad 1 for scale: the approach, the mouth,
 * a side and a rear view, inside the hangar, at the pads, then at the distances of detail levels 1
 * and 2 and the 60 km marker. The site is also shown with its stages part-built (orange hologram).
 * Logs load times, triangles and draw calls per detail level.
 *
 * Checks (fail the scenario): sign text on semi-transparent paint shows at full contrast (Refinery
 * wall and floor labels, a Spire pad number; 3x crops written as real-<type>-sign-*.png), and the
 * local lights do not pop between two camera positions 1.2 m apart where the first renderer
 * switched a light (Ring, Spire; real-<type>-lightpop[-diff].png).
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
            // 5,000 km above MERIDIAN: no stand-in station in the frame, Earth in the sky behind some
            // of the views
            val home = content.system.stations[0].pos
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
                val probe = Probe(s, r, scene, f, stations, gpu, st, signs, stationPos, stationRot, envTex, envLod)
                when (type) {
                    "RefineryStation" -> {
                        // the review's case: labels painted on semi-transparent panels of their SurfaceGui
                        for (text in REFINERY_SIGNS) probe.signContrast(model.labels.first { it.text == text })
                    }
                    "SpireStation" -> {
                        // pad numbers under the pad's translucent ring strokes and chevrons
                        probe.signContrast(model.labels.first { it.isPadNumber && it.pad == 1 })
                        // the first renderer switched the brightness-12 downlight at (-40, 12.8, -40) at z = -357.5
                        probe.lightPop(-25f, -30f, -358.1f, -356.9f, -40f, -75f, -40f)
                    }
                    // ... and the right-wall wash spot at (176.6, -72.7, -140) at z = -133
                    "RingStation" -> probe.lightPop(-25f, -30f, -133.6f, -132.4f, 176f, -45f, -140f)
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

    /** Refinery labels that sit on semi-transparent paint of their own SurfaceGui (wall, floor). */
    private val REFINERY_SIGNS = listOf("BAY A  ·  HYDROGEN", "NO NAKED FLAMES", "KEEP CENTRELINE")

    /**
     * Lowest acceptable luma steps (0..255) the sign text makes inside its box: 90th percentile and
     * largest. Measured on the Refinery labels: 21-27 and 21-28 while the translucent panel was
     * drawn over the text, 55-86 and 77-123 with the text on top.
     */
    private const val MIN_TEXT_STEP_P90 = 40
    private const val MIN_TEXT_STEP_MAX = 60

    /**
     * Light pops: largest share (%) of the picture whose local light, averaged over 15 x 15 px,
     * changes by more than POP_LEVEL luma levels between frames 1.2 m apart. Measured at the two
     * probes: 0.748 % (Ring) and 0.510 % (Spire) with the first renderer's hard top-8 choice, 0.000
     * and 0.058 % with fading (the rest is parallax: near lit edges moving with the camera).
     */
    private const val POP_LEVEL = 3
    private const val MAX_POP_SHARE = 0.2

    /**
     * Close-up checks of one station without the parked ship: renders on the session's target and
     * reads it back.
     */
    private class Probe(
        val s: GlSession, val r: ScenarioReport, val scene: SceneRenderer, val f: SceneFrame, val stations: StationRenderer,
        val gpu: StationRenderer.ModelGpu, val st: StationInstance, val signs: StationRenderer.Signs,
        val stationPos: DVec3, val stationRot: Quat, val envTex: Int, val envLod: Float,
    ) {
        private val rel = Vec3()
        private val eye = Vec3()
        private val tgt = Vec3()
        private val upv = Vec3()
        private val tmp = Vec3()
        private val pose = Mat4()
        private val type = gpu.model.type.removeSuffix("Station").lowercase()

        /** Camera at [e] looking at [t] with [u] up (station space, metres). */
        fun camera(e: FloatArray, t: FloatArray, u: FloatArray, fov: Float) {
            stationRot.rotate(e[0], e[1], e[2], eye)
            stationRot.rotate(t[0], t[1], t[2], tgt)
            stationRot.rotate(u[0], u[1], u[2], upv)
            val cam = f.camera
            cam.fovY = fov * DEG2RAD
            cam.near = 0.5f
            cam.far = 6000f
            cam.pos.set(stationPos.x + eye.x, stationPos.y + eye.y, stationPos.z + eye.z)
            cam.rot.lookRotation(tgt.x - eye.x, tgt.y - eye.y, tgt.z - eye.z, upv.x, upv.y, upv.z)
            cam.update(s.opts.width, s.opts.height)
        }

        /** The station at detail level 0 over the background, with or without sign text, [lights] per pixel at most. */
        fun render(withSigns: Boolean, lights: Int = com.starwake.core.gfx.StationShaders.MAX_LIGHTS): BufferedImage {
            val gl = s.gl
            scene.render(f)
            stationPos.relativeTo(f.camera.pos, rel)
            pose.setTRS(rel.x, rel.y, rel.z, stationRot, 1f)
            gl.depthRange(0f, 1f)
            gl.enable(GL.DEPTH_TEST)
            gl.depthFunc(GL.LEQUAL)
            gl.enable(GL.CULL_FACE)
            gl.cullFace(GL.BACK)
            stations.maxLights = lights
            stations.begin(f, envTex, envLod)
            stations.drawOpaque(gpu, st, pose, f.camera.viewProj, 0)
            stations.drawTransparent(gpu, st, if (withSigns) signs else null, pose, f.camera.viewProj, 0)
            stations.maxLights = com.starwake.core.gfx.StationShaders.MAX_LIGHTS
            gl.disable(GL.DEPTH_TEST)
            gl.disable(GL.CULL_FACE)
            GLES20.glFinish()
            s.drainErrors(0)
            return Screenshot.toImage(s.target.readPixels(), s.opts.width, s.opts.height)
        }

        /** Screen box (x0, y0, x1, y1, top-down pixels) of station-space points; false if one is behind the camera. */
        fun box(points: List<FloatArray>, out: IntArray): Boolean {
            val m = f.camera.viewProj.m
            val w = s.opts.width
            val h = s.opts.height
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            stationPos.relativeTo(f.camera.pos, rel)
            for (p in points) {
                stationRot.rotate(p[0], p[1], p[2], tmp)
                val x = rel.x + tmp.x
                val y = rel.y + tmp.y
                val z = rel.z + tmp.z
                val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
                if (cw <= 0.01f) return false
                val sx = ((m[0] * x + m[4] * y + m[8] * z + m[12]) / cw * 0.5f + 0.5f) * w
                val sy = (1f - ((m[1] * x + m[5] * y + m[9] * z + m[13]) / cw * 0.5f + 0.5f)) * h
                x0 = minOf(x0, sx); y0 = minOf(y0, sy); x1 = maxOf(x1, sx); y1 = maxOf(y1, sy)
            }
            out[0] = x0.toInt().coerceIn(0, w - 1); out[1] = y0.toInt().coerceIn(0, h - 1)
            out[2] = x1.toInt().coerceIn(0, w - 1); out[3] = y1.toInt().coerceIn(0, h - 1)
            return out[2] > out[0] && out[3] > out[1]
        }

        /**
         * A label straight on from 1.2 x its width (a little below its middle, as a pilot over the
         * deck sees a wall sign), rendered with and without the sign text: how far the text moves
         * the pixels of its box (90th percentile and largest luma step), and a 3x crop for review.
         * When translucent paint covered the text (the review's finding) the steps were a quarter
         * of the text's own contrast; blank pad numbers (the locale finding) would show none.
         */
        fun signContrast(l: StationModel.Label) {
            val w = sqrt(l.u[0] * l.u[0] + l.u[1] * l.u[1] + l.u[2] * l.u[2])
            val hv = sqrt(l.v[0] * l.v[0] + l.v[1] * l.v[1] + l.v[2] * l.v[2])
            val c = FloatArray(3) { l.origin[it] + l.u[it] * 0.5f + l.v[it] * 0.5f }
            val d = maxOf(w, hv * 3f) * 1.2f
            // up = against the text's "down"; the camera sits 7 degrees below the label's middle
            val up = FloatArray(3) { -l.v[it] / hv }
            val e = FloatArray(3) { c[it] + l.normal[it] * d - up[it] * d * 0.12f }
            camera(e, c, up, 40f)
            val with = render(true)
            val without = render(false)
            val corners = listOf(
                l.origin, FloatArray(3) { l.origin[it] + l.u[it] }, FloatArray(3) { l.origin[it] + l.v[it] },
                FloatArray(3) { l.origin[it] + l.u[it] + l.v[it] },
            )
            val b = IntArray(4)
            check(box(corners, b)) { "${l.text}: label not in view" }
            val steps = ArrayList<Int>()
            var textPx = 0
            var lumaSum = 0L
            for (y in b[1]..b[3]) for (x in b[0]..b[2]) {
                val a = luma(with.getRGB(x, y))
                val o = luma(without.getRGB(x, y))
                val dl = abs(a - o)
                steps += dl
                if (dl > 8) {
                    textPx++
                    lumaSum += a
                }
            }
            steps.sort()
            val p90 = steps[(steps.size * 9) / 10]
            val pmax = steps.last()
            val name = "real-$type-sign-" + l.text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(16).ifEmpty { "pad${l.pad}" }
            crop(with, b, File(s.opts.outDir, "$name.png"))
            r.note(
                "  %-34s text \"%s\": %d text pixels, mean luma %.1f, luma step p90 %d, max %d (box %dx%d px)"
                    .format(
                        name, (if (l.isPadNumber) StationModel.padNumber(l.pad) else l.text).replace('·', '.'), textPx,
                        if (textPx > 0) lumaSum / textPx.toDouble() else 0.0, p90, pmax, b[2] - b[0], b[3] - b[1],
                    ),
            )
            check(p90 >= MIN_TEXT_STEP_P90 && pmax >= MIN_TEXT_STEP_MAX) {
                "${l.text}: the sign text barely shows over its paint (luma step p90 $p90, max $pmax)"
            }
        }

        /**
         * Two camera positions 1.2 m apart on the hangar centre line ([x], [y], [z0] and [z1]) where
         * the first renderer swapped its 8th and 9th light, looking at ([tx], [ty], [tz]), the pool
         * of the light it switched. Per frame the local lights' contribution L (with lights minus
         * without); the change of L between the frames, box-blurred over 15 x 15 px with its sign,
         * marks a pool turning on or off (edges that move with the camera give +/- pairs that
         * cancel). Writes the second frame and the change amplified 4x for review.
         */
        fun lightPop(x: Float, y: Float, z0: Float, z1: Float, tx: Float, ty: Float, tz: Float) {
            val up = floatArrayOf(0f, 1f, 0f)
            val shots = ArrayList<BufferedImage>()
            for (z in floatArrayOf(z0, z1)) {
                camera(floatArrayOf(x, y, z), floatArrayOf(tx, ty, tz), up, 60f)
                val lit = render(true)
                val dark = render(true, 0)
                shots += lit
                shots += dark
            }
            val w = s.opts.width
            val h = s.opts.height
            val diff = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            // signed change of the light contribution, and its integral image for the box blur
            val sat = LongArray((w + 1) * (h + 1))
            var light = 0L
            for (py in 0 until h) {
                var row = 0L
                for (px in 0 until w) {
                    val l0 = luma(shots[0].getRGB(px, py)) - luma(shots[1].getRGB(px, py))
                    val l1 = luma(shots[2].getRGB(px, py)) - luma(shots[3].getRGB(px, py))
                    val d = l1 - l0
                    light += abs(l1)
                    row += d
                    sat[(py + 1) * (w + 1) + px + 1] = sat[py * (w + 1) + px + 1] + row
                    val v = minOf(255, abs(d) * 4)
                    diff.setRGB(px, py, (v shl 16) or (v shl 8) or v)
                }
            }
            val k = 7
            var pop = 0
            var peak = 0.0
            for (py in k until h - k) for (px in k until w - k) {
                val a = sat[(py - k) * (w + 1) + px - k]
                val b = sat[(py - k) * (w + 1) + px + k + 1]
                val c = sat[(py + k + 1) * (w + 1) + px - k]
                val e = sat[(py + k + 1) * (w + 1) + px + k + 1]
                val mean = abs(e - b - c + a) / ((2.0 * k + 1) * (2 * k + 1))
                if (mean > POP_LEVEL) pop++
                if (mean > peak) peak = mean
            }
            val name = "real-$type-lightpop"
            Screenshot.write(shots[2], File(s.opts.outDir, "$name.png"))
            Screenshot.write(diff, File(s.opts.outDir, "$name-diff.png"))
            val n = w.toDouble() * h
            val share = pop * 100.0 / n
            r.note(
                "  %-34s camera (%.0f, %.0f, %.1f -> %.1f) at (%.0f, %.0f, %.0f): light contribution mean %.2f luma; between the frames a 15 px area changes by %.1f levels at most, %.3f %% of the picture by > %d"
                    .format(name, x, y, z0, z1, tx, ty, tz, light / n, peak, share, POP_LEVEL),
            )
            check(share < MAX_POP_SHARE) { "$type: $share % of the picture changes its light by > $POP_LEVEL levels between frames 1.2 m apart (a light popped)" }
        }

        private fun luma(p: Int): Int = (((p shr 16) and 0xFF) * 54 + ((p shr 8) and 0xFF) * 183 + (p and 0xFF) * 19) shr 8

        private fun crop(img: BufferedImage, b: IntArray, file: File) {
            val pad = 12
            val x0 = (b[0] - pad).coerceAtLeast(0)
            val y0 = (b[1] - pad).coerceAtLeast(0)
            val x1 = (b[2] + pad).coerceAtMost(img.width - 1)
            val y1 = (b[3] + pad).coerceAtMost(img.height - 1)
            val k = maxOf(1, minOf(3, 1600 / (x1 - x0 + 1)))
            val out = BufferedImage((x1 - x0 + 1) * k, (y1 - y0 + 1) * k, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.drawImage(img.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1), 0, 0, out.width, out.height, null)
            g.dispose()
            Screenshot.write(out, file)
        }
    }

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
