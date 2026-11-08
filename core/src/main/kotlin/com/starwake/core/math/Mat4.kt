package com.starwake.core.math

import kotlin.math.tan

/** Column-major 4x4 matrix backed by a FloatArray (OpenGL layout). */
class Mat4 {
    @JvmField
    val m = FloatArray(16)

    init {
        identity()
    }

    fun identity(): Mat4 {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return this
    }

    fun set(o: Mat4): Mat4 {
        System.arraycopy(o.m, 0, m, 0, 16)
        return this
    }

    /**
     * Perspective projection. [shiftX]/[shiftY] move the image in NDC units (lens shift) without
     * distorting perspective – used to place the ship off-centre in the menu.
     */
    fun perspective(fovY: Float, aspect: Float, near: Float, far: Float, shiftX: Float = 0f, shiftY: Float = 0f): Mat4 {
        val f = 1f / tan(fovY * 0.5f)
        m.fill(0f)
        m[0] = f / aspect
        m[5] = f
        m[8] = -shiftX
        m[9] = -shiftY
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return this
    }

    fun ortho(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float): Mat4 {
        m.fill(0f)
        m[0] = 2f / (right - left)
        m[5] = 2f / (top - bottom)
        m[10] = -2f / (far - near)
        m[12] = -(right + left) / (right - left)
        m[13] = -(top + bottom) / (top - bottom)
        m[14] = -(far + near) / (far - near)
        m[15] = 1f
        return this
    }

    /** Model matrix: translate(t) * rotate(q) * scale(s). */
    fun setTRS(tx: Float, ty: Float, tz: Float, q: Quat, s: Float): Mat4 {
        val x = q.x
        val y = q.y
        val z = q.z
        val w = q.w
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        m[0] = (1f - 2f * (yy + zz)) * s
        m[1] = 2f * (xy + wz) * s
        m[2] = 2f * (xz - wy) * s
        m[3] = 0f
        m[4] = 2f * (xy - wz) * s
        m[5] = (1f - 2f * (xx + zz)) * s
        m[6] = 2f * (yz + wx) * s
        m[7] = 0f
        m[8] = 2f * (xz + wy) * s
        m[9] = 2f * (yz - wx) * s
        m[10] = (1f - 2f * (xx + yy)) * s
        m[11] = 0f
        m[12] = tx
        m[13] = ty
        m[14] = tz
        m[15] = 1f
        return this
    }

    /** View matrix for a camera at the origin with orientation [q] (camera-relative rendering). */
    fun setViewRotation(q: Quat): Mat4 {
        setTRS(0f, 0f, 0f, q, 1f)
        // transpose the rotation part (inverse of a rotation)
        var t = m[1]; m[1] = m[4]; m[4] = t
        t = m[2]; m[2] = m[8]; m[8] = t
        t = m[6]; m[6] = m[9]; m[9] = t
        return this
    }

    /** this = a * b. Must not alias a or b. */
    fun mul(a: Mat4, b: Mat4): Mat4 {
        val am = a.m
        val bm = b.m
        for (c in 0 until 4) {
            val b0 = bm[c * 4]
            val b1 = bm[c * 4 + 1]
            val b2 = bm[c * 4 + 2]
            val b3 = bm[c * 4 + 3]
            m[c * 4] = am[0] * b0 + am[4] * b1 + am[8] * b2 + am[12] * b3
            m[c * 4 + 1] = am[1] * b0 + am[5] * b1 + am[9] * b2 + am[13] * b3
            m[c * 4 + 2] = am[2] * b0 + am[6] * b1 + am[10] * b2 + am[14] * b3
            m[c * 4 + 3] = am[3] * b0 + am[7] * b1 + am[11] * b2 + am[15] * b3
        }
        return this
    }

    /** Transforms (x, y, z, 1); writes clip coordinates to [out] (x, y, z, w). */
    fun transform(x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        out[3] = m[3] * x + m[7] * y + m[11] * z + m[15]
    }
}
