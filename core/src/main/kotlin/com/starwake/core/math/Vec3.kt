package com.starwake.core.math

import kotlin.math.sqrt

/** Mutable float vector. All operations are in-place and allocation free. */
class Vec3(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var z: Float = 0f,
) {
    fun set(x: Float, y: Float, z: Float): Vec3 {
        this.x = x; this.y = y; this.z = z; return this
    }

    fun set(v: Vec3): Vec3 = set(v.x, v.y, v.z)
    fun zero(): Vec3 = set(0f, 0f, 0f)

    fun add(v: Vec3): Vec3 {
        x += v.x; y += v.y; z += v.z; return this
    }

    fun add(ax: Float, ay: Float, az: Float): Vec3 {
        x += ax; y += ay; z += az; return this
    }

    fun sub(v: Vec3): Vec3 {
        x -= v.x; y -= v.y; z -= v.z; return this
    }

    fun addScaled(v: Vec3, s: Float): Vec3 {
        x += v.x * s; y += v.y * s; z += v.z * s; return this
    }

    fun scale(s: Float): Vec3 {
        x *= s; y *= s; z *= s; return this
    }

    fun dot(v: Vec3): Float = x * v.x + y * v.y + z * v.z
    fun dot(ax: Float, ay: Float, az: Float): Float = x * ax + y * ay + z * az

    /** this = a × b (safe when this aliases a or b). */
    fun cross(a: Vec3, b: Vec3): Vec3 {
        val cx = a.y * b.z - a.z * b.y
        val cy = a.z * b.x - a.x * b.z
        val cz = a.x * b.y - a.y * b.x
        return set(cx, cy, cz)
    }

    fun lengthSq(): Float = x * x + y * y + z * z
    fun length(): Float = sqrt(lengthSq())

    fun normalize(): Vec3 {
        val l = length()
        if (l > 1e-12f) scale(1f / l)
        return this
    }

    fun lerp(to: Vec3, t: Float): Vec3 {
        x += (to.x - x) * t; y += (to.y - y) * t; z += (to.z - z) * t; return this
    }

    fun distance(v: Vec3): Float {
        val dx = x - v.x
        val dy = y - v.y
        val dz = z - v.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    override fun toString(): String = "($x, $y, $z)"
}

/** Mutable double vector for world-space positions (keeps precision far from the origin). */
class DVec3(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var z: Double = 0.0,
) {
    fun set(x: Double, y: Double, z: Double): DVec3 {
        this.x = x; this.y = y; this.z = z; return this
    }

    fun set(v: DVec3): DVec3 = set(v.x, v.y, v.z)

    fun addScaled(v: Vec3, s: Double): DVec3 {
        x += v.x * s; y += v.y * s; z += v.z * s; return this
    }

    fun add(v: Vec3): DVec3 {
        x += v.x; y += v.y; z += v.z; return this
    }

    fun lerp(to: DVec3, t: Double): DVec3 {
        x += (to.x - x) * t; y += (to.y - y) * t; z += (to.z - z) * t; return this
    }

    /** out = this - origin, as floats (camera-relative coordinates). */
    fun relativeTo(origin: DVec3, out: Vec3): Vec3 =
        out.set((x - origin.x).toFloat(), (y - origin.y).toFloat(), (z - origin.z).toFloat())

    override fun toString(): String = "($x, $y, $z)"
}
