package com.starwake.core.gfx

import com.starwake.core.content.ShipModel
import com.starwake.core.game.GearRig
import com.starwake.core.math.Mat4

/**
 * Draws a [ShipModel]: one VAO per chunk (the hull, the shared cockpit), one draw call per chunk and
 * pass. Opaque parts (with Neon and the engine glow discs, unlit in the shader) go in the opaque
 * pass with the rest of the scene; glass is blended afterwards without depth writes. The view picks
 * the index range: the exterior view leaves out the pilot's body (CockpitOnly), the cockpit view the
 * canopy cage and everything hidden from the seat (HideInside, HideSeated).
 *
 * Landing-gear bones come from a [GearRig] and are uploaded only when they change. The cockpit chunk
 * is lit as a cabin: less sun (no shadows here), plus a warm interior light.
 */
class ShipRenderer(private val gl: Gl) {
    private val prog = ShaderProgram(gl, "ship", Shaders.shipVs, Shaders.shipFs)
    private val uViewProj = prog.u("u_viewProj")
    private val uModel = prog.u("u_model")
    private val uQuant = prog.u("u_quant")
    private val uBones = prog.u("u_bones")
    private val uMat = prog.u("u_mat")
    private val uSunDir = prog.u("u_sunDir")
    private val uSunColor = prog.u("u_sunColor")
    private val uFillDir = prog.u("u_fillDir")
    private val uFillColor = prog.u("u_fillColor")
    private val uAmbient = prog.u("u_ambient")
    private val uEngine = prog.u("u_engine")
    private val uExposure = prog.u("u_exposure")
    private val uEnvLod = prog.u("u_envLod")
    private val uEnv = prog.u("u_env")

    private class ChunkMesh(val chunk: ShipModel.Chunk, val vao: Int, val vbo: Int, val ibo: Int, val indexType: Int)

    var model: ShipModel? = null
        private set
    private var meshes: Array<ChunkMesh> = emptyArray()
    private var bonesVersion = -1
    private var bonesRig: GearRig? = null
    private var sunScale = -1f

    // per-frame lighting, kept for the cabin switch
    private var sunR = 0f
    private var sunG = 0f
    private var sunB = 0f

    /** Draw calls issued since the last [resetStats]. */
    var drawCalls = 0
        private set

    /** Triangles submitted since the last [resetStats]. */
    var triangles = 0
        private set

    fun resetStats() {
        drawCalls = 0
        triangles = 0
    }

    init {
        prog.use()
        gl.uniform4fv(uMat, 16, ShipModel.MATERIAL_PARAMS, 0)
    }

    /** Uploads [m] (dropping the previous ship's buffers). Not per frame. */
    fun setModel(m: ShipModel?) {
        if (m === model) return
        disposeMeshes()
        model = m
        bonesVersion = -1
        bonesRig = null
        if (m == null) return
        meshes = Array(m.chunks.size) { upload(m.chunks[it]) }
    }

    private fun upload(c: ShipModel.Chunk): ChunkMesh {
        val vao = gl.genVertexArray()
        val vbo = gl.genBuffer()
        val ibo = gl.genBuffer()
        gl.bindVertexArray(vao)
        gl.bindBuffer(GL.ARRAY_BUFFER, vbo)
        gl.bufferData(GL.ARRAY_BUFFER, c.vertices.size, Buffers.of(c.vertices), GL.STATIC_DRAW)
        val stride = ShipModel.VERTEX_BYTES
        gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(0, 4, GL.UNSIGNED_SHORT, false, stride, 0)
        gl.enableVertexAttribArray(1)
        gl.vertexAttribPointer(1, 4, GL.BYTE, true, stride, 8)
        gl.enableVertexAttribArray(2)
        gl.vertexAttribPointer(2, 4, GL.UNSIGNED_BYTE, true, stride, 12)
        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, ibo)
        gl.bufferData(GL.ELEMENT_ARRAY_BUFFER, c.indices.size, Buffers.of(c.indices), GL.STATIC_DRAW)
        gl.bindVertexArray(0)
        return ChunkMesh(c, vao, vbo, ibo, if (c.indexSize == 2) GL.UNSIGNED_SHORT else GL.UNSIGNED_INT)
    }

    /**
     * Binds the program with [viewProj] and the frame's lighting; call before [draw] in each pass.
     * [engine] scales the glow of the engine discs (0.5 idle .. ~1.7 boost).
     */
    fun begin(viewProj: Mat4, f: SceneFrame, env: Int, envLod: Float, engine: Float) {
        prog.use()
        gl.uniformMatrix4fv(uViewProj, viewProj.m, 0)
        val s = f.sunDir
        gl.uniform3f(uSunDir, s.x, s.y, s.z)
        val sc = Environment.sunColor
        val v = f.sunVisibility
        sunR = sc[0] * v; sunG = sc[1] * v; sunB = sc[2] * v
        sunScale = -1f
        val p = f.fillDir
        gl.uniform3f(uFillDir, p.x, p.y, p.z)
        val fc = f.fillColor
        gl.uniform3f(uFillColor, fc[0], fc[1], fc[2])
        gl.uniform1f(uEngine, engine)
        gl.uniform1f(uExposure, f.exposure)
        gl.uniform1f(uEnvLod, envLod)
        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_CUBE_MAP, env)
        gl.uniform1i(uEnv, 0)
    }

    /**
     * Draws [pass] (ShipModel.PASS_*) of the ship for [view] (ShipModel.VIEW_*) with the
     * camera-relative pose [pose]. Bones are taken from [rig] (uploaded when they changed).
     */
    fun draw(pass: Int, view: Int, pose: Mat4, rig: GearRig) {
        if (meshes.isEmpty()) return
        gl.uniformMatrix4fv(uModel, pose.m, 0)
        if (rig !== bonesRig || rig.version != bonesVersion) {
            bonesRig = rig
            bonesVersion = rig.version
            gl.uniform4fv(uBones, rig.boneCount * 3, rig.bones, 0)
        }
        for (i in meshes.indices) {
            val mesh = meshes[i]
            val c = mesh.chunk
            val count = c.count(pass, view)
            if (count == 0) continue
            // chunk 1 is the cockpit: a cabin under the canopy frame
            lighting(if (i == COCKPIT_CHUNK) CABIN_SUN else 1f)
            gl.uniform4f(uQuant, c.originX, c.originY, c.originZ, c.scale)
            gl.bindVertexArray(mesh.vao)
            gl.drawElements(GL.TRIANGLES, count, mesh.indexType, c.first(pass, view) * c.indexSize)
            drawCalls++
            triangles += count / 3
        }
    }

    private fun lighting(k: Float) {
        if (k == sunScale) return
        sunScale = k
        gl.uniform3f(uSunColor, sunR * k, sunG * k, sunB * k)
        val a = Environment.ambient
        if (k < 1f) {
            gl.uniform3f(uAmbient, a[0] * SHIP_AMBIENT + CABIN_R, a[1] * SHIP_AMBIENT + CABIN_G, a[2] * SHIP_AMBIENT + CABIN_B)
        } else {
            gl.uniform3f(uAmbient, a[0] * SHIP_AMBIENT, a[1] * SHIP_AMBIENT, a[2] * SHIP_AMBIENT)
        }
    }

    private fun disposeMeshes() {
        for (m in meshes) {
            gl.deleteVertexArray(m.vao)
            gl.deleteBuffer(m.vbo)
            gl.deleteBuffer(m.ibo)
        }
        meshes = emptyArray()
    }

    fun dispose() {
        disposeMeshes()
        prog.dispose()
    }

    companion object {
        private const val COCKPIT_CHUNK = 1

        /** Share of the sun the cockpit gets (the canopy frame and hull shade it in Roblox). */
        private const val CABIN_SUN = 0.55f

        /** Warm cabin light added to the cockpit's ambient (Roblox: amber interior lights). */
        private const val CABIN_R = 0.055f
        private const val CABIN_G = 0.042f
        private const val CABIN_B = 0.030f

        /** Ambient multiplier for ship parts (Roblox lights the hulls with a brighter ambient than our rocks). */
        private const val SHIP_AMBIENT = 2.5f
    }
}
