package com.starwake.desktop

import com.starwake.core.gfx.Gl
import org.lwjgl.opengles.GLES20
import org.lwjgl.opengles.GLES30
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

class GlCallException(msg: String) : RuntimeException(msg)

/**
 * [Gl] on LWJGL's GLES 3.0 bindings (Mesa through glvnd's libGLESv2). Per-frame entry points are
 * allocation free: array uniforms go through a reusable direct buffer and buffer uploads pass the
 * direct buffer's address straight to the driver.
 *
 * With [debug] every call is followed by glGetError and an error throws, naming the function.
 */
class HeadlessGl(override val screenFramebuffer: Int, private val debug: Boolean) : Gl {
    private var scratch: FloatBuffer = ByteBuffer.allocateDirect(64 * 16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val invalidateDepthList: IntBuffer =
        ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer().put(0, GLES20.GL_DEPTH_ATTACHMENT)

    /** Draw calls issued since the last [resetCounters]. */
    var drawCalls = 0
        private set

    fun resetCounters() {
        drawCalls = 0
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun chk(fn: String) {
        if (debug) {
            val e = GLES20.glGetError()
            if (e != GLES20.GL_NO_ERROR) throw GlCallException("${glErrorName(e)} after $fn")
        }
    }

    private fun chk(fn: String, a: Int) {
        if (debug) {
            val e = GLES20.glGetError()
            if (e != GLES20.GL_NO_ERROR) throw GlCallException("${glErrorName(e)} after $fn(0x${Integer.toHexString(a)})")
        }
    }

    private fun floats(src: FloatArray, offset: Int, count: Int): FloatBuffer {
        if (scratch.capacity() < count) {
            scratch = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        val b = scratch
        b.clear()
        b.put(src, offset, count)
        b.flip()
        return b
    }

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) { GLES20.glClearColor(r, g, b, a); chk("glClearColor") }
    override fun clear(mask: Int) { GLES20.glClear(mask); chk("glClear", mask) }
    override fun viewport(x: Int, y: Int, w: Int, h: Int) { GLES20.glViewport(x, y, w, h); chk("glViewport") }
    override fun enable(cap: Int) { GLES20.glEnable(cap); chk("glEnable", cap) }
    override fun disable(cap: Int) { GLES20.glDisable(cap); chk("glDisable", cap) }
    override fun blendFunc(src: Int, dst: Int) { GLES20.glBlendFunc(src, dst); chk("glBlendFunc") }
    override fun depthMask(flag: Boolean) { GLES20.glDepthMask(flag); chk("glDepthMask") }
    override fun depthFunc(func: Int) { GLES20.glDepthFunc(func); chk("glDepthFunc", func) }
    override fun cullFace(mode: Int) { GLES20.glCullFace(mode); chk("glCullFace", mode) }

    override fun createShader(type: Int): Int { val s = GLES20.glCreateShader(type); chk("glCreateShader", type); return s }
    override fun shaderSource(shader: Int, src: String) { GLES20.glShaderSource(shader, src); chk("glShaderSource") }
    override fun compileShader(shader: Int) { GLES20.glCompileShader(shader); chk("glCompileShader") }
    override fun getShaderi(shader: Int, pname: Int): Int { val v = GLES20.glGetShaderi(shader, pname); chk("glGetShaderiv", pname); return v }
    override fun getShaderInfoLog(shader: Int): String { val s = GLES20.glGetShaderInfoLog(shader); chk("glGetShaderInfoLog"); return s }
    override fun deleteShader(shader: Int) { GLES20.glDeleteShader(shader); chk("glDeleteShader") }
    override fun createProgram(): Int { val p = GLES20.glCreateProgram(); chk("glCreateProgram"); return p }
    override fun attachShader(program: Int, shader: Int) { GLES20.glAttachShader(program, shader); chk("glAttachShader") }
    override fun linkProgram(program: Int) { GLES20.glLinkProgram(program); chk("glLinkProgram") }
    override fun getProgrami(program: Int, pname: Int): Int { val v = GLES20.glGetProgrami(program, pname); chk("glGetProgramiv", pname); return v }
    override fun getProgramInfoLog(program: Int): String { val s = GLES20.glGetProgramInfoLog(program); chk("glGetProgramInfoLog"); return s }
    override fun useProgram(program: Int) { GLES20.glUseProgram(program); chk("glUseProgram") }
    override fun deleteProgram(program: Int) { GLES20.glDeleteProgram(program); chk("glDeleteProgram") }
    override fun getUniformLocation(program: Int, name: String): Int {
        val l = GLES20.glGetUniformLocation(program, name)
        if (debug) {
            val e = GLES20.glGetError()
            if (e != GLES20.GL_NO_ERROR) throw GlCallException("${glErrorName(e)} after glGetUniformLocation($name)")
        }
        return l
    }

    override fun uniform1i(loc: Int, v: Int) { GLES20.glUniform1i(loc, v); chk("glUniform1i") }
    override fun uniform1f(loc: Int, v: Float) { GLES20.glUniform1f(loc, v); chk("glUniform1f") }
    override fun uniform2f(loc: Int, x: Float, y: Float) { GLES20.glUniform2f(loc, x, y); chk("glUniform2f") }
    override fun uniform3f(loc: Int, x: Float, y: Float, z: Float) { GLES20.glUniform3f(loc, x, y, z); chk("glUniform3f") }
    override fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float) { GLES20.glUniform4f(loc, x, y, z, w); chk("glUniform4f") }
    override fun uniform4fv(loc: Int, count: Int, v: FloatArray, offset: Int) {
        GLES20.glUniform4fv(loc, floats(v, offset, count * 4)); chk("glUniform4fv")
    }
    override fun uniformMatrix4fv(loc: Int, v: FloatArray, offset: Int) {
        GLES20.glUniformMatrix4fv(loc, false, floats(v, offset, 16)); chk("glUniformMatrix4fv")
    }

    override fun genBuffer(): Int { val b = GLES20.glGenBuffers(); chk("glGenBuffers"); return b }
    override fun deleteBuffer(id: Int) { GLES20.glDeleteBuffers(id); chk("glDeleteBuffers") }
    override fun bindBuffer(target: Int, id: Int) { GLES20.glBindBuffer(target, id); chk("glBindBuffer", target) }
    override fun bufferData(target: Int, sizeBytes: Int, data: ByteBuffer?, usage: Int) {
        if (data == null) {
            GLES20.glBufferData(target, sizeBytes.toLong(), usage)
        } else {
            if (debug) require(data.isDirect && data.remaining() >= sizeBytes) {
                "glBufferData: buffer has ${data.remaining()} bytes remaining, $sizeBytes requested"
            }
            GLES20.nglBufferData(target, sizeBytes.toLong(), MemoryUtil.memAddress(data), usage)
        }
        chk("glBufferData", target)
    }
    override fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: ByteBuffer) {
        if (debug) require(data.isDirect && data.remaining() >= sizeBytes) {
            "glBufferSubData: buffer has ${data.remaining()} bytes remaining, $sizeBytes requested"
        }
        GLES20.nglBufferSubData(target, offsetBytes.toLong(), sizeBytes.toLong(), MemoryUtil.memAddress(data))
        chk("glBufferSubData", target)
    }

    override fun genVertexArray(): Int { val v = GLES30.glGenVertexArrays(); chk("glGenVertexArrays"); return v }
    override fun deleteVertexArray(id: Int) { GLES30.glDeleteVertexArrays(id); chk("glDeleteVertexArrays") }
    override fun bindVertexArray(id: Int) { GLES30.glBindVertexArray(id); chk("glBindVertexArray") }
    override fun enableVertexAttribArray(index: Int) { GLES20.glEnableVertexAttribArray(index); chk("glEnableVertexAttribArray") }
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) {
        GLES20.glVertexAttribPointer(index, size, type, normalized, stride, offset.toLong()); chk("glVertexAttribPointer")
    }
    override fun vertexAttribDivisor(index: Int, divisor: Int) { GLES30.glVertexAttribDivisor(index, divisor); chk("glVertexAttribDivisor") }

    override fun drawArrays(mode: Int, first: Int, count: Int) {
        drawCalls++; GLES20.glDrawArrays(mode, first, count); chk("glDrawArrays", mode)
    }
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) {
        drawCalls++; GLES20.glDrawElements(mode, count, type, offset.toLong()); chk("glDrawElements", mode)
    }
    override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) {
        drawCalls++; GLES30.glDrawArraysInstanced(mode, first, count, instances); chk("glDrawArraysInstanced", mode)
    }

    override fun genTexture(): Int { val t = GLES20.glGenTextures(); chk("glGenTextures"); return t }
    override fun deleteTexture(id: Int) { GLES20.glDeleteTextures(id); chk("glDeleteTextures") }
    override fun bindTexture(target: Int, id: Int) { GLES20.glBindTexture(target, id); chk("glBindTexture", target) }
    override fun activeTexture(unit: Int) { GLES20.glActiveTexture(unit); chk("glActiveTexture", unit) }
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, w: Int, h: Int, format: Int, type: Int, data: ByteBuffer?) {
        GLES20.nglTexImage2D(target, level, internalFormat, w, h, 0, format, type, MemoryUtil.memAddressSafe(data))
        chk("glTexImage2D", internalFormat)
    }
    override fun texParameteri(target: Int, pname: Int, param: Int) { GLES20.glTexParameteri(target, pname, param); chk("glTexParameteri", pname) }
    override fun generateMipmap(target: Int) { GLES20.glGenerateMipmap(target); chk("glGenerateMipmap", target) }
    override fun pixelStorei(pname: Int, param: Int) { GLES20.glPixelStorei(pname, param); chk("glPixelStorei", pname) }

    override fun genFramebuffer(): Int { val f = GLES20.glGenFramebuffers(); chk("glGenFramebuffers"); return f }
    override fun deleteFramebuffer(id: Int) { GLES20.glDeleteFramebuffers(id); chk("glDeleteFramebuffers") }
    override fun bindFramebuffer(target: Int, id: Int) { GLES20.glBindFramebuffer(target, id); chk("glBindFramebuffer", target) }
    override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int) {
        GLES20.glFramebufferTexture2D(target, attachment, texTarget, texture, level); chk("glFramebufferTexture2D", texTarget)
    }
    override fun checkFramebufferStatus(target: Int): Int {
        val s = GLES20.glCheckFramebufferStatus(target); chk("glCheckFramebufferStatus"); return s
    }

    /** The engine calls this while "the screen" (our FBO) is bound, so GL_DEPTH_ATTACHMENT is the right name. */
    override fun invalidateDepth() {
        GLES30.glInvalidateFramebuffer(GLES20.GL_FRAMEBUFFER, invalidateDepthList)
        chk("glInvalidateFramebuffer")
    }

    override fun getError(): Int = GLES20.glGetError()
    override fun getString(name: Int): String? { val s = GLES20.glGetString(name); chk("glGetString", name); return s }
}
