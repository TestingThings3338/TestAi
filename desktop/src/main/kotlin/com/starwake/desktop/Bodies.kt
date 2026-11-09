package com.starwake.desktop

import com.starwake.core.content.Primitives
import com.starwake.core.content.StarField
import com.starwake.core.game.StarSystem
import com.starwake.core.gfx.BodyRenderer
import com.starwake.core.gfx.Camera
import com.starwake.core.gfx.GL
import com.starwake.core.gfx.Gl
import com.starwake.core.gfx.GpuMesh
import com.starwake.core.gfx.ShaderProgram
import com.starwake.core.gfx.Shaders
import com.starwake.core.math.DVec3
import com.starwake.core.world.WorldBuilder
import org.lwjgl.opengles.GLES20
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Sol through [BodyRenderer] directly (no Game, 03-world.md 9.5 "sol-views"):
 *  - the angular sizes of Appendix B.2, measured from the silhouette in the rendered frame (the
 *    alpha channel of a bodies-only pass: lit or not, a body's disc is opaque): Earth from
 *    MERIDIAN 37.5°, Luna from Earth 0.53°, Saturn from Titan, Jupiter from GALILEAN REFINERY
 *    55.5°, Mars from Phobos, the Sun from Earth 0.66°, each within 2 % of the geometry;
 *  - a look at every Sol body from 3.5 radii with the Sun to the side, the ringed giants (Saturn's
 *    bands and Cassini division, Uranus's ring standing at 82°, Jupiter's faint ring), Earth's limb
 *    from low orbit, the hosts from their stations, far planets as dots, an eclipse, a moon behind
 *    its planet's limb (the occluder test);
 *  - a few views at quality 0, a 1 m camera shift (float stability), and the baked textures.
 * The background is the star-point layer, dimmed by [BodyRenderer.skyFade] like the game does.
 */
object Bodies {
    private class View(
        val name: String, val pos: DVec3, val look: DVec3, val up: DVec3 = DVec3(0.0, 1.0, 0.0),
        val fovDeg: Float = 60f,
    )

    fun run(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val gl = s.gl
            val w = opts.width
            val h = opts.height
            val system = WorldBuilder.sol()
            val fullscreen = GpuMesh(gl, Primitives.fullscreenTriangle, intArrayOf(2))
            val starMesh = GpuMesh(gl, StarField.build(4500), intArrayOf(3, 4))
            val starProg = ShaderProgram(gl, "stars", Shaders.starVs, Shaders.starFs)

            // bake cost at both texture sizes
            var t0 = System.nanoTime()
            val bodiesQ0 = BodyRenderer(gl, system, 0, fullscreen)
            GLES20.glFinish()
            r.note("BodyRenderer(quality 0): programs + Sol bakes (512 wide planets) in %.0f ms".format((System.nanoTime() - t0) / 1e6))
            t0 = System.nanoTime()
            val bodies = BodyRenderer(gl, system, 2, fullscreen)
            GLES20.glFinish()
            r.note("BodyRenderer(quality 2): programs + Sol bakes (1024 wide planets) in %.0f ms".format((System.nanoTime() - t0) / 1e6))
            s.drainErrors(0)

            val cam = Camera()
            cam.far = 40000f
            var frame = 0L
            var fade = 0f

            /** Renders [v] with [renderer] (camera moved [shift] m along its right axis); render+finish ms. */
            fun draw(v: View, shift: Double = 0.0, renderer: BodyRenderer = bodies, withStars: Boolean = true): Double {
                val fx = v.look.x - v.pos.x
                val fy = v.look.y - v.pos.y
                val fz = v.look.z - v.pos.z
                cam.fovY = Math.toRadians(v.fovDeg.toDouble()).toFloat()
                cam.rot.lookRotation(fx.toFloat(), fy.toFloat(), fz.toFloat(), v.up.x.toFloat(), v.up.y.toFloat(), v.up.z.toFloat())
                cam.update(w, h)
                cam.pos.set(v.pos.x + cam.right.x * shift, v.pos.y + cam.right.y * shift, v.pos.z + cam.right.z * shift)
                gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
                gl.viewport(0, 0, w, h)
                gl.clearColor(0f, 0f, 0f, if (withStars) 1f else 0f)
                gl.clear(GL.COLOR_BUFFER_BIT or GL.DEPTH_BUFFER_BIT)
                if (withStars) {
                    gl.enable(GL.BLEND)
                    gl.blendFunc(GL.ONE, GL.ONE)
                    starProg.use()
                    gl.uniformMatrix4fv(starProg.u("u_viewProj"), cam.viewProj.m, 0)
                    gl.uniform1f(starProg.u("u_pxScale"), h / 1080f)
                    gl.uniform1f(starProg.u("u_bright"), 1f - fade)
                    starMesh.draw(GL.POINTS)
                    gl.disable(GL.BLEND)
                }
                val t = System.nanoTime()
                renderer.render(cam, 0f, 1f)
                GLES20.glFinish()
                fade = renderer.skyFade()
                return (System.nanoTime() - t) / 1e6
            }
            fun drawSettled(v: View, renderer: BodyRenderer = bodies): Double {
                draw(v, renderer = renderer)
                return draw(v, renderer = renderer)
            }

            // ---- the angular sizes of B.2, measured
            var failures = 0
            for (m in measurements(system)) {
                frame++
                // the look first (stars, settled sky), then a bodies-only pass for the silhouette
                drawSettled(m.view)
                s.drainErrors(frame)
                s.screenshot(m.view.name)
                draw(m.view, withStars = false)
                val measured = measureDisc(s, cam, w, h)
                val dev = (measured - m.geometric) / m.geometric * 100.0
                val ok = kotlin.math.abs(dev) <= 2.0
                if (!ok) failures++
                r.note(
                    "%-28s measured %7.3f°, geometry %7.3f° (%+.2f %%)%s; Appendix B.2: %s".format(
                        m.view.name, measured, m.geometric, dev, if (ok) "" else "  <-- off by more than 2 %", m.b2,
                    ),
                )
            }
            if (failures > 0) r.failure = IllegalStateException("$failures angular sizes off by more than 2 %")

            // ---- the looks
            val all = views(system)
            for (v in all) {
                frame++
                val ms = drawSettled(v)
                s.drainErrors(frame)
                r.note("%-28s render %.1f ms, skyFade %.2f, camera %s".format(v.name, ms, fade, describe(system, v.pos)))
                s.screenshot(v.name)
            }

            // the cheap path: quality 0 (half-size bakes, 5 atmosphere steps, no detail octaves)
            for (name in listOf("sol-earth-from-meridian", "sol-earth-limb-400km", "sol-saturn-rings", "sol-jupiter")) {
                val v = all.firstOrNull { it.name == name } ?: continue
                frame++
                val ms0 = drawSettled(v, bodiesQ0)
                s.drainErrors(frame)
                val shot = "sol-q0-" + name.removePrefix("sol-")
                r.note("%-28s render %.1f ms at quality 0, skyFade %.2f".format(shot, ms0, fade))
                s.screenshot(shot)
            }

            // temporal stability: the camera moved 1 m sideways changes the picture smoothly (sub-pixel
            // parallax), never as speckle from float precision noise
            for (name in listOf("sol-earth-20km-horizon", "sol-earth-limb-400km")) {
                val v = all.first { it.name == name }
                drawSettled(v)
                val a = s.target.readPixels()
                val copy = ByteBuffer.allocateDirect(a.capacity()).order(ByteOrder.nativeOrder()).put(a)
                draw(v, 1.0)
                val b = s.target.readPixels()
                var maxD = 0
                var sum = 0L
                var over4 = 0
                for (p in 0 until w * h) {
                    val o = p * 4
                    var dm = 0
                    for (k in 0 until 3) dm = maxOf(dm, kotlin.math.abs((copy.get(o + k).toInt() and 0xFF) - (b.get(o + k).toInt() and 0xFF)))
                    maxD = maxOf(maxD, dm)
                    sum += dm
                    if (dm > 4) over4++
                }
                r.note("%s moved 1 m: max channel diff %d, mean %.3f, %d px differ by > 4/255".format(name, maxD, sum.toDouble() / (w * h), over4))
            }
            dumpBakes(gl, bodies, system, File(opts.outDir, "sol-bakes.png"))
            r.note("baked equirect textures (top to bottom in body order, RGB | emission): ${File(opts.outDir, "sol-bakes.png").path}")
            bodies.dispose()
            bodiesQ0.dispose()
            starProg.dispose()
            starMesh.dispose()
            fullscreen.dispose()
            s.drainErrors(frame)
        }
    }

    private class Measurement(val view: View, val geometric: Double, val b2: String)

    /**
     * The angular-size views: the camera looks straight at the body's centre (the silhouette is a
     * circle around the screen centre) with a field of view about twice the body's size.
     */
    private fun measurements(system: StarSystem): List<Measurement> {
        fun body(n: String) = system.bodies[system.bodyIndex(n)]
        val out = ArrayList<Measurement>()
        fun add(name: String, eye: DVec3, target: StarSystem.Body, b2: String) {
            val d = dist(eye, target.pos)
            val geo = Math.toDegrees(2.0 * asin(target.radius / d))
            val fov = (geo * 2.2).coerceIn(1.0, 120.0).toFloat()
            out += Measurement(View(name, eye, target.pos, fovDeg = fov), geo, b2)
        }
        val earth = body("Earth")
        val luna = body("Luna")
        val meridian = system.stations[system.stationIndex("SOL-MERIDIAN")]
        add("sol-size-earth-from-meridian", DVec3().set(meridian.pos), earth, "37.5°")
        // Luna from just above Earth's air (100 km) under it: the same angle as from the surface
        add("sol-size-luna-from-earth", add(earth.pos, dir(earth.pos, luna.pos), earth.radius + 100_000.0), luna, "0.53° (from the surface)")
        val titan = body("Titan")
        val saturn = body("Saturn")
        add("sol-size-saturn-from-titan", add(titan.pos, dir(titan.pos, saturn.pos), titan.radius + 200_000.0), saturn, "5.67°")
        val galilean = system.stations[system.stationIndex("SOL-GALILEAN")]
        add("sol-size-jupiter-from-galilean", DVec3().set(galilean.pos), body("Jupiter"), "55.5°")
        val phobos = body("Phobos")
        val mars = body("Mars")
        add("sol-size-mars-from-phobos", add(phobos.pos, dir(phobos.pos, mars.pos), phobos.radius + 50_000.0), mars, "36.3°")
        val sun = system.bodies[system.star]
        add("sol-size-sun-from-earth", add(earth.pos, dir(earth.pos, sun.pos), earth.radius + 100_000.0), sun, "0.66°")
        return out
    }

    /**
     * Angular diameter (degrees) of the disc centred on the screen, from the alpha channel of a
     * bodies-only pass: the extent of alpha > 0.5 along the row and the column through the centre,
     * averaged, through the camera's projection.
     */
    private fun measureDisc(s: GlSession, cam: Camera, w: Int, h: Int): Double {
        val px = s.target.readPixels()
        fun alpha(x: Int, y: Int): Int = px.get(((h - 1 - y) * w + x) * 4 + 3).toInt() and 0xFF
        val cx = w / 2
        val cy = h / 2
        fun extent(dx: Int, dy: Int): Double {
            var k = 0
            var x = cx
            var y = cy
            while (x in 0 until w && y in 0 until h && alpha(x, y) > 127) {
                x += dx; y += dy; k++
            }
            // sub-pixel: the alpha of the edge pixel is its coverage
            val edge = if (x in 0 until w && y in 0 until h) alpha(x, y) / 255.0 else 0.0
            return k - 0.5 + edge
        }
        val f = (h * 0.5) / cam.tanHalfY
        val rx = (extent(1, 0) + extent(-1, 0)) * 0.5
        val ry = (extent(0, 1) + extent(0, -1)) * 0.5
        val radiusPx = (rx + ry) * 0.5
        return Math.toDegrees(2.0 * atan(radiusPx / f))
    }

    /** The look of every body and the showcase views. */
    private fun views(system: StarSystem): List<View> {
        fun body(n: String) = system.bodies[system.bodyIndex(n)]
        val star = system.bodies[system.star]
        val out = ArrayList<View>()

        /** From [k] radii out, with the Sun [sunDeg] degrees to the side and a little from above. */
        fun around(name: String, b: StarSystem.Body, k: Double, sunDeg: Double, elevDeg: Double = 12.0, fov: Float = 40f) {
            val s = dir(b.pos, star.pos)
            val q = unit(cross(s, DVec3(0.0, 1.0, 0.0)))
            val camDir = rot(rot(s, q, deg(sunDeg)), DVec3(0.0, 1.0, 0.0), deg(elevDeg))
            out += View(name, add(b.pos, camDir, k * b.radius), b.pos, fovDeg = fov)
        }

        // every body from 3.5 radii, the Sun 60° to the side
        for (b in system.bodies) {
            if (b.isStar) continue
            around("sol-body-" + b.name.lowercase(), b, 3.5, 60.0)
        }

        // the hosts from their stations (B.2: 37.5° / 19.6° / 28.4° / 55.5° / 24.2° / 21.2°)
        for (st in system.stations) {
            val h = system.bodies[st.parent]
            out += View("sol-from-" + st.id.removePrefix("SOL-").lowercase(), DVec3().set(st.pos), h.pos, fovDeg = 70f)
        }
        out.first { it.name == "sol-from-meridian" }.let { out += View("sol-earth-from-meridian", it.pos, it.look, fovDeg = 60f) }

        // Earth's limb and air from low orbit (400 km), at the terminator with the Sun just set
        val earth = body("Earth")
        val se = dir(earth.pos, star.pos)
        val qe = unit(cross(se, DVec3(0.0, 1.0, 0.0)))
        val upT = rot(qe, se, deg(-8.0))
        val camL = add(earth.pos, upT, earth.radius + 400_000.0)
        val horizon = rot(se, upT, deg(-6.0))
        out += View("sol-earth-limb-400km", camL, add(camL, horizon, 1e6), upT, fovDeg = 70f)
        // 20 km over the day side, looking along the horizon 70° from the Sun
        val upD = rot(se, qe, deg(55.0))
        val camD = add(earth.pos, upD, earth.radius + 20_000.0)
        val sunH = unit(DVec3(se.x - upD.x * dotp(se, upD), se.y - upD.y * dotp(se, upD), se.z - upD.z * dotp(se, upD)))
        val along = rot(sunH, cross(upD, sunH), deg(70.0))
        out += View("sol-earth-20km-horizon", camD, add(add(camD, along, 1e5), upD, -1e5 * tan(deg(3.0))), upD, fovDeg = 60f)

        // the ringed giants
        val saturn = body("Saturn")
        val ringN = DVec3(saturn.ringNormal.x.toDouble(), saturn.ringNormal.y.toDouble(), saturn.ringNormal.z.toDouble())
        val ss = dir(saturn.pos, star.pos)
        val inPlane = unit(DVec3(ss.x - ringN.x * dotp(ss, ringN), ss.y - ringN.y * dotp(ss, ringN), ss.z - ringN.z * dotp(ss, ringN)))
        val sideS = cross(ringN, inPlane)
        out += View("sol-saturn-rings", add(saturn.pos, rot(rot(inPlane, sideS, deg(-55.0)), ringN, deg(22.0)), 6.5 * saturn.radius), saturn.pos, ringN, 45f)
        out += View("sol-saturn-rings-edge", add(saturn.pos, rot(rot(inPlane, sideS, deg(-40.0)), ringN, deg(2.5)), 7.0 * saturn.radius), saturn.pos, ringN, 45f)
        // the Cassini division close up: above the B and A rings at 2 radii from the centre
        out += View(
            "sol-saturn-cassini", add(saturn.pos, rot(rot(inPlane, sideS, deg(-70.0)), ringN, deg(35.0)), 3.2 * saturn.radius),
            add(saturn.pos, rot(inPlane, sideS, deg(-70.0)), 1.95 * saturn.radius), ringN, 40f,
        )
        val uranus = body("Uranus")
        val un = DVec3(uranus.ringNormal.x.toDouble(), uranus.ringNormal.y.toDouble(), uranus.ringNormal.z.toDouble())
        // from 4 radii, looking across the ring plane from 30° above it, world +Y up: the ring stands almost upright
        val su = dir(uranus.pos, star.pos)
        val uIn = unit(DVec3(su.x - un.x * dotp(su, un), su.y - un.y * dotp(su, un), su.z - un.z * dotp(su, un)))
        out += View("sol-uranus-ring-82deg", add(uranus.pos, rot(rot(uIn, cross(un, uIn), deg(-50.0)), un, deg(30.0)), 4.0 * uranus.radius), uranus.pos, DVec3(0.0, 1.0, 0.0), 50f)
        val jupiter = body("Jupiter")
        out += View("sol-jupiter", add(jupiter.pos, rot(rot(dir(jupiter.pos, star.pos), DVec3(0.0, 1.0, 0.0), deg(-40.0)), DVec3(0.0, 1.0, 0.0), 0.0), 3.0 * jupiter.radius), jupiter.pos, fovDeg = 55f)
        // Io in front of Jupiter, then behind Jupiter's limb (the occluder test: it must not show through)
        val io = body("Io")
        val ji = dir(jupiter.pos, io.pos)
        out += View("sol-io-transit", add(io.pos, ji, 2.2 * jupiter.radius), jupiter.pos, fovDeg = 70f)
        val camOcc = add(jupiter.pos, rot(ji, unit(cross(ji, DVec3(0.0, 1.0, 0.0))), deg(-158.0)), 3.2 * jupiter.radius)
        out += View("sol-io-behind-limb", camOcc, jupiter.pos, fovDeg = 60f)

        // the Sun from Mercury (1.20°) and a solar eclipse by Luna seen from Earth's vicinity
        val mercury = body("Mercury")
        out += View("sol-sun-from-mercury", add(mercury.pos, dir(mercury.pos, star.pos), mercury.radius * 2.0), star.pos, fovDeg = 20f)
        // far planets as dots: from Earth towards Mars and Jupiter
        out += View("sol-dots-from-earth", add(earth.pos, dir(earth.pos, body("Mars").pos), earth.radius * 4.0), body("Mars").pos, fovDeg = 70f)
        return out
    }

    /** Debug montage of every baked surface texture (fetched by reflection), RGB | emission. */
    private fun dumpBakes(gl: Gl, bodies: BodyRenderer, system: StarSystem, file: File) {
        val texF = BodyRenderer::class.java.getDeclaredField("textures").apply { isAccessible = true }
        val hF = BodyRenderer::class.java.getDeclaredField("texHeight").apply { isAccessible = true }
        val tex = texF.get(bodies) as IntArray
        val hs = hF.get(bodies) as IntArray
        val ids = system.bodies.indices.filter { !system.bodies[it].isStar }
        val cellW = 512
        val cellH = 256
        val img = BufferedImage(cellW * 2, cellH * ids.size, BufferedImage.TYPE_INT_RGB)
        for ((row, i) in ids.withIndex()) {
            val th = hs[i]
            val tw = th * 2
            val fbo = gl.genFramebuffer()
            gl.bindFramebuffer(GL.FRAMEBUFFER, fbo)
            gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, tex[i], 0)
            val buf = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
            gl.deleteFramebuffer(fbo)
            for (y in 0 until cellH) for (x in 0 until cellW) {
                val sx = x * tw / cellW
                val sy = (cellH - 1 - y) * th / cellH
                val o = (sy * tw + sx) * 4
                val c = ((buf.get(o).toInt() and 0xFF) shl 16) or ((buf.get(o + 1).toInt() and 0xFF) shl 8) or (buf.get(o + 2).toInt() and 0xFF)
                val a = buf.get(o + 3).toInt() and 0xFF
                img.setRGB(x, row * cellH + y, c)
                img.setRGB(cellW + x, row * cellH + y, (a shl 16) or (a shl 8) or a)
            }
            println("  bake ${system.bodies[i].name}: ${tw}x$th, row $row")
        }
        Screenshot.write(img, file)
    }

    private fun describe(system: StarSystem, p: DVec3): String {
        var best = ""
        var bestAlt = Double.MAX_VALUE
        for (b in system.bodies) {
            val d = dist(p, b.pos)
            if ((d - b.radius) / b.radius < bestAlt) {
                bestAlt = (d - b.radius) / b.radius
                best = "%.3g m above %s (%.2f radii from its centre)".format(d - b.radius, b.name, d / b.radius)
            }
        }
        return best
    }

    private fun dist(a: DVec3, b: DVec3): Double {
        val x = a.x - b.x
        val y = a.y - b.y
        val z = a.z - b.z
        return sqrt(x * x + y * y + z * z)
    }

    private fun unit(v: DVec3): DVec3 {
        val l = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return DVec3(v.x / l, v.y / l, v.z / l)
    }

    private fun cross(a: DVec3, b: DVec3) = DVec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    private fun dotp(a: DVec3, b: DVec3) = a.x * b.x + a.y * b.y + a.z * b.z

    private fun add(p: DVec3, d: DVec3, s: Double) = DVec3(p.x + d.x * s, p.y + d.y * s, p.z + d.z * s)

    /** Unit vector from [from] towards [to]. */
    private fun dir(from: DVec3, to: DVec3) = unit(DVec3(to.x - from.x, to.y - from.y, to.z - from.z))

    /** [u] turned by [a] radians towards [v] (u·cos a + v·sin a, normalised). */
    private fun rot(u: DVec3, v: DVec3, a: Double) = unit(DVec3(u.x * cos(a) + v.x * sin(a), u.y * cos(a) + v.y * sin(a), u.z * cos(a) + v.z * sin(a)))

    private fun deg(d: Double) = d * Math.PI / 180.0
}
