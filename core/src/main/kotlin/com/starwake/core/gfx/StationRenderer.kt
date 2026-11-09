package com.starwake.core.gfx

import com.starwake.core.content.StationModel
import com.starwake.core.math.Mat4
import com.starwake.core.math.TAU_F
import com.starwake.core.station.StationInstance
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Draws the Roblox stations ([StationModel]) with their runtime state ([StationInstance]).
 *
 * Per station and frame, at most one draw call per pass and detail level: the opaque hull and
 * paint, the sign text, the translucent parts, the lamps (blinkers and pad lamps, blended because
 * their opacity changes), and for a build site the hologram of the next stage; plus one per light
 * beam (the Spire's lighthouse). Rotating groups and lamp colours are uniforms (uploaded only when
 * they change), so no mesh is ever rebuilt. Detail levels ([lodFor]): 0 within 3 km, 1 to 15 km,
 * 2 to 60 km (Roblox's LodDistance); beyond that [drawMarker] shows a dot instead.
 *
 * The caller passes the station's camera-relative pose ([pose]: station space -> camera-relative
 * world, rotation and translation, optionally a uniform scale for a far depth slice) and the
 * matching view-projection; depth slices stay the scene's business (SceneRenderer.planSlices).
 * Nothing allocates per frame.
 *
 * GL state: the caller enables DEPTH_TEST (LEQUAL) and CULL_FACE (back) and sets the depth range,
 * as for the rest of the scene. [drawOpaque] writes depth with blending off; [drawTransparent]
 * blends (premultiplied) without depth writes and leaves depth writes on and blending off;
 * [drawMarker] likewise. Per frame: [begin] once, then per station [drawOpaque], the rest of the
 * opaque scene (a ship on a pad), then [drawTransparent].
 */
class StationRenderer(private val gl: Gl, val font: FontData, fontAtlas: ByteArray) {
    private val mesh = ShaderProgram(gl, "station", StationShaders.meshVs, StationShaders.meshFs)
    private val text = ShaderProgram(gl, "station-text", StationShaders.textVs, StationShaders.textFs)
    private val beam = ShaderProgram(gl, "station-beam", StationShaders.beamVs, StationShaders.beamFs)
    private val marker = ShaderProgram(gl, "station-marker", StationShaders.markerVs, StationShaders.markerFs)
    private val fontTex: Int
    private val beamMesh: GpuMesh
    private val markerMesh: GpuMesh

    /** GPU copy of a model, shared by every station that uses it. */
    inner class ModelGpu internal constructor(val model: StationModel) {
        internal val vao = gl.genVertexArray()
        internal val vbo = gl.genBuffer()
        internal val ibo = gl.genBuffer()
        internal val indexType = if (model.mesh.indexSize == 2) GL.UNSIGNED_SHORT else GL.UNSIGNED_INT

        init {
            val m = model.mesh
            gl.bindVertexArray(vao)
            gl.bindBuffer(GL.ARRAY_BUFFER, vbo)
            gl.bufferData(GL.ARRAY_BUFFER, m.vertices.size, Buffers.of(m.vertices), GL.STATIC_DRAW)
            val stride = StationModel.VERTEX_BYTES
            gl.enableVertexAttribArray(0)
            gl.vertexAttribPointer(0, 4, GL.UNSIGNED_SHORT, false, stride, 0)
            gl.enableVertexAttribArray(1)
            gl.vertexAttribPointer(1, 4, GL.BYTE, true, stride, 8)
            gl.enableVertexAttribArray(2)
            gl.vertexAttribPointer(2, 4, GL.UNSIGNED_BYTE, true, stride, 12)
            gl.enableVertexAttribArray(3)
            gl.vertexAttribPointer(3, 1, GL.UNSIGNED_BYTE, false, stride, 11)
            gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, ibo)
            gl.bufferData(GL.ELEMENT_ARRAY_BUFFER, m.indices.size, Buffers.of(m.indices), GL.STATIC_DRAW)
            gl.bindVertexArray(0)
        }

        fun dispose() {
            gl.deleteVertexArray(vao)
            gl.deleteBuffer(vbo)
            gl.deleteBuffer(ibo)
        }
    }

    /**
     * The sign texts of one placed station, laid out once with its [name] (the model's Sign label,
     * Roblox's def.Name; the pad numbers stay "01".."06"). Rebuild it when the name changes.
     */
    inner class Signs internal constructor(val model: StationModel, val name: String) {
        internal val vao = gl.genVertexArray()
        internal val vbo = gl.genBuffer()
        internal val ibo = gl.genBuffer()

        /** Glyph quads laid out. */
        val glyphs: Int

        init {
            val data = SignLayout.build(model, name, font)
            glyphs = data.limit() / (SignLayout.VERTEX_BYTES * 4)
            gl.bindVertexArray(vao)
            gl.bindBuffer(GL.ARRAY_BUFFER, vbo)
            gl.bufferData(GL.ARRAY_BUFFER, maxOf(data.limit(), 4), data, GL.STATIC_DRAW)
            val s = SignLayout.VERTEX_BYTES
            gl.enableVertexAttribArray(0)
            gl.vertexAttribPointer(0, 3, GL.FLOAT, false, s, 0)
            gl.enableVertexAttribArray(1)
            gl.vertexAttribPointer(1, 2, GL.UNSIGNED_SHORT, true, s, 12)
            gl.enableVertexAttribArray(2)
            gl.vertexAttribPointer(2, 4, GL.UNSIGNED_BYTE, true, s, 16)
            gl.enableVertexAttribArray(3)
            gl.vertexAttribPointer(3, 4, GL.BYTE, true, s, 20)
            gl.enableVertexAttribArray(4)
            gl.vertexAttribPointer(4, 4, GL.UNSIGNED_BYTE, false, s, 24)
            val idx = ShortArray(glyphs * 6)
            for (q in 0 until glyphs) {
                val v = q * 4
                idx[q * 6] = v.toShort(); idx[q * 6 + 1] = (v + 2).toShort(); idx[q * 6 + 2] = (v + 1).toShort()
                idx[q * 6 + 3] = (v + 1).toShort(); idx[q * 6 + 4] = (v + 2).toShort(); idx[q * 6 + 5] = (v + 3).toShort()
            }
            gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, ibo)
            gl.bufferData(GL.ELEMENT_ARRAY_BUFFER, maxOf(idx.size * 2, 4), Buffers.of(idx), GL.STATIC_DRAW)
            gl.bindVertexArray(0)
        }

        fun dispose() {
            gl.deleteVertexArray(vao)
            gl.deleteBuffer(vbo)
            gl.deleteBuffer(ibo)
        }
    }

    // mesh program uniforms
    private val uViewProj = mesh.u("u_viewProj")
    private val uModel = mesh.u("u_model")
    private val uQuant = mesh.u("u_quant")
    private val uGroups = mesh.u("u_groups")
    private val uGroupMode = mesh.u("u_groupMode")
    private val uLamps = mesh.u("u_lamps")
    private val uMat = mesh.u("u_mat")
    private val uPass = mesh.u("u_pass")
    private val uDecal = mesh.u("u_decal")
    private val uHolo = mesh.u("u_holo")
    private val uLightCount = mesh.u("u_lightCount")
    private val uLightPos = mesh.u("u_lightPos")
    private val uLightCol = mesh.u("u_lightCol")
    private val uLightDir = mesh.u("u_lightDir")

    // per-frame lighting (begin)
    private var frame: SceneFrame? = null
    private var env = 0
    private var envLod = 0f

    // what the programs hold (uploads only on change)
    private var meshInstance: StationInstance? = null
    private var meshLampVersion = -1
    private var meshGroupVersion = -1
    private var meshAngles = FloatArray(StationModel.MAX_GROUPS)
    private var textInstance: StationInstance? = null
    private var textGroupVersion = -1
    private val textAngles = FloatArray(StationModel.MAX_GROUPS)

    // scratch
    private val groupRows = FloatArray(StationModel.MAX_GROUPS * 12)
    private val lightPos = FloatArray(StationShaders.MAX_LIGHTS * 4)
    private val lightCol = FloatArray(StationShaders.MAX_LIGHTS * 4)
    private val lightDir = FloatArray(StationShaders.MAX_LIGHTS * 4)
    private val lightOrder = IntArray(64)
    private val lightKey = FloatArray(64)
    private val camStation = FloatArray(3)
    private val tmp = FloatArray(4)

    /** Draw calls and triangles since the last [resetStats]. */
    var drawCalls = 0
        private set
    var triangles = 0
        private set

    /** Lights evaluated per pixel at most (0 turns them off, e.g. on low quality). */
    var maxLights = StationShaders.MAX_LIGHTS

    fun resetStats() {
        drawCalls = 0
        triangles = 0
    }

    init {
        val (w, h, px) = FontData.atlasPixels(fontAtlas)
        fontTex = gl.genTexture()
        gl.bindTexture(GL.TEXTURE_2D, fontTex)
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 1)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.R8, w, h, GL.RED, GL.UNSIGNED_BYTE, px)
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 4)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
        gl.generateMipmap(GL.TEXTURE_2D)
        // beam ribbon: BEAM_SEGMENTS segments, (t, side) per vertex, drawn as a strip
        val bd = FloatArray((BEAM_SEGMENTS + 1) * 4)
        for (i in 0..BEAM_SEGMENTS) {
            val t = i.toFloat() / BEAM_SEGMENTS
            bd[i * 4] = t; bd[i * 4 + 1] = -1f
            bd[i * 4 + 2] = t; bd[i * 4 + 3] = 1f
        }
        beamMesh = GpuMesh(gl, bd, intArrayOf(2))
        markerMesh = GpuMesh(gl, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), intArrayOf(2))
        mesh.use()
        gl.uniform4fv(uMat, 16, MATERIAL_PARAMS, 0)
    }

    /** Uploads [model] (not per frame). */
    fun upload(model: StationModel): ModelGpu = ModelGpu(model)

    /** Lays out [model]'s sign texts with the station [name] (not per frame). */
    fun signs(model: StationModel, name: String): Signs = Signs(model, name)

    /** Sets the frame's lighting and the environment cube map; call once per frame before drawing. */
    fun begin(f: SceneFrame, envTex: Int, envLod: Float) {
        frame = f
        env = envTex
        this.envLod = envLod
        mesh.use()
        lighting(mesh)
        gl.uniform1f(mesh.u("u_fieldPhase"), (f.time * FIELD_SHIMMER_SPEED) % TAU_F)
        gl.uniform1f(mesh.u("u_envLod"), envLod)
        text.use()
        lighting(text)
    }

    private fun lighting(p: ShaderProgram) {
        val f = frame ?: return
        val s = f.sunDir
        gl.uniform3f(p.u("u_sunDir"), s.x, s.y, s.z)
        val sc = Environment.sunColor
        val v = f.sunVisibility
        gl.uniform3f(p.u("u_sunColor"), sc[0] * v, sc[1] * v, sc[2] * v)
        val a = Environment.ambient
        gl.uniform3f(p.u("u_ambient"), a[0] * STATION_AMBIENT, a[1] * STATION_AMBIENT, a[2] * STATION_AMBIENT)
        gl.uniform1f(p.u("u_exposure"), f.exposure)
        if (p === mesh) {
            val fd = f.fillDir
            gl.uniform3f(p.u("u_fillDir"), fd.x, fd.y, fd.z)
            val fc = f.fillColor
            gl.uniform3f(p.u("u_fillColor"), fc[0], fc[1], fc[2])
        }
    }

    /**
     * Opaque hull and paint of [st] at detail level [lod] with the camera-relative [pose], depth
     * written. [depthFraction] is the share of the depth range the slice uses (0.5 when the scene
     * splits it), which sets the paint's depth bias.
     */
    fun drawOpaque(gpu: ModelGpu, st: StationInstance, pose: Mat4, viewProj: Mat4, lod: Int, depthFraction: Float = 1f) {
        val m = gpu.model.mesh
        val n = m.count(StationModel.PASS_OPAQUE, lod)
        if (n == 0) return
        bindMesh(gpu, st, pose, viewProj, depthFraction)
        gl.depthMask(true)
        gl.disable(GL.BLEND)
        gl.uniform1i(uPass, StationShaders.PASS_OPAQUE)
        drawRange(gpu, m.first(StationModel.PASS_OPAQUE), n)
    }

    /**
     * Everything blended, after the opaque scene: sign text (detail level 0 only), translucent parts,
     * lamps, a build site's hologram stage and light beams. Depth tested, not written.
     */
    fun drawTransparent(
        gpu: ModelGpu, st: StationInstance, signs: Signs?, pose: Mat4, viewProj: Mat4, lod: Int, depthFraction: Float = 1f,
    ) {
        val model = gpu.model
        val m = model.mesh
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE_MINUS_SRC_ALPHA)
        gl.depthMask(false)
        if (lod == 0 && signs != null && signs.glyphs > 0) drawSigns(signs, st, pose, viewProj, depthFraction)
        bindMesh(gpu, st, pose, viewProj, depthFraction)
        val nt = m.count(StationModel.PASS_TRANSLUCENT, lod)
        if (nt > 0) {
            gl.uniform1i(uPass, StationShaders.PASS_TRANSLUCENT)
            drawRange(gpu, m.first(StationModel.PASS_TRANSLUCENT), nt)
        }
        val nl = m.count(StationModel.PASS_LAMP, lod)
        if (nl > 0) {
            gl.uniform1i(uPass, StationShaders.PASS_LAMP)
            drawRange(gpu, m.first(StationModel.PASS_LAMP), nl)
        }
        if (model.isSite) drawHologram(gpu, st, lod)
        if (model.beams.isNotEmpty() && lod <= 1) drawBeams(model, st, pose, viewProj)
        gl.depthMask(true)
        gl.disable(GL.BLEND)
    }

    /** The plan stage of a build site: its parts (every pass) through the hologram shading. */
    private fun drawHologram(gpu: ModelGpu, st: StationInstance, lod: Int) {
        val g = st.siteBuilt + 1
        val m = gpu.model.mesh
        if (g >= m.groupCount) return
        gl.uniform1i(uPass, StationShaders.PASS_HOLOGRAM)
        val c = StationInstance.HOLO_COLOR
        gl.uniform3f(uHolo, c[0], c[1], c[2])
        for (p in 0 until StationModel.PASSES) {
            for (cls in 2 downTo lod) {
                val n = m.count(p, cls, g)
                if (n > 0) drawRange(gpu, m.first(p, cls, g), n)
            }
        }
    }

    private fun drawRange(gpu: ModelGpu, first: Int, count: Int) {
        gl.bindVertexArray(gpu.vao)
        gl.drawElements(GL.TRIANGLES, count, gpu.indexType, first * gpu.model.mesh.indexSize)
        drawCalls++
        triangles += count / 3
    }

    private fun bindMesh(gpu: ModelGpu, st: StationInstance, pose: Mat4, viewProj: Mat4, depthFraction: Float) {
        check(frame != null) { "StationRenderer.begin not called" }
        mesh.use()
        gl.uniformMatrix4fv(uViewProj, viewProj.m, 0)
        gl.uniformMatrix4fv(uModel, pose.m, 0)
        val m = gpu.model.mesh
        gl.uniform4f(uQuant, m.originX, m.originY, m.originZ, m.step)
        gl.uniform1f(uDecal, DECAL_QUANTA * 2f / (1 shl 24) / depthFraction.coerceIn(0.05f, 1f))
        if (st !== meshInstance || st.lampVersion != meshLampVersion) {
            gl.uniform4fv(uLamps, StationInstance.LAMP_SLOTS, st.lamps, 0)
            meshLampVersion = st.lampVersion
        }
        if (st !== meshInstance || st.groupVersion != meshGroupVersion || anglesChanged(st, meshAngles)) {
            uploadGroups(uGroups, uGroupMode, st)
            meshGroupVersion = st.groupVersion
            System.arraycopy(st.angles, 0, meshAngles, 0, st.angles.size)
        }
        meshInstance = st
        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_CUBE_MAP, env)
        gl.uniform1i(mesh.u("u_env"), 0)
        lights(gpu.model, st, pose)
    }

    private fun anglesChanged(st: StationInstance, cache: FloatArray): Boolean {
        val a = st.angles
        for (i in a.indices) if (a[i] != cache[i]) return true
        return false
    }

    private fun uploadGroups(uRows: Int, uMode: Int, st: StationInstance) {
        val g = st.model.groups.size
        for (i in 0 until g) st.groupRows(i, groupRows, i * 12)
        gl.uniform4fv(uRows, g * 3, groupRows, 0)
        gl.uniform4fv(uMode, g, st.groupModes, 0)
    }

    /**
     * Picks the Roblox lights whose reach comes closest to the camera (at most [maxLights]) and
     * uploads them camera-relative.
     */
    private fun lights(model: StationModel, st: StationInstance, pose: Mat4) {
        val ls = model.lights
        val pm = pose.m
        // camera in station space: pose = T * (k R); camera at the origin -> -(kR)^T t / k^2
        val k2 = pm[0] * pm[0] + pm[1] * pm[1] + pm[2] * pm[2]
        val tx = pm[12]
        val ty = pm[13]
        val tz = pm[14]
        camStation[0] = -(pm[0] * tx + pm[1] * ty + pm[2] * tz) / k2
        camStation[1] = -(pm[4] * tx + pm[5] * ty + pm[6] * tz) / k2
        camStation[2] = -(pm[8] * tx + pm[9] * ty + pm[10] * tz) / k2
        var n = 0
        val cap = minOf(ls.size, lightOrder.size)
        for (i in 0 until cap) {
            val l = ls[i]
            val dx = l.x - camStation[0]
            val dy = l.y - camStation[1]
            val dz = l.z - camStation[2]
            val key = sqrt(dx * dx + dy * dy + dz * dz) - l.range
            if (key > LIGHT_CULL) continue
            // insertion into the sorted list
            var j = n
            while (j > 0 && lightKey[j - 1] > key) {
                lightKey[j] = lightKey[j - 1]
                lightOrder[j] = lightOrder[j - 1]
                j--
            }
            lightKey[j] = key
            lightOrder[j] = i
            n++
        }
        val count = minOf(n, maxLights)
        val scale = sqrt(k2)
        for (j in 0 until count) {
            val l = ls[lightOrder[j]]
            // group transform, then the pose
            val g = l.group
            var x = l.x
            var y = l.y
            var z = l.z
            var dx = l.dirX
            var dy = l.dirY
            var dz = l.dirZ
            if (g != 0) {
                st.groupRows(g, groupRows, 0)
                val r = groupRows
                val nx = r[0] * x + r[1] * y + r[2] * z + r[3]
                val ny = r[4] * x + r[5] * y + r[6] * z + r[7]
                val nz = r[8] * x + r[9] * y + r[10] * z + r[11]
                x = nx; y = ny; z = nz
                val ex = r[0] * dx + r[1] * dy + r[2] * dz
                val ey = r[4] * dx + r[5] * dy + r[6] * dz
                val ez = r[8] * dx + r[9] * dy + r[10] * dz
                dx = ex; dy = ey; dz = ez
            }
            val o = j * 4
            lightPos[o] = pm[0] * x + pm[4] * y + pm[8] * z + pm[12]
            lightPos[o + 1] = pm[1] * x + pm[5] * y + pm[9] * z + pm[13]
            lightPos[o + 2] = pm[2] * x + pm[6] * y + pm[10] * z + pm[14]
            lightPos[o + 3] = l.range * scale
            val il = 1f / scale
            lightDir[o] = (pm[0] * dx + pm[4] * dy + pm[8] * dz) * il
            lightDir[o + 1] = (pm[1] * dx + pm[5] * dy + pm[9] * dz) * il
            lightDir[o + 2] = (pm[2] * dx + pm[6] * dy + pm[10] * dz) * il
            lightDir[o + 3] = 0f
            val b = l.brightness * LIGHT_GAIN
            lightCol[o] = srgbToLinear(l.r) * b
            lightCol[o + 1] = srgbToLinear(l.g) * b
            lightCol[o + 2] = srgbToLinear(l.b) * b
            lightCol[o + 3] = if (l.type == StationModel.LIGHT_POINT) -2f else cos(Math.toRadians(l.angle * 0.5).toFloat())
        }
        gl.uniform1i(uLightCount, count)
        if (count > 0) {
            gl.uniform4fv(uLightPos, count, lightPos, 0)
            gl.uniform4fv(uLightCol, count, lightCol, 0)
            gl.uniform4fv(uLightDir, count, lightDir, 0)
        }
    }

    private fun drawSigns(signs: Signs, st: StationInstance, pose: Mat4, viewProj: Mat4, depthFraction: Float) {
        text.use()
        gl.uniformMatrix4fv(text.u("u_viewProj"), viewProj.m, 0)
        gl.uniformMatrix4fv(text.u("u_model"), pose.m, 0)
        gl.uniform1f(text.u("u_decal"), DECAL_QUANTA * 2f / (1 shl 24) / depthFraction.coerceIn(0.05f, 1f))
        if (st !== textInstance || st.groupVersion != textGroupVersion || anglesChanged(st, textAngles)) {
            uploadGroups(text.u("u_groups"), text.u("u_groupMode"), st)
            textGroupVersion = st.groupVersion
            System.arraycopy(st.angles, 0, textAngles, 0, st.angles.size)
            textInstance = st
        }
        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_2D, fontTex)
        gl.uniform1i(text.u("u_font"), 0)
        gl.bindVertexArray(signs.vao)
        gl.drawElements(GL.TRIANGLES, signs.glyphs * 6, GL.UNSIGNED_SHORT, 0)
        drawCalls++
        triangles += signs.glyphs * 2
    }

    private fun drawBeams(model: StationModel, st: StationInstance, pose: Mat4, viewProj: Mat4) {
        val f = frame ?: return
        beam.use()
        gl.uniformMatrix4fv(beam.u("u_viewProj"), viewProj.m, 0)
        gl.uniform1f(beam.u("u_exposure"), f.exposure)
        gl.blendFunc(GL.ONE, GL.ONE)
        gl.disable(GL.CULL_FACE)
        val pm = pose.m
        for (b in model.beams) {
            st.groupRows(b.group, groupRows, 0)
            for (e in 0..1) {
                val p = if (e == 0) b.p0 else b.p1
                val r = groupRows
                val x = r[0] * p[0] + r[1] * p[1] + r[2] * p[2] + r[3]
                val y = r[4] * p[0] + r[5] * p[1] + r[6] * p[2] + r[7]
                val z = r[8] * p[0] + r[9] * p[1] + r[10] * p[2] + r[11]
                tmp[0] = pm[0] * x + pm[4] * y + pm[8] * z + pm[12]
                tmp[1] = pm[1] * x + pm[5] * y + pm[9] * z + pm[13]
                tmp[2] = pm[2] * x + pm[6] * y + pm[10] * z + pm[14]
                gl.uniform3f(beam.u(if (e == 0) "u_p0" else "u_p1"), tmp[0], tmp[1], tmp[2])
            }
            val k = sqrt(pm[0] * pm[0] + pm[1] * pm[1] + pm[2] * pm[2])
            gl.uniform2f(beam.u("u_width"), b.width0 * k, b.width1 * k)
            val t = b.transparency
            gl.uniform3f(beam.u("u_alpha"), 1f - t[0], 1f - t[1], 1f - t[2])
            gl.uniform3f(beam.u("u_color"), srgbToLinear(b.r / 255f) * BEAM_GAIN, srgbToLinear(b.g / 255f) * BEAM_GAIN, srgbToLinear(b.b / 255f) * BEAM_GAIN)
            beamMesh.draw(GL.TRIANGLE_STRIP)
            drawCalls++
        }
        gl.enable(GL.CULL_FACE)
        gl.blendFunc(GL.ONE, GL.ONE_MINUS_SRC_ALPHA)
    }

    /**
     * The far marker (beyond Roblox's LodDistance only the beacon is left): a soft dot of about
     * [sizePx] pixels at the camera-relative point ([x], [y], [z]), additive, in [color] (sRGB
     * 0..1). [cam] supplies the screen axes and the pixel scale.
     */
    fun drawMarker(cam: Camera, viewProj: Mat4, x: Float, y: Float, z: Float, sizePx: Float, r: Float, g: Float, b: Float) {
        val d = sqrt(x * x + y * y + z * z)
        val worldPerPx = 2f * cam.tanHalfY * d / cam.height.coerceAtLeast(1)
        val s = sizePx * worldPerPx
        marker.use()
        gl.uniformMatrix4fv(marker.u("u_viewProj"), viewProj.m, 0)
        gl.uniform3f(marker.u("u_centre"), x, y, z)
        gl.uniform3f(marker.u("u_right"), cam.right.x * s, cam.right.y * s, cam.right.z * s)
        gl.uniform3f(marker.u("u_up"), cam.up.x * s, cam.up.y * s, cam.up.z * s)
        gl.uniform4f(marker.u("u_color"), r, g, b, 1f)
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE)
        gl.depthMask(false)
        gl.disable(GL.CULL_FACE)
        markerMesh.draw(GL.TRIANGLE_STRIP)
        drawCalls++
        gl.enable(GL.CULL_FACE)
        gl.depthMask(true)
        gl.disable(GL.BLEND)
    }

    fun dispose() {
        mesh.dispose()
        text.dispose()
        beam.dispose()
        marker.dispose()
        beamMesh.dispose()
        markerMesh.dispose()
        gl.deleteTexture(fontTex)
    }

    companion object {
        /** Detail level boundaries (m from the station root) and Roblox's LodDistance. */
        const val LOD1_DISTANCE = 3_000f
        const val LOD2_DISTANCE = 15_000f
        const val MARKER_DISTANCE = 60_000f

        /** Detail level for a camera [distance] (m) from the root: 0, 1, 2, or 3 = marker only. */
        fun lodFor(distance: Float): Int = when {
            distance < LOD1_DISTANCE -> 0
            distance < LOD2_DISTANCE -> 1
            distance < MARKER_DISTANCE -> 2
            else -> LOD_MARKER
        }

        const val LOD_MARKER = 3

        /** Depth-buffer quanta per paint layer (the paint's depth bias, like glPolygonOffset units). */
        private const val DECAL_QUANTA = 8f

        /** Station parts get a brighter ambient than our rocks (as the Roblox ships, ShipRenderer). */
        private const val STATION_AMBIENT = 2.5f

        /** Roblox light brightness -> linear radiance scale of the local lights. */
        private const val LIGHT_GAIN = 0.35f

        /** Lights whose reach ends more than this far (m) from the camera are not considered. */
        private const val LIGHT_CULL = 600f
        private const val BEAM_SEGMENTS = 10
        private const val BEAM_GAIN = 0.9f
        private const val FIELD_SHIMMER_SPEED = 2.4f

        /**
         * Shading per material id (StationModel.MATERIAL_NAMES): metallic, emission, lit share,
         * roughness. Paint (ids 10..15) takes its emission and lit share from the vertex.
         */
        val MATERIAL_PARAMS = floatArrayOf(
            0.00f, 0.0f, 1f, 0.38f, // smooth (SmoothPlastic)
            0.00f, 0.0f, 1f, 0.85f, // rough (Plastic, Concrete, Slate, Rock, Basalt, Pebble, Sand ...)
            0.25f, 0.0f, 1f, 0.48f, // metal
            0.40f, 0.0f, 1f, 0.55f, // plate (DiamondPlate, CorrodedMetal)
            0.60f, 0.0f, 1f, 0.30f, // foil
            0.00f, 0.0f, 1f, 0.80f, // organic (Wood, WoodPlanks, Grass, LeafyGrass, Fabric)
            0.00f, 0.0f, 1f, 0.15f, // ice (Ice, Glacier)
            0.30f, 0.0f, 1f, 0.06f, // glass
            0.00f, 1.5f, 0f, 1.00f, // neon: unlit glow
            0.00f, 1.0f, 0f, 1.00f, // forcefield
            0f, 0f, 1f, 0.6f, 0f, 0f, 1f, 0.6f, 0f, 0f, 1f, 0.6f, // paint 1..3
            0f, 0f, 1f, 0.6f, 0f, 0f, 1f, 0.6f, 0f, 0f, 1f, 0.6f, // paint 4..6
        )

        internal fun srgbToLinear(c: Float): Float = Math.pow(c.toDouble(), 2.2).toFloat()
    }
}

/**
 * Lays out a station's sign labels as SDF glyph quads in station space (Roblox TextScaled: the
 * largest size up to 100 px that fits the label, wrapping words when TextWrapped). Load time only.
 */
internal object SignLayout {
    /** Vertex: f32 x, y, z; u16 u, v; u8 rgba; s8 normal + 0; u8 group, layer, light influence, emission * 100. */
    const val VERTEX_BYTES = 28
    private const val MAX_TEXT_SIZE = 100f

    fun build(model: StationModel, name: String, font: FontData): ByteBuffer {
        var glyphs = 0
        for (l in model.labels) glyphs += textOf(l, name).length
        val buf = ByteBuffer.allocateDirect(maxOf(glyphs, 1) * 4 * VERTEX_BYTES).order(ByteOrder.nativeOrder())
        val lines = IntArray(64)
        for (l in model.labels) layout(l, textOf(l, name), font, buf, lines)
        buf.flip()
        return buf
    }

    private fun textOf(l: StationModel.Label, name: String): String = when {
        l.isStationName -> name
        l.isPadNumber -> "%02d".format(l.pad)
        else -> l.text
    }

    private fun advance(font: FontData, face: Int, c: Char, size: Float): Float {
        val g = font.index(face, c.code)
        return if (g >= 0) font.advance(g) * size / font.baseSize else size * 0.3f
    }

    /** Greedy word wrap at [maxW] px for [size]; line starts in [out] (end marker after the last). Returns the line count. */
    private fun wrap(s: String, font: FontData, face: Int, size: Float, maxW: Float, wrapped: Boolean, out: IntArray): Int {
        var n = 0
        out[n++] = 0
        if (!wrapped) {
            out[n] = s.length
            return 1
        }
        var lineW = 0f
        var i = 0
        while (i < s.length) {
            // next word [i, j) plus its trailing spaces
            var j = i
            while (j < s.length && s[j] != ' ') j++
            var wordW = 0f
            for (k in i until j) wordW += advance(font, face, s[k], size)
            if (lineW > 0f && lineW + wordW > maxW && n < out.size - 1) {
                out[n++] = i
                lineW = 0f
            }
            lineW += wordW
            while (j < s.length && s[j] == ' ') {
                lineW += advance(font, face, s[j], size)
                j++
            }
            i = j
        }
        out[n] = s.length
        return n
    }

    private fun lineWidth(s: String, a: Int, b: Int, font: FontData, face: Int, size: Float): Float {
        var e = b
        while (e > a && s[e - 1] == ' ') e--
        var w = 0f
        for (k in a until e) w += advance(font, face, s[k], size)
        return w
    }

    private fun layout(l: StationModel.Label, s: String, font: FontData, buf: ByteBuffer, lines: IntArray) {
        if (s.isEmpty() || l.a == 0) return
        val face = l.font
        val wm = sqrt(l.u[0] * l.u[0] + l.u[1] * l.u[1] + l.u[2] * l.u[2])
        val hm = sqrt(l.v[0] * l.v[0] + l.v[1] * l.v[1] + l.v[2] * l.v[2])
        val ppm = l.pxPerMetre
        val wp = wm * ppm
        val hp = hm * ppm
        // TextScaled: largest whole size that fits; otherwise TextSize
        var size = l.textSize
        var n: Int
        if (l.scaled) {
            size = minOf(MAX_TEXT_SIZE, kotlin.math.floor(hp))
            while (true) {
                n = wrap(s, font, face, size, wp, l.wrapped, lines)
                var widest = 0f
                for (i in 0 until n) widest = maxOf(widest, lineWidth(s, lines[i], lines[i + 1], font, face, size))
                if ((widest <= wp && n * size <= hp) || size <= 1f) break
                size -= 1f
            }
        }
        n = wrap(s, font, face, size, wp, l.wrapped, lines)
        val scale = size / font.baseSize
        val cap = font.capHeight(face) * scale
        val blockH = n * size
        val top = when (l.yAlign) {
            0 -> 0f
            2 -> hp - blockH
            else -> (hp - blockH) * 0.5f
        }
        val iw = 1f / font.atlasW
        val ih = 1f / font.atlasH
        val li = (l.lightInfluence.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val em = ((1f - l.lightInfluence).coerceIn(0f, 1f) * l.brightness * 100f + 0.5f).toInt().coerceIn(0, 255)
        for (i in 0 until n) {
            val a = lines[i]
            val b = lines[i + 1]
            val w = lineWidth(s, a, b, font, face, size)
            var pen = when (l.xAlign) {
                0 -> 0f
                1 -> wp - w
                else -> (wp - w) * 0.5f
            }
            val baseline = top + i * size + size * 0.5f + cap * 0.5f
            for (k in a until b) {
                val c = s[k]
                val g = font.index(face, c.code)
                if (g < 0) {
                    pen += size * 0.3f
                    continue
                }
                val gw = font.w(g)
                if (gw > 0f) {
                    val x0 = pen + font.xOff(g) * scale
                    val y0 = baseline + font.yOff(g) * scale
                    val x1 = x0 + gw * scale
                    val y1 = y0 + font.h(g) * scale
                    val u0 = font.x(g) * iw
                    val v0 = font.y(g) * ih
                    val u1 = (font.x(g) + gw) * iw
                    val v1 = (font.y(g) + font.h(g)) * ih
                    vertex(buf, l, x0 / wp, y0 / hp, u0, v0, li, em)
                    vertex(buf, l, x1 / wp, y0 / hp, u1, v0, li, em)
                    vertex(buf, l, x0 / wp, y1 / hp, u0, v1, li, em)
                    vertex(buf, l, x1 / wp, y1 / hp, u1, v1, li, em)
                }
                pen += font.advance(g) * scale
            }
        }
    }

    private fun vertex(buf: ByteBuffer, l: StationModel.Label, s: Float, t: Float, u: Float, v: Float, li: Int, em: Int) {
        val o = l.origin
        buf.putFloat(o[0] + l.u[0] * s + l.v[0] * t)
        buf.putFloat(o[1] + l.u[1] * s + l.v[1] * t)
        buf.putFloat(o[2] + l.u[2] * s + l.v[2] * t)
        buf.putShort((u.coerceIn(0f, 1f) * 65535f + 0.5f).toInt().toShort())
        buf.putShort((v.coerceIn(0f, 1f) * 65535f + 0.5f).toInt().toShort())
        buf.put(l.r.toByte()); buf.put(l.g.toByte()); buf.put(l.b.toByte()); buf.put(l.a.toByte())
        buf.put((l.normal[0] * 127f).toInt().toByte()); buf.put((l.normal[1] * 127f).toInt().toByte())
        buf.put((l.normal[2] * 127f).toInt().toByte()); buf.put(0)
        buf.put(l.group.toByte()); buf.put(l.layer.toByte()); buf.put(li.toByte()); buf.put(em.toByte())
    }
}
