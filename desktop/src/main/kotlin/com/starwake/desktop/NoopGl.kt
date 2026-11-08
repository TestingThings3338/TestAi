package com.starwake.desktop

import com.starwake.core.gfx.GL
import com.starwake.core.gfx.Gl
import java.nio.ByteBuffer

/**
 * [Gl] that does no GPU work at all: shaders always "compile", objects get fresh ids. Running the
 * engine on it isolates the engine's own CPU cost (and its allocations) from the driver, which
 * on llvmpipe is far slower than any phone GPU. Also counts what a frame would submit.
 */
class NoopGl : Gl {
    override val screenFramebuffer: Int = 0
    private var nextId = 1
    private var nextLoc = 0

    var calls = 0L
    var drawCalls = 0L
    var uploadBytes = 0L
    var programSwitches = 0L

    fun resetCounters() {
        calls = 0; drawCalls = 0; uploadBytes = 0; programSwitches = 0
    }

    private fun id(): Int { calls++; return nextId++ }
    private fun c() { calls++ }

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = c()
    override fun clear(mask: Int) = c()
    override fun viewport(x: Int, y: Int, w: Int, h: Int) = c()
    override fun enable(cap: Int) = c()
    override fun disable(cap: Int) = c()
    override fun blendFunc(src: Int, dst: Int) = c()
    override fun depthMask(flag: Boolean) = c()
    override fun depthFunc(func: Int) = c()
    override fun cullFace(mode: Int) = c()

    override fun createShader(type: Int): Int = id()
    override fun shaderSource(shader: Int, src: String) = c()
    override fun compileShader(shader: Int) = c()
    override fun getShaderi(shader: Int, pname: Int): Int { c(); return 1 }
    override fun getShaderInfoLog(shader: Int): String = ""
    override fun deleteShader(shader: Int) = c()
    override fun createProgram(): Int = id()
    override fun attachShader(program: Int, shader: Int) = c()
    override fun linkProgram(program: Int) = c()
    override fun getProgrami(program: Int, pname: Int): Int { c(); return 1 }
    override fun getProgramInfoLog(program: Int): String = ""
    override fun useProgram(program: Int) { c(); programSwitches++ }
    override fun deleteProgram(program: Int) = c()
    override fun getUniformLocation(program: Int, name: String): Int { c(); return nextLoc++ }

    override fun uniform1i(loc: Int, v: Int) = c()
    override fun uniform1f(loc: Int, v: Float) = c()
    override fun uniform2f(loc: Int, x: Float, y: Float) = c()
    override fun uniform3f(loc: Int, x: Float, y: Float, z: Float) = c()
    override fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float) = c()
    override fun uniform4fv(loc: Int, count: Int, v: FloatArray, offset: Int) = c()
    override fun uniformMatrix4fv(loc: Int, v: FloatArray, offset: Int) = c()

    override fun genBuffer(): Int = id()
    override fun deleteBuffer(id: Int) = c()
    override fun bindBuffer(target: Int, id: Int) = c()
    override fun bufferData(target: Int, sizeBytes: Int, data: ByteBuffer?, usage: Int) { c(); if (data != null) uploadBytes += sizeBytes }
    override fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: ByteBuffer) { c(); uploadBytes += sizeBytes }

    override fun genVertexArray(): Int = id()
    override fun deleteVertexArray(id: Int) = c()
    override fun bindVertexArray(id: Int) = c()
    override fun enableVertexAttribArray(index: Int) = c()
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) = c()
    override fun vertexAttribDivisor(index: Int, divisor: Int) = c()

    override fun drawArrays(mode: Int, first: Int, count: Int) { c(); drawCalls++ }
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) { c(); drawCalls++ }
    override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) { c(); drawCalls++ }

    override fun genTexture(): Int = id()
    override fun deleteTexture(id: Int) = c()
    override fun bindTexture(target: Int, id: Int) = c()
    override fun activeTexture(unit: Int) = c()
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, w: Int, h: Int, format: Int, type: Int, data: ByteBuffer?) = c()
    override fun texParameteri(target: Int, pname: Int, param: Int) = c()
    override fun generateMipmap(target: Int) = c()
    override fun pixelStorei(pname: Int, param: Int) = c()

    override fun genFramebuffer(): Int = id()
    override fun deleteFramebuffer(id: Int) = c()
    override fun bindFramebuffer(target: Int, id: Int) = c()
    override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int) = c()
    override fun checkFramebufferStatus(target: Int): Int { c(); return GL.FRAMEBUFFER_COMPLETE }
    override fun invalidateDepth() = c()

    override fun getError(): Int = GL.NO_ERROR
    override fun getString(name: Int): String? = when (name) {
        GL.VENDOR -> "Starwake"
        GL.RENDERER -> "no-op"
        GL.VERSION -> "OpenGL ES 3.0 no-op"
        else -> ""
    }
}
