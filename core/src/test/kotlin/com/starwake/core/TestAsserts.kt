package com.starwake.core

import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Small assertion helpers shared by the :core unit tests. */
object TestAsserts {
    fun assertNear(expected: Float, actual: Float, eps: Float, message: String = "") {
        if (!(abs(expected - actual) <= eps)) fail("$message expected $expected ± $eps but was $actual")
    }

    fun assertVec(ex: Float, ey: Float, ez: Float, actual: Vec3, eps: Float = 1e-5f, message: String = "") {
        if (!(abs(ex - actual.x) <= eps && abs(ey - actual.y) <= eps && abs(ez - actual.z) <= eps)) {
            fail("$message expected ($ex, $ey, $ez) ± $eps but was $actual")
        }
    }

    fun assertVec(expected: Vec3, actual: Vec3, eps: Float = 1e-5f, message: String = "") =
        assertVec(expected.x, expected.y, expected.z, actual, eps, message)

    /** q and -q describe the same rotation, so compare |q·p| against 1. */
    fun assertSameRotation(expected: Quat, actual: Quat, eps: Float = 1e-5f, message: String = "") {
        val d = abs(expected.dot(actual))
        assertTrue(1f - d <= eps, "$message expected rotation $expected but was $actual (|dot| = $d)")
    }

    fun assertUnit(q: Quat, eps: Float = 1e-5f) {
        val l = q.dot(q)
        assertTrue(abs(1f - l) <= eps, "quaternion not unit length: $q (|q|² = $l)")
    }

    fun assertUnit(v: Vec3, eps: Float = 1e-5f, message: String = "") {
        assertTrue(abs(1f - v.length()) <= eps, "$message vector not unit length: $v (|v| = ${v.length()})")
    }
}
