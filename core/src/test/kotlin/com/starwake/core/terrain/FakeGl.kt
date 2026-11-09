package com.starwake.core.terrain

import com.starwake.core.gfx.GL
import com.starwake.core.gfx.Gl
import java.nio.ByteBuffer

/** A [Gl] that does nothing (tests of renderer logic and allocation without a GPU). */
class FakeGl : Gl {
    override val screenFramebuffer = 0
    private var next = 1
    var drawCalls = 0
    var uploads = 0L

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) {}
    override fun clear(mask: Int) {}
    override fun viewport(x: Int, y: Int, w: Int, h: Int) {}
    override fun enable(cap: Int) {}
    override fun disable(cap: Int) {}
    override fun blendFunc(src: Int, dst: Int) {}
    override fun depthMask(flag: Boolean) {}
    override fun depthFunc(func: Int) {}
    override fun cullFace(mode: Int) {}
    override fun depthRange(near: Float, far: Float) {}
    override fun createShader(type: Int) = next++
    override fun shaderSource(shader: Int, src: String) {}
    override fun compileShader(shader: Int) {}
    override fun getShaderi(shader: Int, pname: Int) = 1
    override fun getShaderInfoLog(shader: Int) = ""
    override fun deleteShader(shader: Int) {}
    override fun createProgram() = next++
    override fun attachShader(program: Int, shader: Int) {}
    override fun linkProgram(program: Int) {}
    override fun getProgrami(program: Int, pname: Int) = 1
    override fun getProgramInfoLog(program: Int) = ""
    override fun useProgram(program: Int) {}
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
    override fun bufferData(target: Int, sizeBytes: Int, data: ByteBuffer?, usage: Int) { uploads += sizeBytes }
    override fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: ByteBuffer) { uploads += sizeBytes }
    override fun genVertexArray() = next++
    override fun deleteVertexArray(id: Int) {}
    override fun bindVertexArray(id: Int) {}
    override fun enableVertexAttribArray(index: Int) {}
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) {}
    override fun vertexAttribDivisor(index: Int, divisor: Int) {}
    override fun drawArrays(mode: Int, first: Int, count: Int) { drawCalls++ }
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) { drawCalls++ }
    override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) { drawCalls++ }
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
    override fun getString(name: Int): String? = "fake"
}
