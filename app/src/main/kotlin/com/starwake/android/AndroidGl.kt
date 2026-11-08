package com.starwake.android

import android.opengl.GLES30
import com.starwake.core.gfx.Gl
import java.nio.ByteBuffer

/**
 * [Gl] on top of android.opengl.GLES30: thin 1:1 calls, GL thread only.
 * The gen/delete helpers reuse one scratch array, so nothing here allocates.
 */
internal class AndroidGl : Gl {
    private val tmp = IntArray(1)

    /** Default framebuffer depth + stencil attachments, for glInvalidateFramebuffer. */
    private val depthStencilAttachments = intArrayOf(GLES30.GL_DEPTH, GLES30.GL_STENCIL)

    override val screenFramebuffer: Int get() = 0

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES30.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GLES30.glClear(mask)
    override fun viewport(x: Int, y: Int, w: Int, h: Int) = GLES30.glViewport(x, y, w, h)
    override fun enable(cap: Int) = GLES30.glEnable(cap)
    override fun disable(cap: Int) = GLES30.glDisable(cap)
    override fun blendFunc(src: Int, dst: Int) = GLES30.glBlendFunc(src, dst)
    override fun depthMask(flag: Boolean) = GLES30.glDepthMask(flag)
    override fun depthFunc(func: Int) = GLES30.glDepthFunc(func)
    override fun cullFace(mode: Int) = GLES30.glCullFace(mode)
    override fun depthRange(near: Float, far: Float) = GLES30.glDepthRangef(near, far)

    // ---------------------------------------------------------------- shaders / programs

    override fun createShader(type: Int): Int = GLES30.glCreateShader(type)
    override fun shaderSource(shader: Int, src: String) = GLES30.glShaderSource(shader, src)
    override fun compileShader(shader: Int) = GLES30.glCompileShader(shader)

    override fun getShaderi(shader: Int, pname: Int): Int {
        GLES30.glGetShaderiv(shader, pname, tmp, 0)
        return tmp[0]
    }

    override fun getShaderInfoLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader) ?: ""
    override fun deleteShader(shader: Int) = GLES30.glDeleteShader(shader)
    override fun createProgram(): Int = GLES30.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES30.glAttachShader(program, shader)
    override fun linkProgram(program: Int) = GLES30.glLinkProgram(program)

    override fun getProgrami(program: Int, pname: Int): Int {
        GLES30.glGetProgramiv(program, pname, tmp, 0)
        return tmp[0]
    }

    override fun getProgramInfoLog(program: Int): String = GLES30.glGetProgramInfoLog(program) ?: ""
    override fun useProgram(program: Int) = GLES30.glUseProgram(program)
    override fun deleteProgram(program: Int) = GLES30.glDeleteProgram(program)
    override fun getUniformLocation(program: Int, name: String): Int = GLES30.glGetUniformLocation(program, name)

    // ---------------------------------------------------------------- uniforms

    override fun uniform1i(loc: Int, v: Int) = GLES30.glUniform1i(loc, v)
    override fun uniform1f(loc: Int, v: Float) = GLES30.glUniform1f(loc, v)
    override fun uniform2f(loc: Int, x: Float, y: Float) = GLES30.glUniform2f(loc, x, y)
    override fun uniform3f(loc: Int, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc, x, y, z)
    override fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float) = GLES30.glUniform4f(loc, x, y, z, w)
    override fun uniform4fv(loc: Int, count: Int, v: FloatArray, offset: Int) = GLES30.glUniform4fv(loc, count, v, offset)
    override fun uniformMatrix4fv(loc: Int, v: FloatArray, offset: Int) = GLES30.glUniformMatrix4fv(loc, 1, false, v, offset)

    // ---------------------------------------------------------------- buffers / vertex arrays

    override fun genBuffer(): Int {
        GLES30.glGenBuffers(1, tmp, 0)
        return tmp[0]
    }

    override fun deleteBuffer(id: Int) {
        tmp[0] = id
        GLES30.glDeleteBuffers(1, tmp, 0)
    }

    override fun bindBuffer(target: Int, id: Int) = GLES30.glBindBuffer(target, id)

    /** A null [data] only allocates storage (the GLES binding passes NULL through). */
    override fun bufferData(target: Int, sizeBytes: Int, data: ByteBuffer?, usage: Int) =
        GLES30.glBufferData(target, sizeBytes, data, usage)

    override fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: ByteBuffer) =
        GLES30.glBufferSubData(target, offsetBytes, sizeBytes, data)

    override fun genVertexArray(): Int {
        GLES30.glGenVertexArrays(1, tmp, 0)
        return tmp[0]
    }

    override fun deleteVertexArray(id: Int) {
        tmp[0] = id
        GLES30.glDeleteVertexArrays(1, tmp, 0)
    }

    override fun bindVertexArray(id: Int) = GLES30.glBindVertexArray(id)
    override fun enableVertexAttribArray(index: Int) = GLES30.glEnableVertexAttribArray(index)

    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        GLES30.glVertexAttribPointer(index, size, type, normalized, stride, offset)

    override fun vertexAttribDivisor(index: Int, divisor: Int) = GLES30.glVertexAttribDivisor(index, divisor)

    // ---------------------------------------------------------------- draws

    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES30.glDrawArrays(mode, first, count)
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = GLES30.glDrawElements(mode, count, type, offset)
    override fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int) =
        GLES30.glDrawArraysInstanced(mode, first, count, instances)

    // ---------------------------------------------------------------- textures

    override fun genTexture(): Int {
        GLES30.glGenTextures(1, tmp, 0)
        return tmp[0]
    }

    override fun deleteTexture(id: Int) {
        tmp[0] = id
        GLES30.glDeleteTextures(1, tmp, 0)
    }

    override fun bindTexture(target: Int, id: Int) = GLES30.glBindTexture(target, id)
    override fun activeTexture(unit: Int) = GLES30.glActiveTexture(unit)

    /** A null [data] allocates an uninitialised texture (render target). */
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, w: Int, h: Int, format: Int, type: Int, data: ByteBuffer?) =
        GLES30.glTexImage2D(target, level, internalFormat, w, h, 0, format, type, data)

    override fun texParameteri(target: Int, pname: Int, param: Int) = GLES30.glTexParameteri(target, pname, param)
    override fun generateMipmap(target: Int) = GLES30.glGenerateMipmap(target)
    override fun pixelStorei(pname: Int, param: Int) = GLES30.glPixelStorei(pname, param)

    // ---------------------------------------------------------------- framebuffers

    override fun genFramebuffer(): Int {
        GLES30.glGenFramebuffers(1, tmp, 0)
        return tmp[0]
    }

    override fun deleteFramebuffer(id: Int) {
        tmp[0] = id
        GLES30.glDeleteFramebuffers(1, tmp, 0)
    }

    override fun bindFramebuffer(target: Int, id: Int) = GLES30.glBindFramebuffer(target, id)

    override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int) =
        GLES30.glFramebufferTexture2D(target, attachment, texTarget, texture, level)

    override fun checkFramebufferStatus(target: Int): Int = GLES30.glCheckFramebufferStatus(target)

    /**
     * Tile-based GPUs (Adreno, Mali, PowerVR) can then skip writing depth/stencil back to memory at
     * the end of the render pass. Must be called while the default framebuffer (0) is bound, after
     * the last draw that needs depth.
     */
    override fun invalidateDepth() =
        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 2, depthStencilAttachments, 0)

    override fun getError(): Int = GLES30.glGetError()
    override fun getString(name: Int): String? = GLES30.glGetString(name)
}
