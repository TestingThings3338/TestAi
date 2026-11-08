package com.starwake.desktop

import com.starwake.core.content.Primitives
import com.starwake.core.content.StarField
import com.starwake.core.game.StarSystem
import com.starwake.core.gfx.BodyRenderer
import com.starwake.core.gfx.Camera
import com.starwake.core.gfx.GL
import com.starwake.core.gfx.GpuMesh
import com.starwake.core.gfx.ShaderProgram
import com.starwake.core.gfx.Shaders
import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import com.starwake.core.gfx.Gl
import org.lwjgl.opengles.GLES20
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-scale star system bodies rendered by [BodyRenderer] directly (no Game): the gas giant from
 * the home position, PLANET II from orbit and from 20 km (also towards the sun), the star up
 * close, an eclipse, far-away planets as dots, the rings edge-on / in their plane / tiny, a close
 * look at every surface type, and a few views again at quality 0. The background is the plain
 * star-point layer, dimmed by [BodyRenderer.skyFade] like the game should. Views with a [View.zoom]
 * point also get a nearest-neighbour enlarged crop around it (`-zoom.png`) for judging aliasing.
 */
object Bodies {
    private class View(
        val name: String, val pos: DVec3, val look: DVec3, val up: DVec3 = DVec3(0.0, 1.0, 0.0), val time: Float = 0f,
        val zoom: DVec3? = null,
    )

    fun run(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val gl = s.gl
            val w = opts.width
            val h = opts.height
            val system = StarSystem()
            val fullscreen = GpuMesh(gl, Primitives.fullscreenTriangle, intArrayOf(2))
            val starMesh = GpuMesh(gl, StarField.build(4500), intArrayOf(3, 4))
            val starProg = ShaderProgram(gl, "stars", Shaders.starVs, Shaders.starFs)

            // bake cost at both texture sizes
            var t0 = System.nanoTime()
            val bodiesQ0 = BodyRenderer(gl, system, 0, fullscreen)
            GLES20.glFinish()
            r.note("BodyRenderer(quality 0): programs + 512x256 bakes in %.0f ms".format((System.nanoTime() - t0) / 1e6))
            t0 = System.nanoTime()
            val bodies = BodyRenderer(gl, system, 2, fullscreen)
            GLES20.glFinish()
            r.note("BodyRenderer(quality 2): programs + 1024x512 bakes in %.0f ms".format((System.nanoTime() - t0) / 1e6))
            r.note("needsRebake from quality 2: q0 %b, q1 %b, q2 %b, q3 %b; from quality 0: q0 %b, q1 %b".format(
                bodies.needsRebake(0), bodies.needsRebake(1), bodies.needsRebake(2), bodies.needsRebake(3),
                bodiesQ0.needsRebake(0), bodiesQ0.needsRebake(1),
            ))
            s.drainErrors(0)

            val cam = Camera()
            cam.far = 40000f
            var frame = 0L
            var fade = 0f
            /**
             * Renders [v] (camera moved by [shift] metres along its right axis) with [renderer]; returns
             * the render+finish time in ms. The star points are dimmed by the previous frame's skyFade
             * (as a game would), so every view is drawn twice: once to settle it.
             */
            fun draw(v: View, shift: Double = 0.0, renderer: BodyRenderer = bodies): Double {
                val fx = v.look.x - v.pos.x
                val fy = v.look.y - v.pos.y
                val fz = v.look.z - v.pos.z
                cam.rot.lookRotation(fx.toFloat(), fy.toFloat(), fz.toFloat(), v.up.x.toFloat(), v.up.y.toFloat(), v.up.z.toFloat())
                cam.update(w, h)
                cam.pos.set(v.pos.x + cam.right.x * shift, v.pos.y + cam.right.y * shift, v.pos.z + cam.right.z * shift)
                gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
                gl.viewport(0, 0, w, h)
                gl.clearColor(0f, 0f, 0f, 1f)
                gl.clear(GL.COLOR_BUFFER_BIT or GL.DEPTH_BUFFER_BIT)
                gl.enable(GL.BLEND)
                gl.blendFunc(GL.ONE, GL.ONE)
                starProg.use()
                gl.uniformMatrix4fv(starProg.u("u_viewProj"), cam.viewProj.m, 0)
                gl.uniform1f(starProg.u("u_pxScale"), h / 1080f)
                gl.uniform1f(starProg.u("u_bright"), 1f - fade)
                starMesh.draw(GL.POINTS)
                gl.disable(GL.BLEND)
                val t = System.nanoTime()
                renderer.render(cam, v.time, 1f)
                GLES20.glFinish()
                fade = renderer.skyFade()
                return (System.nanoTime() - t) / 1e6
            }
            fun drawSettled(v: View, renderer: BodyRenderer = bodies): Double {
                draw(v, renderer = renderer)
                return draw(v, renderer = renderer)
            }
            val p2 = system.bodies.indexOfFirst { it.name == "PLANET II" }
            val all = views(system, readBake(gl, bodies, p2))
            for (v in all) {
                frame++
                val ms = drawSettled(v)
                s.drainErrors(frame)
                r.note("%-26s render %.1f ms, skyFade %.2f, camera %s".format(v.name, ms, fade, describe(system, v.pos)))
                if (v.name == "bodies-e-dots" || v.name == "bodies-e2-min-dot") {
                    val pxAngle = 2.0 * cam.tanHalfY / h
                    for (b in system.bodies) {
                        val ang = kotlin.math.asin(b.radius / dist(v.pos, b.pos))
                        r.note("    %-10s true apparent radius %.2f px%s".format(b.name, ang / pxAngle, if (ang / pxAngle < BodyRenderer.MIN_PLANET_PX) " (drawn at the minimum)" else ""))
                    }
                }
                s.screenshot(v.name)
                v.zoom?.let { zoomShot(s, cam, it, File(opts.outDir, "${v.name}-zoom.png")) }
            }

            // the cheap path: quality 0 (512x256 bakes, 5 atmosphere steps, no detail octaves)
            for (name in listOf("bodies-a-home-giant", "bodies-b-orbit-limb", "bodies-c-20km-horizon", "bodies-c3-1500m", "bodies-f-giant-rings")) {
                val v = all.first { it.name == name }
                frame++
                val ms0 = drawSettled(v, bodiesQ0)
                s.drainErrors(frame)
                val shot = "bodies-q0-" + name.removePrefix("bodies-")
                val ms2 = drawSettled(v)
                drawSettled(v, bodiesQ0)
                r.note("%-26s render %.1f ms at quality 0 (%.1f ms at quality 2), skyFade %.2f".format(shot, ms0, ms2, fade))
                s.screenshot(shot)
            }

            // temporal stability: the same view with the camera moved 1 m sideways must change
            // smoothly (sub-pixel parallax), never as speckle from float precision noise
            for (name in listOf("bodies-c-20km-horizon", "bodies-c3-1500m", "bodies-b-orbit-limb")) {
                val v = all.first { it.name == name }
                drawSettled(v)
                val a = s.target.readPixels()
                val copy = ByteBuffer.allocateDirect(a.capacity()).order(ByteOrder.nativeOrder()).put(a)
                draw(v, 1.0)
                val b = s.target.readPixels()
                var maxD = 0
                var sum = 0L
                var over4 = 0
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until h) for (x in 0 until w) {
                    val o = ((h - 1 - y) * w + x) * 4
                    var dm = 0
                    for (k in 0 until 3) dm = maxOf(dm, kotlin.math.abs((copy.get(o + k).toInt() and 0xFF) - (b.get(o + k).toInt() and 0xFF)))
                    maxD = maxOf(maxD, dm)
                    sum += dm
                    if (dm > 4) over4++
                    val g = minOf(255, dm * 16)
                    img.setRGB(x, y, (g shl 16) or (g shl 8) or g)
                }
                val f = File(opts.outDir, "$name-diff1m.png")
                Screenshot.write(img, f)
                r.note("%s moved 1 m: max channel diff %d, mean %.3f, %d px differ by > 4/255 (x16 diff image %s)".format(name, maxD, sum.toDouble() / (w * h), over4, f.path))
            }
            dumpBakes(gl, bodies, system, File(opts.outDir, "bodies-bakes.png"))
            r.note("baked equirect textures (top to bottom, RGB | emission): ${File(opts.outDir, "bodies-bakes.png").path}")
            bodies.dispose()
            bodiesQ0.dispose()
            starProg.dispose()
            starMesh.dispose()
            fullscreen.dispose()
            s.drainErrors(frame)
        }
    }

    /** Writes a 6x nearest-neighbour enlargement of the 400x200 px around [target]'s projection. */
    private fun zoomShot(s: GlSession, cam: Camera, target: DVec3, file: File) {
        val dx = target.x - cam.pos.x
        val dy = target.y - cam.pos.y
        val dz = target.z - cam.pos.z
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        val p = Vec3()
        if (!cam.project((dx / l * 1000).toFloat(), (dy / l * 1000).toFloat(), (dz / l * 1000).toFloat(), p)) return
        val w = s.target.width
        val h = s.target.height
        val cw = minOf(400, w)
        val ch = minOf(200, h)
        val x0 = (p.x.toInt() - cw / 2).coerceIn(0, w - cw)
        val y0 = (p.y.toInt() - ch / 2).coerceIn(0, h - ch)
        val src = Screenshot.toImage(s.target.readPixels(), w, h)
        val k = 6
        val img = BufferedImage(cw * k, ch * k, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until ch * k) for (x in 0 until cw * k) img.setRGB(x, y, src.getRGB(x0 + x / k, y0 + y / k))
        Screenshot.write(img, file)
        println("  [zoom] ${file.path}: ${cw}x$ch px at ($x0, $y0), x$k")
    }

    /**
     * Debug montage of every baked surface texture (read back through an FBO; the texture ids are
     * private to BodyRenderer, so they are fetched by reflection). Left: sqrt-encoded albedo as
     * stored, right: the alpha channel (lava glow / city lights).
     */
    private fun dumpBakes(gl: Gl, bodies: BodyRenderer, system: StarSystem, file: File) {
        val ids = system.bodies.indices.filter { system.bodies[it].type != StarSystem.BodyType.STAR }
        val w = BodyRenderer.bakeWidth(2)
        val h = w / 2
        val img = BufferedImage(w * 2, h * ids.size, BufferedImage.TYPE_INT_RGB)
        for ((row, i) in ids.withIndex()) {
            val px = readBake(gl, bodies, i)
            for (y in 0 until h) for (x in 0 until w) {
                val c = px[(h - 1 - y) * w + x]
                val aa = c ushr 24
                img.setRGB(x, row * h + y, c and 0xFFFFFF)
                img.setRGB(w + x, row * h + y, (aa shl 16) or (aa shl 8) or aa)
            }
            println("  bake ${system.bodies[i].name}: row $row")
        }
        Screenshot.write(img, file)
    }

    /**
     * The baked texture of body [i] (quality 2 size) as ARGB ints, row 0 = v 0 (south pole). The
     * texture ids are private to BodyRenderer, so they are fetched by reflection.
     */
    private fun readBake(gl: Gl, bodies: BodyRenderer, i: Int): IntArray {
        val f = BodyRenderer::class.java.getDeclaredField("textures").apply { isAccessible = true }
        val tex = f.get(bodies) as IntArray
        val w = BodyRenderer.bakeWidth(2)
        val h = w / 2
        val fbo = gl.genFramebuffer()
        gl.bindFramebuffer(GL.FRAMEBUFFER, fbo)
        gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, tex[i], 0)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
        gl.deleteFramebuffer(fbo)
        return IntArray(w * h) {
            val o = it * 4
            ((buf.get(o + 3).toInt() and 0xFF) shl 24) or ((buf.get(o).toInt() and 0xFF) shl 16) or
                ((buf.get(o + 1).toInt() and 0xFF) shl 8) or (buf.get(o + 2).toInt() and 0xFF)
        }
    }

    private fun describe(system: StarSystem, p: DVec3): String {
        var best = ""
        var bestAlt = Double.MAX_VALUE
        for (b in system.bodies) {
            val d = dist(p, b.pos)
            if (d - b.radius < bestAlt) {
                bestAlt = d - b.radius
                best = "%.3g m above %s (%.2f radii from its centre)".format(d - b.radius, b.name, d / b.radius)
            }
        }
        return best
    }

    private fun body(system: StarSystem, name: String) = system.bodies.first { it.name == name }

    private fun dist(a: DVec3, b: DVec3): Double {
        val x = a.x - b.x
        val y = a.y - b.y
        val z = a.z - b.z
        return sqrt(x * x + y * y + z * z)
    }

    private fun unit(x: Double, y: Double, z: Double): DVec3 {
        val l = sqrt(x * x + y * y + z * z)
        return DVec3(x / l, y / l, z / l)
    }

    private fun cross(a: DVec3, b: DVec3) = DVec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    private fun add(p: DVec3, d: DVec3, s: Double) = DVec3(p.x + d.x * s, p.y + d.y * s, p.z + d.z * s)

    /** Unit vector from [from] towards [to]. */
    private fun dir(from: DVec3, to: DVec3) = unit(to.x - from.x, to.y - from.y, to.z - from.z)

    /** u * cos(a) + v * sin(a) */
    private fun rot(u: DVec3, v: DVec3, a: Double) = unit(u.x * cos(a) + v.x * sin(a), u.y * cos(a) + v.y * sin(a), u.z * cos(a) + v.z * sin(a))

    private fun deg(d: Double) = d * Math.PI / 180.0

    /**
     * World direction (from the centre of [b]) of the surface point whose baked texture coordinate
     * is ([u], [v]), and the time at which the planet's spin puts it at [elevation] sun height.
     * Mirrors the planet shader: texture <- (spin about body Y) <- Body.rot^-1 <- world. A planet
     * that does not spin keeps its sun heights: then, at time 0, the texel nearest to ([u], [v])
     * among those with the sun at [elevation] is used, preferring a coast of the baked texture
     * [tex] (ARGB, see [readBake]).
     */
    private fun surfacePoint(b: StarSystem.Body, sun: DVec3, u: Double, v: Double, elevation: Double, tex: IntArray? = null): Pair<DVec3, Float> {
        val w = Vec3()
        fun world(uu: Double, vv: Double, a: Double): DVec3 {
            val lon = (uu - 0.5) * 2 * Math.PI
            val lat = (vv - 0.5) * Math.PI
            val nx = cos(lat) * cos(lon)
            val nz = cos(lat) * sin(lon)
            val c = cos(a)
            val s = sin(a)
            b.rot.rotate((c * nx + s * nz).toFloat(), sin(lat).toFloat(), (-s * nx + c * nz).toFloat(), w)
            return unit(w.x.toDouble(), w.y.toDouble(), w.z.toDouble())
        }
        if (kotlin.math.abs(b.spin) < 1e-9f) {
            val tw = BodyRenderer.bakeWidth(2)
            val th = tw / 2
            // open water as the planet shader sees it (sqrt-encoded texel: blue well above red)
            fun water(x: Int, y: Int): Boolean? {
                if (tex == null) return null
                val c = tex[y * tw + ((x % tw) + tw) % tw]
                val r = (c shr 16) and 0xFF
                val bl = c and 0xFF
                return when {
                    r > 200 -> null // cloud / ice
                    bl - r > 60 -> true
                    bl - r < 0 -> false
                    else -> null
                }
            }
            fun coast(x: Int, y: Int): Boolean {
                val here = water(x, y) ?: return false
                for (k in intArrayOf(-4, 4)) if (water(x + k, y) == !here) return true
                return false
            }
            val target = world(u, v, 0.0)
            var best = target
            var bestScore = Double.MAX_VALUE
            for (y in 0 until th) for (x in 0 until tw) {
                val d = world((x + 0.5) / tw, (y + 0.5) / th, 0.0)
                if (kotlin.math.abs(kotlin.math.asin(dot(d, sun)) - elevation) > deg(0.3)) continue
                // distance from the requested point, much shorter for a coast
                val score = (1.0 - dot(d, target)) * (if (coast(x, y)) 1.0 else 100.0)
                if (score < bestScore) {
                    bestScore = score
                    best = d
                }
            }
            return best to 0f
        }
        val lat = (v - 0.5) * Math.PI
        val lon = (u - 0.5) * 2 * Math.PI
        val nx = cos(lat) * cos(lon)
        val ny = sin(lat)
        val nz = cos(lat) * sin(lon)
        var best = DVec3()
        var bestA = 0.0
        var bestErr = Double.MAX_VALUE
        for (step in 0 until 1440) {
            val a = step * 2 * Math.PI / 1440
            val c = cos(a)
            val s = sin(a)
            b.rot.rotate((c * nx + s * nz).toFloat(), ny.toFloat(), (-s * nx + c * nz).toFloat(), w)
            val d = unit(w.x.toDouble(), w.y.toDouble(), w.z.toDouble())
            val err = kotlin.math.abs(kotlin.math.asin(dot(d, sun)) - elevation)
            if (err < bestErr) {
                bestErr = err
                best = d
                bestA = a
            }
        }
        return best to (bestA / b.spin).toFloat()
    }

    private fun views(system: StarSystem, p2Bake: IntArray): List<View> {
        val star = system.bodies.first { it.type == StarSystem.BodyType.STAR }
        val giant = body(system, "PLANET III")
        val p2 = body(system, "PLANET II")
        val out = ArrayList<View>()

        // (a) the home position looking at the ringed gas giant; the star is ~115 deg away
        out += View("bodies-a-home-giant", DVec3().set(system.home), giant.pos)

        // (b) 2 radii above PLANET II (3 radii from its centre), mostly on the night side: lit
        // crescent limb with the star ~25 deg beyond it
        val s2 = dir(p2.pos, star.pos)
        val q2 = unit(cross(s2, DVec3(0.0, 1.0, 0.0)).x, cross(s2, DVec3(0.0, 1.0, 0.0)).y, cross(s2, DVec3(0.0, 1.0, 0.0)).z)
        val u2 = rot(DVec3(-s2.x, -s2.y, -s2.z), q2, deg(45.0))
        val camB = add(p2.pos, u2, 3.0 * p2.radius)
        val lookB = add(camB, unit(-u2.x + s2.x * 0.9, -u2.y + s2.y * 0.9, -u2.z + s2.z * 0.9), 1e6)
        out += View("bodies-b-orbit-limb", camB, lookB)

        // (c) 20 km above PLANET II over a coast (texture u 0.43, v 0.50), the planet spun so the
        // sun stands 25 deg high there; looking along the horizon 70 deg from the sun
        val coast = surfacePoint(p2, s2, 0.43, 0.50, deg(25.0), p2Bake)
        val upC = coast.first
        val camC = add(p2.pos, upC, p2.radius + 20_000.0)
        val sunH = unit(s2.x - upC.x * dot(s2, upC), s2.y - upC.y * dot(s2, upC), s2.z - upC.z * dot(s2, upC))
        val side = cross(upC, sunH)
        val horiz = rot(sunH, side, deg(70.0))
        val lookC = add(add(camC, horiz, 1e5), upC, -1e5 * kotlin.math.tan(deg(3.0)))
        out += View("bodies-c-20km-horizon", camC, lookC, upC, coast.second)

        // (c3) 1500 m above the same spot, looking 25 deg down: close-range detail and precision
        val camL = add(p2.pos, upC, p2.radius + 1500.0)
        val lookL = add(add(camL, horiz, 1e4), upC, -1e4 * kotlin.math.tan(deg(25.0)))
        out += View("bodies-c3-1500m", camL, lookL, upC, coast.second)

        // (c4) the same spot looking up at the star (25 deg high): the star through the daylight sky
        out += View("bodies-c4-20km-sun", camC, star.pos, upC, coast.second)

        // (c2) the same altitude at sunset, looking towards the star just above the horizon
        val upS = rot(q2, s2, deg(2.0))
        val camS = add(p2.pos, upS, p2.radius + 20_000.0)
        val sunHS = unit(s2.x - upS.x * dot(s2, upS), s2.y - upS.y * dot(s2, upS), s2.z - upS.z * dot(s2, upS))
        val lookS = add(add(camS, sunHS, 1e5), upS, -1e5 * kotlin.math.tan(deg(2.0)))
        out += View("bodies-c2-20km-sunset", camS, lookS, upS, zoom = giant.pos)

        // (d) looking straight at the star from 4e8 m
        val toHome = dir(star.pos, system.home)
        out += View("bodies-d-star-4e8", add(star.pos, toHome, 4e8), star.pos)

        // (e) far away planets as dots: from home towards PLANET I / II (and the star off to the side)
        val p1 = body(system, "PLANET I")
        val home = system.home
        val m = dir(home, p1.pos)
        val n2 = dir(home, p2.pos)
        out += View("bodies-e-dots", DVec3().set(home), add(home, unit(m.x + n2.x, m.y + n2.y, m.z + n2.z), 1e9))

        // (e2) PLANET V from 3e9 m, the star 60 deg behind the camera (true apparent radius ~0.7 px):
        // the 1.5 px minimum dot in the centre
        val p5 = body(system, "PLANET V")
        val s5 = dir(p5.pos, star.pos)
        val q5 = unit(cross(s5, DVec3(0.0, 1.0, 0.0)).x, cross(s5, DVec3(0.0, 1.0, 0.0)).y, cross(s5, DVec3(0.0, 1.0, 0.0)).z)
        out += View("bodies-e2-min-dot", add(p5.pos, rot(s5, q5, deg(60.0)), 3e9), p5.pos, zoom = giant.pos)

        // (i) eclipse: in PLANET II's penumbra, 5e7 m behind it, the star's centre half a star
        // radius inside the planet's disc (a crescent of the star, its glow and the backlit air)
        run {
            val l = 5.0e7
            val ds = dist(p2.pos, star.pos)
            val sep = kotlin.math.asin(p2.radius / l) - 0.5 * kotlin.math.asin(star.radius / (ds + l))
            val off = sep / (1.0 / l - 1.0 / (ds + l))
            val camI = add(add(p2.pos, s2, -l), q2, off)
            out += View("bodies-i-eclipse", camI, star.pos)
        }

        // (f) close to the gas giant, 8 deg above the ring plane on the day side: rings in front of
        // and behind the planet, the planet's shadow on the rings
        val ringN = DVec3()
        run {
            val v = com.starwake.core.math.Vec3()
            giant.rot.rotate(0f, 1f, 0f, v)
            ringN.set(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
        }
        val sg = dir(giant.pos, star.pos)
        val inPlane = unit(sg.x - ringN.x * dot(sg, ringN), sg.y - ringN.y * dot(sg, ringN), sg.z - ringN.z * dot(sg, ringN))
        val sideG = cross(ringN, inPlane)
        val camDirG = rot(rot(inPlane, sideG, deg(-60.0)), ringN, deg(8.0))
        val camF = add(giant.pos, camDirG, 3.4 * giant.radius)
        out += View("bodies-f-giant-rings", camF, giant.pos, ringN)

        // (h) the rings nearly edge-on (0.4 deg above their plane, 7 radii out) and (h2) exactly in
        // their plane; the camera rolled 12 deg so the ring line crosses pixel rows. Zoom on the
        // ring's tip, where it is thinnest
        for ((name, elev) in listOf("bodies-h-rings-edge-on" to 0.4, "bodies-h2-rings-in-plane" to 0.0)) {
            val camDir = rot(rot(inPlane, sideG, deg(-50.0)), ringN, deg(elev))
            val across = cross(ringN, camDir)
            val camH = add(giant.pos, camDir, 7.0 * giant.radius)
            out += View(name, camH, giant.pos, rot(ringN, across, deg(12.0)), zoom = add(giant.pos, across, 2.0 * giant.radius))
        }
        // (h3) the ringed giant small (1.5e9 m, 20 deg above the rings, ~25 px radius)
        run {
            val camDir = rot(rot(inPlane, sideG, deg(30.0)), ringN, deg(20.0))
            out += View("bodies-h3-rings-small", add(giant.pos, camDir, 1.5e9), giant.pos, rot(ringN, cross(ringN, camDir), deg(25.0)), zoom = giant.pos)
        }

        // (g) every surface type from 3.5 radii with the sun 60 deg to the right, 30 s later
        for (name in listOf("PLANET I", "PLANET II", "PLANET IV", "PLANET V")) {
            val b = body(system, name)
            val sb = dir(b.pos, star.pos)
            val qb = unit(cross(sb, DVec3(0.0, 1.0, 0.0)).x, cross(sb, DVec3(0.0, 1.0, 0.0)).y, cross(sb, DVec3(0.0, 1.0, 0.0)).z)
            val camDir = rot(sb, qb, deg(60.0))
            out += View("bodies-g-" + name.lowercase().replace(' ', '-'), add(b.pos, camDir, 3.5 * b.radius), b.pos, time = 30f)
        }
        return out
    }

    private fun dot(a: DVec3, b: DVec3) = a.x * b.x + a.y * b.y + a.z * b.z
}
