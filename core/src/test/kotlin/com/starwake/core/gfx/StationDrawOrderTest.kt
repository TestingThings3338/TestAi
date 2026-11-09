package com.starwake.core.gfx

import com.starwake.core.content.StationModel
import com.starwake.core.math.Mat4
import com.starwake.core.station.StationInstance
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [StationRenderer.drawTransparent] submits, in order, through a recording [Gl]: the translucent
 * paint before the sign text, the text before the translucent parts. None of these write depth, so
 * the order alone keeps a label on top of the semi-transparent panel it is painted on.
 */
class StationDrawOrderTest {
    private val assets: File = listOf(File("../app/src/main/assets"), File("app/src/main/assets")).first { File(it, "stations").isDirectory }

    /** A draw call: the program's vertex shader source, first index (or vertex), count. */
    private data class Draw(val vs: String, val first: Int, val count: Int)

    private class RecordingGl : Gl {
        override val screenFramebuffer = 0
        private var next = 1
        private val sources = HashMap<Int, String>()
        private val programVs = HashMap<Int, String>()
        private var program = 0
        private var depthWrite = true
        private var blend = false
        val draws = ArrayList<Draw>()
        val depthWrites = ArrayList<Boolean>()
        val blended = ArrayList<Boolean>()
        var indexSize = 2

        private fun record(first: Int, count: Int) {
            draws += Draw(programVs[program] ?: "", first, count)
            depthWrites += depthWrite
            blended += blend
        }

        override fun clearColor(r: Float, g: Float, b: Float, a: Float) {}
        override fun clear(mask: Int) {}
        override fun viewport(x: Int, y: Int, w: Int, h: Int) {}
        override fun enable(cap: Int) { if (cap == GL.BLEND) blend = true }
        override fun disable(cap: Int) { if (cap == GL.BLEND) blend = false }
        override fun blendFunc(src: Int, dst: Int) {}
        override fun depthMask(flag: Boolean) { depthWrite = flag }
        override fun depthFunc(func: Int) {}
        override fun cullFace(mode: Int) {}
        override fun depthRange(near: Float, far: Float) {}
        override fun createShader(type: Int) = next++
        override fun shaderSource(shader: Int, src: String) { sources[shader] = src }
        override fun compileShader(shader: Int) {}
        override fun getShaderi(shader: Int, pname: Int) = 1
        override fun getShaderInfoLog(shader: Int) = ""
        override fun deleteShader(shader: Int) {}
        override fun createProgram() = next++
        override fun attachShader(program: Int, shader: Int) {
            val src = sources[shader] ?: return
            if (src.contains("gl_Position")) programVs[program] = src
        }
        override fun linkProgram(program: Int) {}
        override fun getProgrami(program: Int, pname: Int) = 1
        override fun getProgramInfoLog(program: Int) = ""
        override fun useProgram(program: Int) { this.program = program }
        override fun deleteProgram(program: Int) {}
        override fun getUniformLocation(program: Int, name: String) = next++
        override fun uniform1i(loc: Int, v: Int) {}
        override fun uniform1f(loc: Int, v: Float) {}
        override fun uniform2f(loc: Int, x: Float, y: Float) {}
        override fun uniform3f(loc: Int, x: Float, y: Float, z: Float) {}
        override fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float) {}
        override fun uniform4fv(loc: Int, count: Int, v: FloatArray, offset: Int) {}
        override fun uniformMatrix4fv(loc: Int, v: FloatArray, offset: Int) {}
        override fun genBuffer() = next++
        override fun deleteBuffer(id: Int) {}
        override fun bindBuffer(target: Int, id: Int) {}
        override fun bufferData(target: Int, sizeBytes: Int, data: ByteBuffer?, usage: Int) {}
        override fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: ByteBuffer) {}
        override fun genVertexArray() = next++
        override fun deleteVertexArray(id: Int) {}
        override fun bindVertexArray(id: Int) {}
        override fun enableVertexAttribArray(index: Int) {}
        override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) {}
        override fun vertexAttribDivisor(index: Int, divisor: Int) {}
        override fun drawArrays(mode: Int, first: Int, count: Int) = record(first, count)
        override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) =
            record(offset / (if (type == GL.UNSIGNED_INT) 4 else 2), count)
        override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) = record(first, count)
        override fun genTexture() = next++
        override fun deleteTexture(id: Int) {}
        override fun bindTexture(target: Int, id: Int) {}
        override fun activeTexture(unit: Int) {}
        override fun texImage2D(target: Int, level: Int, internalFormat: Int, w: Int, h: Int, format: Int, type: Int, data: ByteBuffer?) {}
        override fun texParameteri(target: Int, pname: Int, param: Int) {}
        override fun generateMipmap(target: Int) {}
        override fun pixelStorei(pname: Int, param: Int) {}
        override fun genFramebuffer() = next++
        override fun deleteFramebuffer(id: Int) {}
        override fun bindFramebuffer(target: Int, id: Int) {}
        override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int) {}
        override fun checkFramebufferStatus(target: Int) = GL.FRAMEBUFFER_COMPLETE
        override fun invalidateDepth() {}
        override fun getError() = GL.NO_ERROR
        override fun getString(name: Int): String? = ""
    }

    @Test
    fun translucentPaintThenSignTextThenTranslucentParts() {
        val font = FontData.parse(File(assets, "font/ui.fnt").readText())
        val atlas = File(assets, "font/ui.sdf").readBytes()
        for (t in StationModel.TYPES.toList() + StationModel.SITE) {
            val gl = RecordingGl()
            val r = StationRenderer(gl, font, atlas)
            val m = StationModel.load(t) { File(assets, it).readBytes() }
            val gpu = r.upload(m)
            val signs = r.signs(m, "GALILEAN REFINERY")
            val st = StationInstance(m)
            if (m.isSite) st.setSiteStages(2, building = false)
            val pose = Mat4()
            val viewProj = Mat4()
            r.begin(SceneFrame(), 1, 0f)
            for (lod in 0..2) {
                gl.draws.clear(); gl.depthWrites.clear(); gl.blended.clear()
                r.drawTransparent(gpu, st, signs, pose, viewProj, lod)
                val mesh = m.mesh
                val expected = ArrayList<Draw>()
                fun range(pass: Int) {
                    val n = mesh.count(pass, lod)
                    if (n > 0) expected += Draw(StationShaders.meshVs, mesh.first(pass), n)
                }
                range(StationModel.PASS_PAINT)
                if (lod == 0) expected += Draw(StationShaders.textVs, 0, signs.glyphs * 6)
                range(StationModel.PASS_TRANSLUCENT)
                range(StationModel.PASS_LAMP)
                val got = gl.draws
                // the hologram and the beams come after (site, Spire)
                assertTrue(got.size >= expected.size, "$t lod $lod: ${got.size} draws")
                assertEquals(expected, got.subList(0, expected.size), "$t lod $lod: draw order")
                for (k in got.indices) {
                    assertEquals(false, gl.depthWrites[k], "$t lod $lod draw $k writes depth")
                    assertEquals(true, gl.blended[k], "$t lod $lod draw $k not blended")
                }
                if (t == "RefineryStation") assertTrue(mesh.count(StationModel.PASS_PAINT, lod) > 0 || lod > 0)
            }
            r.dispose()
        }
    }
}
