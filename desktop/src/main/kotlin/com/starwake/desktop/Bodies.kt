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
 * the home position, PLANET II from orbit and from 20 km, the star up close, far-away planets as
 * dots and a close look at every surface type. The background is the plain star-point layer.
 */
object Bodies {
    private class View(val name: String, val pos: DVec3, val look: DVec3, val up: DVec3 = DVec3(0.0, 1.0, 0.0), val time: Float = 0f)

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
            BodyRenderer(gl, system, 0, fullscreen).also { GLES20.glFinish() }.dispose()
            r.note("BodyRenderer(quality 0): programs + 512x256 bakes in %.0f ms".format((System.nanoTime() - t0) / 1e6))
            t0 = System.nanoTime()
            val bodies = BodyRenderer(gl, system, 2, fullscreen)
            GLES20.glFinish()
            r.note("BodyRenderer(quality 2): programs + 1024x512 bakes in %.0f ms".format((System.nanoTime() - t0) / 1e6))
            s.drainErrors(0)

            val cam = Camera()
            cam.far = 40000f
            var frame = 0L
            /** Renders [v] (camera moved by [shift] metres along its right axis); returns the render+finish time in ms. */
            fun draw(v: View, shift: Double = 0.0): Double {
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
                gl.uniform1f(starProg.u("u_bright"), 1f)
                starMesh.draw(GL.POINTS)
                gl.disable(GL.BLEND)
                val t = System.nanoTime()
                bodies.render(cam, v.time, 1f)
                GLES20.glFinish()
                return (System.nanoTime() - t) / 1e6
            }
            val all = views(system)
            for (v in all) {
                frame++
                val ms = draw(v)
                s.drainErrors(frame)
                r.note("%-22s render %.1f ms, camera %s".format(v.name, ms, describe(system, v.pos)))
                if (v.name == "bodies-e-dots" || v.name == "bodies-e2-min-dot") {
                    val pxAngle = 2.0 * cam.tanHalfY / h
                    for (b in system.bodies) {
                        val ang = kotlin.math.asin(b.radius / dist(v.pos, b.pos))
                        r.note("    %-10s true apparent radius %.2f px%s".format(b.name, ang / pxAngle, if (ang / pxAngle < BodyRenderer.MIN_PLANET_PX) " (drawn at the minimum)" else ""))
                    }
                }
                s.screenshot(v.name)
            }

            // temporal stability: the same view with the camera moved 1 m sideways must change
            // smoothly (sub-pixel parallax), never as speckle from float precision noise
            for (name in listOf("bodies-c-20km-horizon", "bodies-c3-1500m", "bodies-b-orbit-limb")) {
                val v = all.first { it.name == name }
                draw(v)
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
            starProg.dispose()
            starMesh.dispose()
            fullscreen.dispose()
            s.drainErrors(frame)
        }
    }

    /**
     * Debug montage of every baked surface texture (read back through an FBO; the texture ids are
     * private to BodyRenderer, so they are fetched by reflection). Left: sqrt-encoded albedo as
     * stored, right: the alpha channel (lava glow / city lights).
     */
    private fun dumpBakes(gl: Gl, bodies: BodyRenderer, system: StarSystem, file: File) {
        val f = BodyRenderer::class.java.getDeclaredField("textures").apply { isAccessible = true }
        val tex = f.get(bodies) as IntArray
        val ids = tex.indices.filter { tex[it] != 0 }
        val w = BodyRenderer.bakeWidth(2)
        val h = w / 2
        val img = BufferedImage(w * 2, h * ids.size, BufferedImage.TYPE_INT_RGB)
        val fbo = gl.genFramebuffer()
        gl.bindFramebuffer(GL.FRAMEBUFFER, fbo)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for ((row, i) in ids.withIndex()) {
            gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, tex[i], 0)
            buf.clear()
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            for (y in 0 until h) for (x in 0 until w) {
                val o = ((h - 1 - y) * w + x) * 4
                val rr = buf.get(o).toInt() and 0xFF
                val gg = buf.get(o + 1).toInt() and 0xFF
                val bb = buf.get(o + 2).toInt() and 0xFF
                val aa = buf.get(o + 3).toInt() and 0xFF
                img.setRGB(x, row * h + y, (rr shl 16) or (gg shl 8) or bb)
                img.setRGB(w + x, row * h + y, (aa shl 16) or (aa shl 8) or aa)
            }
            println("  bake ${system.bodies[i].name}: row $row")
        }
        gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
        gl.deleteFramebuffer(fbo)
        Screenshot.write(img, file)
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
     * Mirrors the planet shader: texture <- (spin about body Y) <- Body.rot⁻¹ <- world.
     */
    private fun surfacePoint(b: StarSystem.Body, sun: DVec3, u: Double, v: Double, elevation: Double): Pair<DVec3, Float> {
        val lon = (u - 0.5) * 2 * Math.PI
        val lat = (v - 0.5) * Math.PI
        val nx = cos(lat) * cos(lon)
        val ny = sin(lat)
        val nz = cos(lat) * sin(lon)
        val w = com.starwake.core.math.Vec3()
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

    private fun views(system: StarSystem): List<View> {
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
        val coast = surfacePoint(p2, s2, 0.43, 0.50, deg(25.0))
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

        // (c2) the same altitude at sunset, looking towards the star just above the horizon
        val upS = rot(q2, s2, deg(2.0))
        val camS = add(p2.pos, upS, p2.radius + 20_000.0)
        val sunHS = unit(s2.x - upS.x * dot(s2, upS), s2.y - upS.y * dot(s2, upS), s2.z - upS.z * dot(s2, upS))
        val lookS = add(add(camS, sunHS, 1e5), upS, -1e5 * kotlin.math.tan(deg(2.0)))
        out += View("bodies-c2-20km-sunset", camS, lookS, upS)

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
        out += View("bodies-e2-min-dot", add(p5.pos, rot(s5, q5, deg(60.0)), 3e9), p5.pos)

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
