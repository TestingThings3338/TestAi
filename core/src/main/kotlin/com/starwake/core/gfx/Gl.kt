package com.starwake.core.gfx

import java.nio.ByteBuffer

/**
 * Minimal OpenGL ES 3.0 surface used by the engine. Android implements it with
 * android.opengl.GLES30, the desktop preview harness with LWJGL on Mesa. Keeping the engine on this
 * interface lets the exact same rendering code be verified headlessly.
 */
interface Gl {
    /** Framebuffer that represents "the screen" (0 on Android, an offscreen FBO in the harness). */
    val screenFramebuffer: Int

    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(mask: Int)
    fun viewport(x: Int, y: Int, w: Int, h: Int)
    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFunc(src: Int, dst: Int)
    fun depthMask(flag: Boolean)
    fun depthFunc(func: Int)
    fun cullFace(mode: Int)

    /** glDepthRangef: maps NDC depth into [near, far] of the depth buffer. */
    fun depthRange(near: Float, far: Float)

    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, src: String)
    fun compileShader(shader: Int)
    fun getShaderi(shader: Int, pname: Int): Int
    fun getShaderInfoLog(shader: Int): String
    fun deleteShader(shader: Int)
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun linkProgram(program: Int)
    fun getProgrami(program: Int, pname: Int): Int
    fun getProgramInfoLog(program: Int): String
    fun useProgram(program: Int)
    fun deleteProgram(program: Int)
    fun getUniformLocation(program: Int, name: String): Int

    fun uniform1i(loc: Int, v: Int)
    fun uniform1f(loc: Int, v: Float)
    fun uniform2f(loc: Int, x: Float, y: Float)
    fun uniform3f(loc: Int, x: Float, y: Float, z: Float)
    fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float)
    fun uniform4fv(loc: Int, count: Int, v: FloatArray, offset: Int)
    fun uniformMatrix4fv(loc: Int, v: FloatArray, offset: Int)

    fun genBuffer(): Int
    fun deleteBuffer(id: Int)
    fun bindBuffer(target: Int, id: Int)
    fun bufferData(target: Int, sizeBytes: Int, data: ByteBuffer?, usage: Int)
    fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: ByteBuffer)

    fun genVertexArray(): Int
    fun deleteVertexArray(id: Int)
    fun bindVertexArray(id: Int)
    fun enableVertexAttribArray(index: Int)
    fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)
    fun vertexAttribDivisor(index: Int, divisor: Int)

    fun drawArrays(mode: Int, first: Int, count: Int)
    fun drawElements(mode: Int, count: Int, type: Int, offset: Int)
    fun drawArraysInstanced(mode: Int, first: Int, count: Int, instances: Int)

    fun genTexture(): Int
    fun deleteTexture(id: Int)
    fun bindTexture(target: Int, id: Int)
    fun activeTexture(unit: Int)
    fun texImage2D(target: Int, level: Int, internalFormat: Int, w: Int, h: Int, format: Int, type: Int, data: ByteBuffer?)

    /** glTexImage3D (border 0): the tiling detail noise of the cloud deck. */
    fun texImage3D(target: Int, level: Int, internalFormat: Int, w: Int, h: Int, d: Int, format: Int, type: Int, data: ByteBuffer?)
    fun texParameteri(target: Int, pname: Int, param: Int)
    fun generateMipmap(target: Int)
    fun pixelStorei(pname: Int, param: Int)

    fun genFramebuffer(): Int
    fun deleteFramebuffer(id: Int)
    fun bindFramebuffer(target: Int, id: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int)
    fun checkFramebufferStatus(target: Int): Int

    /** Tells a tiled GPU it does not need to write the depth buffer back to memory. */
    fun invalidateDepth()

    fun getError(): Int
    fun getString(name: Int): String?
}

/** GL ES enum values (identical on every platform). */
object GL {
    const val DEPTH_BUFFER_BIT = 0x00000100
    const val COLOR_BUFFER_BIT = 0x00004000

    const val POINTS = 0x0000
    const val LINES = 0x0001
    const val TRIANGLES = 0x0004
    const val TRIANGLE_STRIP = 0x0005

    const val ZERO = 0
    const val ONE = 1
    const val SRC_ALPHA = 0x0302
    const val ONE_MINUS_SRC_ALPHA = 0x0303

    const val BLEND = 0x0BE2
    const val DEPTH_TEST = 0x0B71
    const val CULL_FACE = 0x0B44
    const val BACK = 0x0405
    const val LESS = 0x0201
    const val LEQUAL = 0x0203

    const val ARRAY_BUFFER = 0x8892
    const val ELEMENT_ARRAY_BUFFER = 0x8893
    const val STATIC_DRAW = 0x88E4
    const val DYNAMIC_DRAW = 0x88E8
    const val STREAM_DRAW = 0x88E0

    const val BYTE = 0x1400
    const val UNSIGNED_BYTE = 0x1401
    const val SHORT = 0x1402
    const val UNSIGNED_SHORT = 0x1403
    const val UNSIGNED_INT = 0x1405
    const val FLOAT = 0x1406

    const val FRAGMENT_SHADER = 0x8B30
    const val VERTEX_SHADER = 0x8B31
    const val COMPILE_STATUS = 0x8B81
    const val LINK_STATUS = 0x8B82

    const val TEXTURE_2D = 0x0DE1
    const val TEXTURE_3D = 0x806F
    const val TEXTURE_CUBE_MAP = 0x8513
    const val TEXTURE_CUBE_MAP_POSITIVE_X = 0x8515
    const val TEXTURE0 = 0x84C0
    const val TEXTURE1 = 0x84C1
    const val TEXTURE_MAG_FILTER = 0x2800
    const val TEXTURE_MIN_FILTER = 0x2801
    const val TEXTURE_WRAP_S = 0x2802
    const val TEXTURE_WRAP_T = 0x2803
    const val TEXTURE_WRAP_R = 0x8072
    const val NEAREST = 0x2600
    const val LINEAR = 0x2601
    const val LINEAR_MIPMAP_LINEAR = 0x2703
    const val CLAMP_TO_EDGE = 0x812F
    const val REPEAT = 0x2901

    const val RED = 0x1903
    const val RGBA = 0x1908
    const val R8 = 0x8229
    const val RG = 0x8227
    const val RG8 = 0x822B
    const val RGBA8 = 0x8058
    const val UNPACK_ALIGNMENT = 0x0CF5

    const val FRAMEBUFFER = 0x8D40
    const val COLOR_ATTACHMENT0 = 0x8CE0
    const val FRAMEBUFFER_COMPLETE = 0x8CD5

    const val VENDOR = 0x1F00
    const val RENDERER = 0x1F01
    const val VERSION = 0x1F02
    const val NO_ERROR = 0
}
