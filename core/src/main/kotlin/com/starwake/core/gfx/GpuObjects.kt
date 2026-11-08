package com.starwake.core.gfx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

object Buffers {
    fun bytes(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    fun of(data: FloatArray, count: Int = data.size): ByteBuffer {
        val b = bytes(count * 4)
        b.asFloatBuffer().put(data, 0, count)
        b.position(0)
        return b
    }

    fun of(data: ShortArray): ByteBuffer {
        val b = bytes(data.size * 2)
        b.asShortBuffer().put(data)
        b.position(0)
        return b
    }

    fun of(data: ByteArray): ByteBuffer {
        val b = bytes(data.size)
        b.put(data)
        b.position(0)
        return b
    }
}

class ShaderProgram(private val gl: Gl, val name: String, vertexSrc: String, fragmentSrc: String) {
    val id: Int

    init {
        val vs = compile(GL.VERTEX_SHADER, vertexSrc)
        val fs = compile(GL.FRAGMENT_SHADER, fragmentSrc)
        id = gl.createProgram()
        gl.attachShader(id, vs)
        gl.attachShader(id, fs)
        gl.linkProgram(id)
        if (gl.getProgrami(id, GL.LINK_STATUS) == 0) {
            val log = gl.getProgramInfoLog(id)
            gl.deleteProgram(id)
            throw IllegalStateException("Shader link failed [$name]: $log")
        }
        gl.deleteShader(vs)
        gl.deleteShader(fs)
    }

    private fun compile(type: Int, src: String): Int {
        val s = gl.createShader(type)
        gl.shaderSource(s, src)
        gl.compileShader(s)
        if (gl.getShaderi(s, GL.COMPILE_STATUS) == 0) {
            val log = gl.getShaderInfoLog(s)
            gl.deleteShader(s)
            val kind = if (type == GL.VERTEX_SHADER) "vertex" else "fragment"
            throw IllegalStateException("Shader compile failed [$name/$kind]: $log")
        }
        return s
    }

    private val cacheNames = arrayOfNulls<String>(32)
    private val cacheLocs = IntArray(32)
    private var cacheSize = 0

    fun use() = gl.useProgram(id)

    /**
     * Uniform location with a tiny allocation-free cache. Callers pass string literals, which are
     * interned, so the identity check almost always hits and no GL query happens per frame.
     */
    fun u(uniform: String): Int {
        for (i in 0 until cacheSize) if (cacheNames[i] === uniform) return cacheLocs[i]
        for (i in 0 until cacheSize) if (cacheNames[i] == uniform) return cacheLocs[i]
        val loc = gl.getUniformLocation(id, uniform)
        if (cacheSize < cacheNames.size) {
            cacheNames[cacheSize] = uniform
            cacheLocs[cacheSize] = loc
            cacheSize++
        }
        return loc
    }

    fun dispose() = gl.deleteProgram(id)
}

/**
 * Static vertex data with float attributes at consecutive locations starting at 0.
 * [attribSizes] e.g. [3, 3, 2] for position, normal, material.
 */
class GpuMesh(
    private val gl: Gl,
    data: FloatArray,
    attribSizes: IntArray,
    indices: ShortArray? = null,
) {
    val vao = gl.genVertexArray()
    private val vbo = gl.genBuffer()
    private val ibo = if (indices != null) gl.genBuffer() else 0
    private var instanceVbo = 0
    val floatsPerVertex = attribSizes.sum()
    val vertexCount = data.size / floatsPerVertex
    val indexCount = indices?.size ?: 0

    init {
        gl.bindVertexArray(vao)
        gl.bindBuffer(GL.ARRAY_BUFFER, vbo)
        gl.bufferData(GL.ARRAY_BUFFER, data.size * 4, Buffers.of(data), GL.STATIC_DRAW)
        var offset = 0
        val stride = floatsPerVertex * 4
        for (i in attribSizes.indices) {
            gl.enableVertexAttribArray(i)
            gl.vertexAttribPointer(i, attribSizes[i], GL.FLOAT, false, stride, offset)
            offset += attribSizes[i] * 4
        }
        if (indices != null) {
            gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, ibo)
            gl.bufferData(GL.ELEMENT_ARRAY_BUFFER, indices.size * 2, Buffers.of(indices), GL.STATIC_DRAW)
        }
        gl.bindVertexArray(0)
    }

    /** Adds a per-instance float buffer whose attributes start at [firstLocation]. */
    fun setInstanceData(data: FloatArray, count: Int, attribSizes: IntArray, firstLocation: Int) {
        gl.bindVertexArray(vao)
        if (instanceVbo == 0) instanceVbo = gl.genBuffer()
        gl.bindBuffer(GL.ARRAY_BUFFER, instanceVbo)
        val floats = attribSizes.sum()
        gl.bufferData(GL.ARRAY_BUFFER, count * floats * 4, Buffers.of(data, count * floats), GL.STATIC_DRAW)
        var offset = 0
        for (i in attribSizes.indices) {
            val loc = firstLocation + i
            gl.enableVertexAttribArray(loc)
            gl.vertexAttribPointer(loc, attribSizes[i], GL.FLOAT, false, floats * 4, offset)
            gl.vertexAttribDivisor(loc, 1)
            offset += attribSizes[i] * 4
        }
        gl.bindVertexArray(0)
    }

    fun draw(mode: Int = GL.TRIANGLES) {
        gl.bindVertexArray(vao)
        if (indexCount > 0) gl.drawElements(mode, indexCount, GL.UNSIGNED_SHORT, 0)
        else gl.drawArrays(mode, 0, vertexCount)
    }

    fun drawInstanced(instances: Int) {
        if (instances <= 0) return
        gl.bindVertexArray(vao)
        gl.drawArraysInstanced(GL.TRIANGLES, 0, vertexCount, instances)
    }

    fun dispose() {
        gl.deleteVertexArray(vao)
        gl.deleteBuffer(vbo)
        if (ibo != 0) gl.deleteBuffer(ibo)
        if (instanceVbo != 0) gl.deleteBuffer(instanceVbo)
    }
}

/**
 * Per-frame streamed vertex data for quads. Vertices are written into an IntArray staging area
 * (floats via raw bits, colors as packed bytes) and uploaded with one bulk copy. A ring of three
 * buffers avoids stalling on buffers the GPU is still reading.
 */
class QuadStream(
    private val gl: Gl,
    val maxQuads: Int,
    /** Attribute layout: pairs of (componentCount, type) with type FLOAT or UNSIGNED_BYTE (normalized, 4 comps). */
    private val layout: IntArray,
) {
    val wordsPerVertex: Int
    val staging: IntArray
    private val upload: ByteBuffer
    private val uploadInts: IntBuffer
    private val vaos = IntArray(RING)
    private val vbos = IntArray(RING)
    private val ibo: Int
    private var ring = 0

    /** Number of ints written so far this batch. */
    var cursor = 0
    var quads = 0
        private set

    init {
        var words = 0
        var i = 0
        while (i < layout.size) {
            words += if (layout[i + 1] == GL.FLOAT) layout[i] else 1
            i += 2
        }
        wordsPerVertex = words
        staging = IntArray(maxQuads * 4 * wordsPerVertex)
        upload = Buffers.bytes(staging.size * 4)
        uploadInts = upload.asIntBuffer()

        val idx = ShortArray(maxQuads * 6)
        for (q in 0 until maxQuads) {
            val v = q * 4
            idx[q * 6] = v.toShort()
            idx[q * 6 + 1] = (v + 1).toShort()
            idx[q * 6 + 2] = (v + 2).toShort()
            idx[q * 6 + 3] = (v + 2).toShort()
            idx[q * 6 + 4] = (v + 1).toShort()
            idx[q * 6 + 5] = (v + 3).toShort()
        }
        ibo = gl.genBuffer()
        val stride = wordsPerVertex * 4
        for (r in 0 until RING) {
            vaos[r] = gl.genVertexArray()
            vbos[r] = gl.genBuffer()
            gl.bindVertexArray(vaos[r])
            gl.bindBuffer(GL.ARRAY_BUFFER, vbos[r])
            gl.bufferData(GL.ARRAY_BUFFER, staging.size * 4, null, GL.STREAM_DRAW)
            var off = 0
            var loc = 0
            i = 0
            while (i < layout.size) {
                val comps = layout[i]
                val type = layout[i + 1]
                gl.enableVertexAttribArray(loc)
                if (type == GL.FLOAT) {
                    gl.vertexAttribPointer(loc, comps, GL.FLOAT, false, stride, off)
                    off += comps * 4
                } else {
                    gl.vertexAttribPointer(loc, 4, GL.UNSIGNED_BYTE, true, stride, off)
                    off += 4
                }
                loc++
                i += 2
            }
            gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, ibo)
            if (r == 0) gl.bufferData(GL.ELEMENT_ARRAY_BUFFER, idx.size * 2, Buffers.of(idx), GL.STATIC_DRAW)
        }
        gl.bindVertexArray(0)
    }

    fun begin() {
        cursor = 0
        quads = 0
    }

    /** Call after writing 4 vertices. Returns false (and discards) when the stream is full. */
    fun endQuad(): Boolean {
        if (quads >= maxQuads) {
            cursor -= 4 * wordsPerVertex
            return false
        }
        quads++
        return true
    }

    fun hasRoom(): Boolean = quads < maxQuads

    fun flush() {
        if (quads == 0) return
        val vao = vaos[ring]
        gl.bindVertexArray(vao)
        gl.bindBuffer(GL.ARRAY_BUFFER, vbos[ring])
        uploadInts.position(0)
        uploadInts.put(staging, 0, cursor)
        upload.position(0)
        gl.bufferSubData(GL.ARRAY_BUFFER, 0, cursor * 4, upload)
        gl.drawElements(GL.TRIANGLES, quads * 6, GL.UNSIGNED_SHORT, 0)
        ring = (ring + 1) % RING
        begin()
    }

    fun dispose() {
        for (r in 0 until RING) {
            gl.deleteVertexArray(vaos[r])
            gl.deleteBuffer(vbos[r])
        }
        gl.deleteBuffer(ibo)
    }

    companion object {
        const val RING = 3

        /** Converts ARGB to the little-endian RGBA byte order GL reads from a packed int. */
        fun abgr(argb: Int): Int =
            (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16)
    }
}
