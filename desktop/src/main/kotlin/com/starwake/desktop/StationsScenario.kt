package com.starwake.desktop

import com.starwake.core.content.Content
import com.starwake.core.content.Primitives
import com.starwake.core.content.StationMesh
import com.starwake.core.gfx.Environment
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
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import org.lwjgl.opengles.GLES20
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Renders every [StationMesh] model through the same lit shader (and glow sprites for its lights)
 * the scene uses for the ship, over the real background from [SceneRenderer], from several angles.
 * The station sits far from the asteroid field so nothing else is in the frame.
 */
object StationsScenario {
    private class View(
        val name: String,
        val model: Int,
        /** Station yaw (radians about +Y) – 1.2 turns the docking face towards the sun. */
        val yaw: Float,
        /** Camera eye and target in station-local metres. */
        val ex: Float, val ey: Float, val ez: Float,
        val tx: Float, val ty: Float, val tz: Float,
    )

    private val views = listOf(
        View("station-ring-front", 0, 1.2f, -900f, 600f, -2600f, 0f, 0f, -150f),
        View("station-ring-side", 0, 1.2f, -2700f, 520f, 350f, 0f, 0f, 0f),
        View("station-ring-rear", 0, 1.2f, 1500f, 900f, 2400f, 0f, 0f, 150f),
        View("station-ring-slot", 0, 1.2f, 150f, 80f, -900f, 0f, 0f, -300f),
        View("station-ring-far", 0, 0.4f, -3000f, 2000f, -8000f, 0f, 0f, 0f),
        // oblique approach in the game's orientation (yaw 0.4: the dock face is barely sunlit)
        View("station-ring-approach", 0, 0.4f, -260f, 90f, -1000f, 0f, 0f, -330f),
        View("station-outpost-front", 1, 1.2f, -380f, 260f, -680f, 0f, 0f, -40f),
        View("station-outpost-pad", 1, 1.2f, 520f, 380f, -260f, 110f, 0f, 0f),
        View("station-outpost-rear", 1, 1.2f, 330f, 120f, 640f, 0f, 0f, 0f),
        View("station-outpost-bay", 1, 1.2f, 45f, 28f, -360f, 0f, 0f, -130f),
        View("station-outpost-far", 1, 0.4f, -900f, 500f, -2600f, 0f, 0f, 0f),
        View("station-outpost-approach", 1, 0.4f, -90f, 30f, -420f, 0f, 0f, -140f),
    )

    /**
     * Where the models are shown: 4 km up and 40 km on from MERIDIAN's free-flight spawn, far from
     * every Sol station and body (the origin is inside the Sun), Earth in the sky.
     */
    private val stationPos = DVec3()

    fun run(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val gl = s.gl
            val w = opts.width
            val h = opts.height
            val platform = DesktopPlatform(opts.assetsDir)
            val content = Content(String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8), platform.readAsset("font/ui.sdf"))
            content.system.spaceSpawn(0, stationPos, com.starwake.core.math.Quat())
            stationPos.set(stationPos.x, stationPos.y + 4000.0, stationPos.z + 40_000.0)
            val scene = SceneRenderer(gl, content, 2)
            val lit = ShaderProgram(gl, "station-lit", Shaders.litVs(false), Shaders.litFs)
            val glow = ShaderProgram(gl, "station-glow", Shaders.glowVs, Shaders.glowFs)
            val stream = QuadStream(gl, 64, intArrayOf(3, GL.FLOAT, 3, GL.FLOAT, 4, GL.FLOAT))
            val envSize = 512
            val envTex = bakeEnvironment(gl, envSize)
            val envLod = ln(envSize.toFloat()) / ln(2f) - 1.5f
            val t0 = System.nanoTime()
            val data = Array(StationMesh.MODELS) { StationMesh.build(it) }
            r.note("StationMesh.build: %.1f ms for %d models".format((System.nanoTime() - t0) / 1e6, StationMesh.MODELS))
            for (m in data.indices) {
                r.note("model $m: ${data[m].size / 24} triangles, radius ${StationMesh.radius(m)} m, ${StationMesh.lights(m).size / 6} lights")
            }
            val meshes = Array(StationMesh.MODELS) { GpuMesh(gl, data[it], intArrayOf(3, 3, 2)) }
            val lights = Array(StationMesh.MODELS) { StationMesh.lights(it) }
            s.drainErrors(0)

            val f = SceneFrame()
            f.ship = null
            f.quality = 2
            f.pxScale = h / 1080f
            f.dustIntensity = 0f
            val rot = Quat()
            val model = Mat4()
            val eye = Vec3()
            val tgt = Vec3()
            val rel = Vec3()
            val tmp = Vec3()
            var frame = 0L
            for (v in views) {
                frame++
                f.time = 1f + frame
                rot.setAxisAngle(0f, 1f, 0f, v.yaw)
                rot.rotate(v.ex, v.ey, v.ez, eye)
                rot.rotate(v.tx, v.ty, v.tz, tgt)
                val cam = f.camera
                cam.pos.set(stationPos.x + eye.x, stationPos.y + eye.y, stationPos.z + eye.z)
                cam.rot.lookRotation(tgt.x - eye.x, tgt.y - eye.y, tgt.z - eye.z, 0f, 1f, 0f)
                cam.update(w, h)
                scene.render(f) // background: sky, stars, sun, planet

                stationPos.relativeTo(cam.pos, rel)
                model.setTRS(rel.x, rel.y, rel.z, rot, 1f)
                gl.depthRange(0f, 0.999f)
                gl.enable(GL.DEPTH_TEST)
                gl.depthFunc(GL.LEQUAL)
                gl.depthMask(true)
                gl.enable(GL.CULL_FACE)
                gl.cullFace(GL.BACK)
                beginLit(gl, lit, f, model, envTex, envLod)
                meshes[v.model].draw()

                // glow sprites for the station lights
                stream.begin()
                val l = lights[v.model]
                var i = 0
                while (i < l.size) {
                    rot.rotate(l[i], l[i + 1], l[i + 2], tmp)
                    val px = rel.x + tmp.x
                    val py = rel.y + tmp.y
                    val pz = rel.z + tmp.z
                    val dist = sqrt(px * px + py * py + pz * pz)
                    billboard(stream, cam.right, cam.up, px, py, pz, maxOf(4f, dist * 0.012f), l[i + 3], l[i + 4], l[i + 5], 0.9f)
                    i += 6
                }
                gl.enable(GL.BLEND)
                gl.blendFunc(GL.ONE, GL.ONE)
                gl.depthMask(false)
                gl.disable(GL.CULL_FACE)
                glow.use()
                gl.uniformMatrix4fv(glow.u("u_viewProj"), cam.viewProj.m, 0)
                gl.uniform1f(glow.u("u_time"), 0f)
                stream.flush()
                gl.disable(GL.BLEND)
                gl.depthMask(true)
                gl.depthRange(0f, 1f)
                gl.disable(GL.DEPTH_TEST)
                GLES20.glFinish()
                s.drainErrors(frame)
                s.screenshot(v.name)
            }
            for (m in meshes) m.dispose()
            stream.dispose()
            lit.dispose()
            glow.dispose()
            gl.deleteTexture(envTex)
            scene.dispose()
            s.drainErrors(frame)
        }
    }

    internal fun beginLit(gl: Gl, p: ShaderProgram, f: SceneFrame, model: Mat4, env: Int, envLod: Float) {
        val c = f.camera
        p.use()
        gl.uniformMatrix4fv(p.u("u_viewProj"), c.viewProj.m, 0)
        gl.uniformMatrix4fv(p.u("u_model"), model.m, 0)
        gl.uniform4fv(p.u("u_palette"), 10, StationMesh.palette, 0)
        gl.uniform4fv(p.u("u_matParams"), 10, StationMesh.params, 0)
        gl.uniform1f(p.u("u_time"), f.time)
        val sd = Environment.sunDir
        gl.uniform3f(p.u("u_sunDir"), sd.x, sd.y, sd.z)
        val sc = Environment.sunColor
        gl.uniform3f(p.u("u_sunColor"), sc[0], sc[1], sc[2])
        val pd = Environment.planetDir
        gl.uniform3f(p.u("u_fillDir"), pd.x, pd.y, pd.z)
        val fc = Environment.fillColor
        gl.uniform3f(p.u("u_fillColor"), fc[0], fc[1], fc[2])
        val a = Environment.ambient
        gl.uniform3f(p.u("u_ambient"), a[0], a[1], a[2])
        gl.uniform1f(p.u("u_engine"), 1f)
        gl.uniform1f(p.u("u_exposure"), f.exposure)
        gl.uniform1f(p.u("u_envLod"), envLod)
        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_CUBE_MAP, env)
        gl.uniform1i(p.u("u_env"), 0)
    }

    internal fun billboard(
        st: QuadStream, right: Vec3, up: Vec3, x: Float, y: Float, z: Float, size: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        if (!st.hasRoom()) return
        val rx = right.x * size
        val ry = right.y * size
        val rz = right.z * size
        val ux = up.x * size
        val uy = up.y * size
        val uz = up.z * size
        vertex(st, x - rx - ux, y - ry - uy, z - rz - uz, -1f, -1f, r, g, b, a)
        vertex(st, x + rx - ux, y + ry - uy, z + rz - uz, 1f, -1f, r, g, b, a)
        vertex(st, x - rx + ux, y - ry + uy, z - rz + uz, -1f, 1f, r, g, b, a)
        vertex(st, x + rx + ux, y + ry + uy, z + rz + uz, 1f, 1f, r, g, b, a)
        st.endQuad()
    }

    private fun vertex(st: QuadStream, x: Float, y: Float, z: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float) {
        val d = st.staging
        var o = st.cursor
        d[o++] = x.toRawBits(); d[o++] = y.toRawBits(); d[o++] = z.toRawBits()
        d[o++] = u.toRawBits(); d[o++] = v.toRawBits(); d[o++] = 0f.toRawBits()
        d[o++] = r.toRawBits(); d[o++] = g.toRawBits(); d[o++] = b.toRawBits(); d[o++] = a.toRawBits()
        st.cursor = o
    }

    /** Same nebula cube map bake as SceneRenderer's (reflections for the lit shader). */
    internal fun bakeEnvironment(gl: Gl, size: Int): Int {
        val tex = gl.genTexture()
        gl.bindTexture(GL.TEXTURE_CUBE_MAP, tex)
        for (face in 0 until 6) {
            gl.texImage2D(GL.TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL.RGBA8, size, size, GL.RGBA, GL.UNSIGNED_BYTE, null)
        }
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE)
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_WRAP_R, GL.CLAMP_TO_EDGE)
        val prog = ShaderProgram(gl, "station-nebula", Shaders.bakeVs, Shaders.nebulaFs)
        val tri = GpuMesh(gl, Primitives.fullscreenTriangle, intArrayOf(2))
        val fbo = gl.genFramebuffer()
        gl.bindFramebuffer(GL.FRAMEBUFFER, fbo)
        gl.viewport(0, 0, size, size)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.BLEND)
        gl.disable(GL.CULL_FACE)
        prog.use()
        for (face in 0 until 6) {
            gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_CUBE_MAP_POSITIVE_X + face, tex, 0)
            val b = FACE_BASIS[face]
            gl.uniform3f(prog.u("u_fz"), b[0], b[1], b[2])
            gl.uniform3f(prog.u("u_fx"), b[3], b[4], b[5])
            gl.uniform3f(prog.u("u_fy"), b[6], b[7], b[8])
            tri.draw()
        }
        gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
        gl.deleteFramebuffer(fbo)
        tri.dispose()
        prog.dispose()
        gl.bindTexture(GL.TEXTURE_CUBE_MAP, tex)
        gl.generateMipmap(GL.TEXTURE_CUBE_MAP)
        return tex
    }

    private val FACE_BASIS = arrayOf(
        floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, -1f, 0f),
        floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f),
        floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
        floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
        floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, -1f, 0f),
        floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, -1f, 0f),
    )
}
