package com.starwake.desktop

import com.starwake.core.content.Content
import com.starwake.core.content.StationMesh
import com.starwake.core.game.ShipState
import com.starwake.core.gfx.GL
import com.starwake.core.gfx.Gl
import com.starwake.core.gfx.GpuMesh
import com.starwake.core.gfx.QuadStream
import com.starwake.core.gfx.SceneFrame
import com.starwake.core.gfx.SceneRenderer
import com.starwake.core.gfx.ShaderProgram
import com.starwake.core.gfx.Shaders
import com.starwake.core.math.DVec3
import com.starwake.core.math.Mat4
import com.starwake.core.math.Vec3
import org.lwjgl.opengles.GLES20
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * [Gl] that forwards to [inner] and counts what the scene submits: draw calls, fullscreen-triangle
 * draws (3-vertex TRIANGLES arrays: the sky and full-screen body passes) and instanced vertices.
 */
class CountingGl(private val inner: Gl) : Gl by inner {
    var draws = 0
    var fullscreen = 0
    var instancedVertices = 0L

    fun reset() {
        draws = 0; fullscreen = 0; instancedVertices = 0
    }

    override fun drawArrays(mode: Int, first: Int, count: Int) {
        draws++
        if (mode == GL.TRIANGLES && count == 3) fullscreen++
        inner.drawArrays(mode, first, count)
    }

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) {
        draws++
        inner.drawElements(mode, count, type, offset)
    }

    override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) {
        draws++
        instancedVertices += count.toLong() * instances
        inner.drawArraysInstanced(mode, first, count, instances)
    }
}

/**
 * Rendering checks for the scene's depth handling and body culling, through the real
 * [SceneRenderer] (no Game):
 *  - "station-depth": MERIDIAN STATION from an oblique front view at 1..200 km with a telephoto lens,
 *    compared against a reference that draws the station alone with a projection fitted to it
 *    (near = d - R, far = d + R), so depth precision cannot be the cause of a difference;
 *  - "render-probe": draw calls, depth slices and full-screen body passes per frame for spawn,
 *    30 km from the station, deep space, leaving the field (40 and 55 km) and the star behind / in front
 *    of the camera.
 */
object RenderProbe {
    private val distancesKm = intArrayOf(1, 2, 5, 12, 30, 60, 200)

    fun stationDepth(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val gl = CountingGl(s.gl)
            val w = opts.width
            val h = opts.height
            val platform = DesktopPlatform(opts.assetsDir)
            val content = Content(String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8), platform.readAsset("font/ui.sdf"))
            val scene = SceneRenderer(gl, content, 2)
            val system = content.system
            val st = system.stations[system.stationIndex("SOL-MERIDIAN")]
            val radius = StationMesh.radius(st.model)

            // reference renderer: the station mesh + its light sprites, unscaled, fitted depth range
            val lit = ShaderProgram(gl, "ref-lit", Shaders.litVs(false), Shaders.litFs)
            val glow = ShaderProgram(gl, "ref-glow", Shaders.glowVs, Shaders.glowFs)
            val stream = QuadStream(gl, 64, intArrayOf(3, GL.FLOAT, 3, GL.FLOAT, 4, GL.FLOAT))
            val mesh = GpuMesh(gl, StationMesh.build(st.model), intArrayOf(3, 3, 2))
            val lights = StationMesh.lights(st.model)
            val envTex = StationsScenario.bakeEnvironment(gl, 512)
            val envLod = ln(512f) / ln(2f) - 1.5f
            val refProj = Mat4()
            val refViewProj = Mat4()
            val model = Mat4()
            val rel = Vec3()
            val tmp = Vec3()

            // oblique front view: in front of the docking face, to the left and above
            val dir = Vec3()
            st.rot.rotate(-0.55f, 0.35f, -1f, dir)
            dir.normalize()
            val f = SceneFrame()
            f.ship = null
            f.quality = 2
            f.pxScale = h / 1080f
            f.dustIntensity = 0f
            f.time = 0.3f // red lights on
            var frame = 0L
            for (km in distancesKm) {
                frame++
                val d = km * 1000.0
                val cam = f.camera
                cam.pos.set(st.pos.x + dir.x * d, st.pos.y + dir.y * d, st.pos.z + dir.z * d)
                cam.rot.lookRotation(-dir.x, -dir.y, -dir.z, 0f, 1f, 0f)
                // telephoto: the station spans ~80% of the height at every distance
                cam.fovY = (2.0 * atan(1.25 * radius / d)).toFloat()
                cam.update(w, h)
                gl.reset()
                scene.render(f)
                GLES20.glFinish()
                s.drainErrors(frame)
                val draws = gl.draws
                val game = copy(s.target.readPixels())
                s.screenshot("depth-${km}km")

                // reference: black background, the station alone in a projection fitted to it
                gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
                gl.viewport(0, 0, w, h)
                gl.depthMask(true)
                gl.clearColor(0f, 0f, 0f, 1f)
                gl.clear(GL.COLOR_BUFFER_BIT or GL.DEPTH_BUFFER_BIT)
                st.pos.relativeTo(cam.pos, rel)
                val dist = rel.length()
                refProj.perspective(cam.fovY, w.toFloat() / h, dist - radius - 10f, dist + radius + 10f)
                refViewProj.mul(refProj, cam.view)
                model.setTRS(rel.x, rel.y, rel.z, st.rot, 1f)
                gl.enable(GL.DEPTH_TEST)
                gl.depthFunc(GL.LEQUAL)
                gl.enable(GL.CULL_FACE)
                gl.cullFace(GL.BACK)
                gl.depthRange(0f, 1f)
                StationsScenario.beginLit(gl, lit, f, model, envTex, envLod)
                gl.uniformMatrix4fv(lit.u("u_viewProj"), refViewProj.m, 0)
                mesh.draw()
                // station pixels (opaque geometry) are compared; glows over the sky are not
                GLES20.glFinish()
                val mask = copy(s.target.readPixels())
                stream.begin()
                var i = 0
                while (i < lights.size) {
                    st.rot.rotate(lights[i], lights[i + 1], lights[i + 2], tmp)
                    // the game's sprite size: grows with distance up to 60 m at 5 km, then keeps its apparent size
                    val dEff = minOf(dist, 20_000f)
                    val size = maxOf(6f, minOf(dEff * 0.012f, 60f)) * dist / dEff
                    StationsScenario.billboard(
                        stream, cam.right, cam.up, rel.x + tmp.x, rel.y + tmp.y, rel.z + tmp.z, size,
                        lights[i + 3], lights[i + 4], lights[i + 5], 0.9f,
                    )
                    i += 6
                }
                gl.enable(GL.BLEND)
                gl.blendFunc(GL.ONE, GL.ONE)
                gl.depthMask(false)
                gl.disable(GL.CULL_FACE)
                glow.use()
                gl.uniformMatrix4fv(glow.u("u_viewProj"), refViewProj.m, 0)
                gl.uniform1f(glow.u("u_time"), 0f)
                stream.flush()
                gl.disable(GL.BLEND)
                gl.depthMask(true)
                gl.disable(GL.DEPTH_TEST)
                GLES20.glFinish()
                s.drainErrors(frame)
                val ref = s.target.readPixels()
                s.screenshot("depth-${km}km-ref")
                val cmp = compare(game, ref, mask, w * h)
                r.note(
                    "%3d km: %d draw calls; station pixels %d, differing from the fitted-depth reference by > 24/255: %d (%.2f%% of the frame, %.1f%% of the station)"
                        .format(km, draws, cmp[0], cmp[1], cmp[1] * 100.0 / (w * h), cmp[1] * 100.0 / maxOf(1, cmp[0])),
                )
                zoom(s, "depth-${km}km", minOf(w, h) / 2, minOf(w, h) / 2, 2)
            }
            mesh.dispose(); stream.dispose(); lit.dispose(); glow.dispose()
            gl.deleteTexture(envTex)
            scene.dispose()
        }
    }

    /**
     * Draw calls, depth slices and full-screen body draws per frame, plus the GPU time
     * (render + glFinish) of each view.
     */
    fun passes(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val gl = CountingGl(s.gl)
            val w = opts.width
            val h = opts.height
            val platform = DesktopPlatform(opts.assetsDir)
            val content = Content(String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8), platform.readAsset("font/ui.sdf"))
            val scene = SceneRenderer(gl, content, 2)
            val model = com.starwake.core.content.ShipModel.load("varrow") { platform.readAsset(it) }
            scene.setShipModel(model)
            val system = content.system
            val home = system.stations[system.stationIndex("SOL-MERIDIAN")]
            val ship = ShipState()
            val f = SceneFrame()
            f.gear = com.starwake.core.game.GearRig().also { it.setModel(model, down = false) }
            f.shipReach = 250f
            f.quality = 2
            f.pxScale = h / 1080f
            f.time = 0.3f
            val star = system.bodies[0]
            val p1 = system.bodies[system.bodyIndex("Mercury")]
            val out = Vec3()
            var frame = 0L

            /** Camera at [pos] looking along [look]; the ship 16.5 m ahead and 4 m below, like the chase camera. */
            fun view(name: String, pos: DVec3, look: Vec3, withShip: Boolean = true, zoomCentre: Boolean = false) {
                frame++
                val cam = f.camera
                cam.fovY = 55f * com.starwake.core.math.DEG2RAD
                cam.pos.set(pos)
                cam.rot.lookRotation(look.x, look.y, look.z, 0f, 1f, 0f)
                cam.update(w, h)
                if (withShip) {
                    // the VARROW's centre 125 m ahead and 26 m below, like the chase camera
                    ship.rot.set(cam.rot)
                    ship.rot.rotate(model.centreX, model.centreY, model.centreZ, out)
                    ship.pos.set(
                        pos.x + cam.forward.x * 125.0 - cam.up.x * 26.0 - out.x,
                        pos.y + cam.forward.y * 125.0 - cam.up.y * 26.0 - out.y,
                        pos.z + cam.forward.z * 125.0 - cam.up.z * 26.0 - out.z,
                    )
                    ship.engineOutput = 0.4f
                    f.ship = ship
                } else {
                    f.ship = null
                }
                // settle (skyFade comes from the previous frame), then measure
                scene.render(f)
                GLES20.glFinish()
                gl.reset()
                val t0 = System.nanoTime()
                scene.render(f)
                GLES20.glFinish()
                val ms = (System.nanoTime() - t0) / 1e6
                s.drainErrors(frame)
                r.note(
                    "%-22s %2d draw calls, %d depth slice(s), %d body full-screen draw(s), %6.0fk instanced vertices, render+finish %.1f ms"
                        .format(name, gl.draws, scene.depthSlices, gl.fullscreen - 1, gl.instancedVertices / 1000.0, ms),
                )
                s.screenshot("probe-$name")
                if (zoomCentre) zoom(s, "probe-$name", 400, 300, 3)
            }

            // spawn: chase camera behind the ship at the free-flight spawn off MERIDIAN, nose to Earth
            val hp = DVec3()
            val hq = com.starwake.core.math.Quat()
            system.spaceSpawn(system.stationIndex("SOL-MERIDIAN"), hp, hq)
            val nose = hq.rotate(0f, 0f, -1f, Vec3())
            view("spawn", DVec3(hp.x - nose.x * 16.5, hp.y - nose.y * 16.5 + 4.0, hp.z - nose.z * 16.5), out.set(nose))
            // 30 km out in front of MERIDIAN, looking back at it (Earth behind the station)
            val dir = Vec3()
            home.rot.rotate(-0.55f, 0.35f, -1f, dir)
            dir.normalize()
            val at30 = DVec3(home.pos.x + dir.x * 30_000.0, home.pos.y + dir.y * 30_000.0, home.pos.z + dir.z * 30_000.0)
            view("station-30km", at30, out.set(-dir.x, -dir.y, -dir.z))
            view("station-30km-away", at30, out.set(dir.x, dir.y, dir.z))
            // deep space: a quarter of the way from Earth to the star, 200 Mm above the plane, looking at it
            val deep = DVec3(hp.x * 0.75, hp.y * 0.75 + 2.0e8, hp.z * 0.75)
            view("deep-space", deep, out.set(-deep.x.toFloat(), -deep.y.toFloat(), -deep.z.toFloat()))

            // F5: 3 radii from Mercury on its night side (1.38 Gm from the star, where the glow's
            // fade-out cone is wider than a quad allows), nose away from / towards the star
            val ux = p1.pos.x - star.pos.x
            val uy = p1.pos.y - star.pos.y
            val uz = p1.pos.z - star.pos.z
            val ul = sqrt(ux * ux + uy * uy + uz * uz)
            val nx = ux / ul
            val ny = uy / ul
            val nz = uz / ul
            for ((label, alt) in listOf("3r" to 2.0 * p1.radius, "50km" to 50_000.0)) {
                val d = p1.radius + alt
                val p = DVec3(p1.pos.x + nx * d, p1.pos.y + ny * d, p1.pos.z + nz * d)
                view("planet1-$label-away", p, out.set(nx.toFloat(), ny.toFloat(), nz.toFloat()), withShip = false)
                view("planet1-$label-sunward", p, out.set(-nx.toFloat(), -ny.toFloat() + 0.02f, -nz.toFloat()), withShip = false)
            }
            scene.dispose()
        }
    }

    private fun copy(src: ByteBuffer): ByteBuffer {
        val c = ByteBuffer.allocateDirect(src.capacity()).order(ByteOrder.nativeOrder())
        src.position(0)
        c.put(src)
        src.position(0)
        c.position(0)
        return c
    }

    /**
     * [0] = station pixels ([mask]: the reference before its light sprites, not black), [1] = those
     * where [game] and [ref] differ by > 24/255 in a channel.
     */
    private fun compare(game: ByteBuffer, ref: ByteBuffer, mask: ByteBuffer, n: Int): IntArray {
        var covered = 0
        var diff = 0
        for (p in 0 until n) {
            val o = p * 4
            var maxMask = 0
            var maxDiff = 0
            for (ch in 0 until 3) {
                val a = game.get(o + ch).toInt() and 0xFF
                val b = ref.get(o + ch).toInt() and 0xFF
                maxMask = maxOf(maxMask, mask.get(o + ch).toInt() and 0xFF)
                maxDiff = maxOf(maxDiff, kotlin.math.abs(a - b))
            }
            if (maxMask <= 2) continue
            covered++
            if (maxDiff > 24) diff++
        }
        return intArrayOf(covered, diff)
    }

    /** [scale]x nearest-neighbour crop ([cw] x [ch]) of the centre of screenshot [name], as `name-zoom.png`. */
    private fun zoom(s: GlSession, name: String, cw: Int, ch: Int, scale: Int) {
        val src = javax.imageio.ImageIO.read(File(s.opts.outDir, "$name.png"))
        val img = java.awt.image.BufferedImage(cw * scale, ch * scale, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val x0 = src.width / 2 - cw / 2
        val y0 = src.height / 2 - ch / 2
        for (y in 0 until ch * scale) for (x in 0 until cw * scale) img.setRGB(x, y, src.getRGB(x0 + x / scale, y0 + y / scale))
        Screenshot.write(img, File(s.opts.outDir, "$name-zoom.png"))
    }
}
