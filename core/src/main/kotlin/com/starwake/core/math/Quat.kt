package com.starwake.core.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mutable unit quaternion (x, y, z, w). Conventions: right handed, objects face -Z, +Y up.
 * All operations are allocation free.
 */
class Quat(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var z: Float = 0f,
    @JvmField var w: Float = 1f,
) {
    fun set(x: Float, y: Float, z: Float, w: Float): Quat {
        this.x = x; this.y = y; this.z = z; this.w = w; return this
    }

    fun set(q: Quat): Quat = set(q.x, q.y, q.z, q.w)
    fun identity(): Quat = set(0f, 0f, 0f, 1f)

    fun setAxisAngle(ax: Float, ay: Float, az: Float, angle: Float): Quat {
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-12f) return identity()
        val h = angle * 0.5f
        val s = sin(h) / len
        return set(ax * s, ay * s, az * s, cos(h))
    }

    /** this = a * b (apply b first, then a). Safe with aliasing. */
    fun mul(a: Quat, b: Quat): Quat {
        val nw = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
        val nx = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y
        val ny = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x
        val nz = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w
        return set(nx, ny, nz, nw)
    }

    fun normalize(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        if (l < 1e-12f) return identity()
        val inv = 1f / l
        x *= inv; y *= inv; z *= inv; w *= inv
        return this
    }

    fun dot(q: Quat): Float = x * q.x + y * q.y + z * q.z + w * q.w

    /** out = this * v * this⁻¹ */
    fun rotate(vx: Float, vy: Float, vz: Float, out: Vec3): Vec3 {
        // t = 2 * cross(q.xyz, v)
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)
        // v' = v + w * t + cross(q.xyz, t)
        return out.set(
            vx + w * tx + (y * tz - z * ty),
            vy + w * ty + (z * tx - x * tz),
            vz + w * tz + (x * ty - y * tx),
        )
    }

    fun rotate(v: Vec3, out: Vec3): Vec3 = rotate(v.x, v.y, v.z, out)

    /** out = this⁻¹ * v * this (world -> local). */
    fun rotateInverse(vx: Float, vy: Float, vz: Float, out: Vec3): Vec3 {
        val qx = -x
        val qy = -y
        val qz = -z
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        return out.set(
            vx + w * tx + (qy * tz - qz * ty),
            vy + w * ty + (qz * tx - qx * tz),
            vz + w * tz + (qx * ty - qy * tx),
        )
    }

    fun rotateInverse(v: Vec3, out: Vec3): Vec3 = rotateInverse(v.x, v.y, v.z, out)

    /** out = this * v * this⁻¹ in double precision (velocities of up to 10⁹ m/s keep their low digits). */
    fun rotate(vx: Double, vy: Double, vz: Double, out: DVec3): DVec3 {
        val qx = x.toDouble()
        val qy = y.toDouble()
        val qz = z.toDouble()
        val qw = w.toDouble()
        val tx = 2.0 * (qy * vz - qz * vy)
        val ty = 2.0 * (qz * vx - qx * vz)
        val tz = 2.0 * (qx * vy - qy * vx)
        return out.set(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx),
        )
    }

    /** out = this⁻¹ * v * this (world -> local) in double precision. */
    fun rotateInverse(vx: Double, vy: Double, vz: Double, out: DVec3): DVec3 {
        val qx = -x.toDouble()
        val qy = -y.toDouble()
        val qz = -z.toDouble()
        val qw = w.toDouble()
        val tx = 2.0 * (qy * vz - qz * vy)
        val ty = 2.0 * (qz * vx - qx * vz)
        val tz = 2.0 * (qx * vy - qy * vx)
        return out.set(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx),
        )
    }

    /** Integrates a body-frame angular velocity (rad/s) over dt: this = this * exp(ω dt / 2). */
    fun integrateLocal(wx: Float, wy: Float, wz: Float, dt: Float): Quat {
        val ax = wx * dt
        val ay = wy * dt
        val az = wz * dt
        val angle = sqrt(ax * ax + ay * ay + az * az)
        if (angle < 1e-9f) return this
        val h = angle * 0.5f
        val s = sin(h) / angle
        val dx = ax * s
        val dy = ay * s
        val dz = az * s
        val dw = cos(h)
        val nw = w * dw - x * dx - y * dy - z * dz
        val nx = w * dx + x * dw + y * dz - z * dy
        val ny = w * dy - x * dz + y * dw + z * dx
        val nz = w * dz + x * dy - y * dx + z * dw
        set(nx, ny, nz, nw)
        return normalize()
    }

    /** this = slerp(a, b, t) along the shortest arc. */
    fun slerp(a: Quat, b: Quat, t: Float): Quat {
        var bx = b.x
        var by = b.y
        var bz = b.z
        var bw = b.w
        var d = a.x * bx + a.y * by + a.z * bz + a.w * bw
        if (d < 0f) {
            d = -d; bx = -bx; by = -by; bz = -bz; bw = -bw
        }
        val s0: Float
        val s1: Float
        if (d > 0.9995f) {
            s0 = 1f - t
            s1 = t
        } else {
            val theta = acos(d)
            val sinTheta = sin(theta)
            s0 = sin((1f - t) * theta) / sinTheta
            s1 = sin(t * theta) / sinTheta
        }
        set(a.x * s0 + bx * s1, a.y * s0 + by * s1, a.z * s0 + bz * s1, a.w * s0 + bw * s1)
        return normalize()
    }

    /**
     * Orientation whose -Z axis points along [forward] and whose +Y is as close as possible to [up].
     */
    fun lookRotation(fx: Float, fy: Float, fz: Float, ux: Float, uy: Float, uz: Float): Quat {
        var fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl < 1e-9f) return identity()
        fl = 1f / fl
        val f0 = fx * fl
        val f1 = fy * fl
        val f2 = fz * fl
        // right = forward × up
        var rx = f1 * uz - f2 * uy
        var ry = f2 * ux - f0 * uz
        var rz = f0 * uy - f1 * ux
        var rl = sqrt(rx * rx + ry * ry + rz * rz)
        if (rl < 1e-6f) {
            // forward parallel to up: use the world axis least aligned with forward instead
            val ax: Float
            val ay: Float
            val az: Float
            if (abs(f0) < abs(f1) && abs(f0) < abs(f2)) { ax = 1f; ay = 0f; az = 0f } else if (abs(f1) < abs(f2)) { ax = 0f; ay = 1f; az = 0f } else { ax = 0f; ay = 0f; az = 1f }
            rx = f1 * az - f2 * ay
            ry = f2 * ax - f0 * az
            rz = f0 * ay - f1 * ax
            rl = sqrt(rx * rx + ry * ry + rz * rz)
        }
        rl = 1f / rl
        rx *= rl; ry *= rl; rz *= rl
        // up' = right × forward
        val upx = ry * f2 - rz * f1
        val upy = rz * f0 - rx * f2
        val upz = rx * f1 - ry * f0
        // basis columns: X = right, Y = up', Z = -forward
        return fromBasis(rx, ry, rz, upx, upy, upz, -f0, -f1, -f2)
    }

    /** Builds the quaternion from an orthonormal basis given by its column vectors. */
    fun fromBasis(
        m00: Float, m10: Float, m20: Float,
        m01: Float, m11: Float, m21: Float,
        m02: Float, m12: Float, m22: Float,
    ): Quat {
        val trace = m00 + m11 + m22
        if (trace > 0f) {
            val s = 0.5f / sqrt(trace + 1f)
            set((m21 - m12) * s, (m02 - m20) * s, (m10 - m01) * s, 0.25f / s)
        } else if (m00 > m11 && m00 > m22) {
            val s = 2f * sqrt(1f + m00 - m11 - m22)
            set(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = 2f * sqrt(1f + m11 - m00 - m22)
            set((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = 2f * sqrt(1f + m22 - m00 - m11)
            set((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
        }
        return normalize()
    }

    override fun toString(): String = "Quat($x, $y, $z, $w)"
}
