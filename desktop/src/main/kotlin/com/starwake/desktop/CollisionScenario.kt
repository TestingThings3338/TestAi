package com.starwake.desktop

import com.starwake.core.content.Content
import com.starwake.core.content.StationMesh
import com.starwake.core.game.ShipSpec
import com.starwake.core.game.StarSystem
import com.starwake.core.game.StationCollision
import com.starwake.core.gfx.SceneFrame
import com.starwake.core.gfx.SceneRenderer
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import org.lwjgl.opengles.GLES20
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * Station collision without Game: each station through the real [SceneRenderer], with
 *  - path views: the auto-dock route ([StationCollision.dockWaypoint] + the kinematics of
 *    Game.updateDocking) from several starts drawn over it, the old straight line to the stand-off
 *    in red, the stand-off and park points; the escape views start from ships wedged against the
 *    hull (a start dot each), where the old router pushed back and forth between two walls;
 *  - proxy views: a CPU sphere trace of the collision proxies from the same camera (`-proxies.png`)
 *    and an overlay on the mesh render (`-overlay.png`: proxies tinted magenta, their outline yellow).
 */
object CollisionScenario {
    private class View(
        val name: String,
        /** Index into [StarSystem.stations]. */
        val station: Int,
        /** Camera eye and target in station-local metres. */
        val ex: Float, val ey: Float, val ez: Float,
        val tx: Float, val ty: Float, val tz: Float,
        val paths: Boolean,
        /** Route starts (station-local); null = the far [starts]. */
        val from: Array<FloatArray>? = null,
    )

    private val views = listOf(
        View("collision-ring-paths", 0, 2600f, 1500f, 2300f, 0f, 0f, -250f, true),
        View("collision-ring-paths-side", 0, 4200f, 300f, -200f, 0f, 0f, -150f, true),
        View("collision-ring-front", 0, -900f, 600f, -2600f, 0f, 0f, -150f, false),
        View("collision-ring-slot", 0, 150f, 80f, -900f, 0f, 0f, -300f, false),
        View("collision-ring-rear", 0, 1500f, 900f, 2400f, 0f, 0f, 150f, false),
        View("collision-outpost-paths", 1, 1100f, 650f, 900f, 0f, 0f, -120f, true),
        View("collision-outpost-paths-side", 1, 3600f, 300f, -200f, 0f, 0f, -150f, true),
        View("collision-outpost-front", 1, -380f, 260f, -680f, 0f, 0f, -40f, false),
        View("collision-outpost-rear", 1, 330f, 120f, 640f, 0f, 0f, 0f, false),
        // wedged starts, as in StationCollisionTest.autoDockSlidesOutOfNarrowGaps
        View(
            "collision-ring-escapes", 0, 700f, 260f, -60f, 0f, 0f, -130f, true,
            arrayOf(
                floatArrayOf(0f, 74f, -100f), floatArrayOf(21.93f, -76.13f, -165.43f), floatArrayOf(125.34f, 0.97f, -50.65f),
                floatArrayOf(-12.82f, -111.23f, -49.57f), floatArrayOf(72.67f, -7.33f, -47.58f), floatArrayOf(-105.06f, 0.78f, -192.82f),
                floatArrayOf(94.16f, -1.99f, -191.57f),
            ),
        ),
        View(
            "collision-outpost-escapes", 1, -330f, -260f, -330f, -40f, -60f, 0f, true,
            arrayOf(floatArrayOf(-0.41f, -117.2f, -14.77f), floatArrayOf(-101.18f, -52.52f, 4.83f), floatArrayOf(-46.76f, -39.93f, -10.12f)),
        ),
    )

    /** Route starts (station-local), as in StationCollisionTest. */
    private val starts = arrayOf(
        floatArrayOf(0f, 0f, 1500f), floatArrayOf(0f, 1500f, 0f), floatArrayOf(0f, -1500f, 0f),
        floatArrayOf(1500f, 0f, 0f), floatArrayOf(-1500f, 0f, 0f), floatArrayOf(900f, -700f, 900f),
        floatArrayOf(600f, 300f, -1400f),
    )
    private val colors = intArrayOf(
        0xFF66FF99.toInt(), 0xFF3FC8FF.toInt(), 0xFFFFD040.toInt(), 0xFFFF7AE0.toInt(),
        0xFFB0FF40.toInt(), 0xFF40FFF0.toInt(), 0xFFFFFFFF.toInt(),
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
            val system = content.system
            val scene = SceneRenderer(gl, content, 2)
            val ui = UiRenderer(gl, content.font, content.fontAtlas)
            val sc = StationCollision()
            s.drainErrors(0)

            val f = SceneFrame()
            f.ship = null
            f.quality = 2
            f.pxScale = h / 1080f
            f.dustIntensity = 0f
            val eye = Vec3()
            val tgt = Vec3()
            val up = Vec3()
            var frame = 0L
            for (v in views) {
                frame++
                val st = system.stations[v.station]
                st.rot.rotate(v.ex, v.ey, v.ez, eye)
                st.rot.rotate(v.tx, v.ty, v.tz, tgt)
                st.rot.rotate(0f, 1f, 0f, up)
                val cam = f.camera
                cam.fovY = 52f * (Math.PI.toFloat() / 180f)
                cam.pos.set(st.pos.x + eye.x, st.pos.y + eye.y, st.pos.z + eye.z)
                cam.rot.lookRotation(tgt.x - eye.x, tgt.y - eye.y, tgt.z - eye.z, up.x, up.y, up.z)
                cam.update(w, h)
                f.time = 1f + frame
                scene.render(f)
                ui.begin(w, h)
                if (v.paths) drawPaths(ui, f, st, sc, r, v.name, v.from ?: starts)
                ui.text(v.name.uppercase(), 40f * f.pxScale, 70f * f.pxScale, 30f * f.pxScale, 0xFFFFFFFF.toInt(), tracking = 0.1f, shadow = 0.6f)
                ui.end()
                GLES20.glFinish()
                s.drainErrors(frame)
                val shot = s.screenshot(v.name)
                if (!v.paths) proxyImages(shot, f, st, sc, w, h, r, v.name)
            }
            ui.dispose()
            scene.dispose()
            s.drainErrors(frame)
        }
    }

    /** Simulates the routed auto-dock from every start and draws it (station-relative polylines). */
    private fun drawPaths(
        ui: UiRenderer, f: SceneFrame, st: StarSystem.Station, sc: StationCollision, r: ScenarioReport, name: String,
        from: Array<FloatArray>,
    ) {
        val cam = f.camera
        val portL = Vec3()
        val dirL = Vec3()
        StationMesh.dockPort(st.model, portL, dirL)
        val dockDir = st.rot.rotate(dirL, Vec3())
        val dockOff = st.rot.rotate(portL, Vec3())
        val park = StationMesh.slotSize(st.model, Vec3()).z * 0.5
        val so = StationCollision.DOCK_STANDOFF
        // station centre relative to the camera
        val base = st.pos.relativeTo(cam.pos, Vec3())
        val a = Vec3()
        val b = Vec3()
        fun seg(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, width: Float, color: Int) {
            if (!cam.project(base.x + x0.toFloat(), base.y + y0.toFloat(), base.z + z0.toFloat(), a)) return
            if (!cam.project(base.x + x1.toFloat(), base.y + y1.toFloat(), base.z + z1.toFloat(), b)) return
            ui.line(a.x, a.y, b.x, b.y, width, color)
        }
        val px = f.pxScale
        for ((i, s0) in from.withIndex()) {
            val color = colors[i % colors.size]
            val p = DVec3()
            st.rot.rotate(s0[0], s0[1], s0[2], a)
            p.set(st.pos.x + a.x, st.pos.y + a.y, st.pos.z + a.z)
            // the old straight approach, for comparison
            seg(p.x - st.pos.x, p.y - st.pos.y, p.z - st.pos.z, dockOff.x + dockDir.x * so, dockOff.y + dockDir.y * so, dockOff.z + dockDir.z * so, 2f * px, 0x90FF4040.toInt())
            val wp = DVec3()
            var phase = 0
            val dt = 1f / 60f
            var t = 0f
            var detourFrames = 0
            var boxed = 0f
            var outcome = "not docked"
            // start dot (the escape views start right against the hull)
            if (cam.project(base.x + (p.x - st.pos.x).toFloat(), base.y + (p.y - st.pos.y).toFloat(), base.z + (p.z - st.pos.z).toFloat(), b)) {
                ui.circle(b.x, b.y, 7f * px, color)
            }
            while (t < 240f) {
                t += dt
                val detour = phase == 0 && sc.dockWaypoint(st, p, wp)
                if (detour) detourFrames++
                // Game's give-up rule (see StationCollision.boxedIn)
                boxed = if (detour && sc.boxedIn) boxed + dt else 0f
                if (boxed > 0.5f) {
                    outcome = "gave up (boxed in)"
                    break
                }
                val off = if (phase == 0) so else -park
                val tx = if (detour) wp.x else st.pos.x + dockOff.x + dockDir.x * off
                val ty = if (detour) wp.y else st.pos.y + dockOff.y + dockDir.y * off
                val tz = if (detour) wp.z else st.pos.z + dockOff.z + dockDir.z * off
                val dx = tx - p.x
                val dy = ty - p.y
                val dz = tz - p.z
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                val speed = if (detour) 180f else if (phase == 0) minOf(180f, (d * 0.6).toFloat() + 5f) else minOf(30f, (d * 0.8).toFloat() + 8f)
                val ox = p.x - st.pos.x
                val oy = p.y - st.pos.y
                val oz = p.z - st.pos.z
                if (d > 0.5) {
                    val k = minOf(1.0, speed * dt / d)
                    p.set(p.x + dx * k, p.y + dy * k, p.z + dz * k)
                }
                seg(ox, oy, oz, p.x - st.pos.x, p.y - st.pos.y, p.z - st.pos.z, 4f * px, color)
                if (phase == 0 && !detour && d < 15.0) {
                    phase = 1
                } else if (phase == 1 && d < 2.0) {
                    outcome = "docked"
                    break
                }
            }
            r.note("$name: start (${s0.joinToString()}) $outcome after %.1f s, %d detour frames".format(t, detourFrames))
        }
        // stand-off and park points
        for (k in 0..1) {
            val off = if (k == 0) so else -park
            if (cam.project(base.x + (dockOff.x + dockDir.x * off).toFloat(), base.y + (dockOff.y + dockDir.y * off).toFloat(), base.z + (dockOff.z + dockDir.z * off).toFloat(), a)) {
                ui.circle(a.x, a.y, 9f * px, 0xFFFFFFFF.toInt(), stroke = 3f * px)
            }
        }
    }

    /**
     * Sphere-traces the station's collision proxies on the CPU from [f]'s camera (one ray per 4x4
     * pixels), writes the shaded result, and an overlay on the mesh screenshot [shot].
     */
    private fun proxyImages(shot: File, f: SceneFrame, st: StarSystem.Station, sc: StationCollision, w: Int, h: Int, r: ScenarioReport, name: String) {
        val t0 = System.nanoTime()
        val cam = f.camera
        val block = 4
        val bw = w / block
        val bh = h / block
        val hit = BooleanArray(bw * bh)
        val shade = FloatArray(bw * bh)
        // camera in station-local space
        val eye = st.rot.rotateInverse(
            (cam.pos.x - st.pos.x).toFloat(), (cam.pos.y - st.pos.y).toFloat(), (cam.pos.z - st.pos.z).toFloat(), Vec3(),
        )
        val right = st.rot.rotateInverse(cam.right, Vec3())
        val upv = st.rot.rotateInverse(cam.up, Vec3())
        val fwd = st.rot.rotateInverse(cam.forward, Vec3())
        val light = Vec3(-0.4f, 0.7f, -0.6f).normalize()
        val m = st.model
        val reach = st.radius.toFloat() + 20f
        val d = Vec3()
        for (j in 0 until bh) for (i in 0 until bw) {
            val nxp = ((i + 0.5f) * block / w) * 2f - 1f
            val nyp = 1f - ((j + 0.5f) * block / h) * 2f
            d.set(fwd).addScaled(right, nxp * cam.tanHalfX).addScaled(upv, nyp * cam.tanHalfY).normalize()
            // start where the ray enters the bounding sphere
            val bproj = eye.dot(d)
            val c = eye.dot(eye) - reach * reach
            val disc = bproj * bproj - c
            if (disc <= 0f) continue
            var t = maxOf(0f, -bproj - sqrt(disc))
            val tEnd = -bproj + sqrt(disc)
            var steps = 0
            while (t < tEnd && steps < 400) {
                val x = eye.x + d.x * t
                val y = eye.y + d.y * t
                val z = eye.z + d.z * t
                val s = sc.localDistance(m, x, y, z)
                if (s < 0.05f) {
                    // normal by central differences
                    val e = 0.5f
                    val gx = sc.localDistance(m, x + e, y, z) - sc.localDistance(m, x - e, y, z)
                    val gy = sc.localDistance(m, x, y + e, z) - sc.localDistance(m, x, y - e, z)
                    val gz = sc.localDistance(m, x, y, z + e) - sc.localDistance(m, x, y, z - e)
                    val gl = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6f)
                    val lam = (gx * light.x + gy * light.y + gz * light.z) / gl
                    hit[j * bw + i] = true
                    shade[j * bw + i] = 0.25f + 0.75f * maxOf(0f, lam)
                    break
                }
                t += maxOf(s, 0.05f)
                steps++
            }
        }
        val proxies = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val mesh = ImageIO.read(shot)
        val overlay = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val bi = minOf(x / block, bw - 1)
            val bj = minOf(y / block, bh - 1)
            val k = bj * bw + bi
            val src = mesh.getRGB(x, y)
            if (!hit[k]) {
                proxies.setRGB(x, y, 0x101418)
                overlay.setRGB(x, y, src)
                continue
            }
            val g = (shade[k] * 255f).toInt().coerceIn(0, 255)
            proxies.setRGB(x, y, (g shl 16) or ((g * 0.85f).toInt() shl 8) or (g * 0.7f).toInt())
            // outline where the proxy silhouette ends, magenta tint inside
            val edge = (bi > 0 && !hit[k - 1]) || (bi < bw - 1 && !hit[k + 1]) || (bj > 0 && !hit[k - bw]) || (bj < bh - 1 && !hit[k + bw])
            if (edge) {
                overlay.setRGB(x, y, 0xFFE040)
            } else {
                val sr = (src shr 16) and 255
                val sg = (src shr 8) and 255
                val sb = src and 255
                overlay.setRGB(x, y, (((sr * 0.65f) + 255 * 0.35f).toInt() shl 16) or ((sg * 0.65f).toInt() shl 8) or ((sb * 0.65f) + 255 * 0.35f).toInt())
            }
        }
        val pf = File(shot.parentFile, "$name-proxies.png")
        val of = File(shot.parentFile, "$name-overlay.png")
        ImageIO.write(proxies, "png", pf)
        ImageIO.write(overlay, "png", of)
        r.note("$name: proxies traced in %.0f ms -> ${pf.name}, ${of.name}".format((System.nanoTime() - t0) / 1e6))
    }
}
