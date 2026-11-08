package com.starwake.core.math

import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.TestAsserts.assertVec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class Mat4Test {
    private val clip = FloatArray(4)

    /** Projects a camera-space point and returns NDC (x, y, z). */
    private fun ndc(m: Mat4, x: Float, y: Float, z: Float): Vec3 {
        m.transform(x, y, z, clip)
        return Vec3(clip[0] / clip[3], clip[1] / clip[3], clip[2] / clip[3])
    }

    @Test
    fun newMatrixIsIdentity() {
        val m = Mat4()
        assertContentEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), m.m)
        m.transform(1f, 2f, 3f, clip)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 1f), clip)
    }

    @Test
    fun perspectiveMapsNearAndFarPlanesToMinusOneAndOne() {
        val near = 0.5f
        val far = 5000f
        val m = Mat4().perspective(60f * DEG2RAD, 16f / 9f, near, far)
        for ((x, y) in listOf(0f to 0f, 0.1f to -0.2f, -0.25f to 0.15f)) {
            assertNear(-1f, ndc(m, x * near, y * near, -near).z, 1e-4f, "near plane")
            assertNear(1f, ndc(m, x * far, y * far, -far).z, 1e-4f, "far plane")
        }
        // depth increases monotonically in between, w = distance in front of the camera
        var prev = -1f
        for (d in listOf(1f, 10f, 100f, 1000f, 4000f)) {
            val z = ndc(m, 0f, 0f, -d).z
            assertTrue(z > prev && z < 1f, "depth must increase monotonically: $prev -> $z at $d")
            prev = z
            assertNear(d, clip[3], 1e-3f)
        }
    }

    @Test
    fun perspectiveFieldOfViewAndAspect() {
        val fov = 70f * DEG2RAD
        val aspect = 2f
        val m = Mat4().perspective(fov, aspect, 0.1f, 100f)
        val t = kotlin.math.tan(fov / 2f)
        // a point on the top edge of the frustum lands on y = +1, right edge on x = +1
        assertNear(1f, ndc(m, 0f, t * 10f, -10f).y, 1e-5f)
        assertNear(1f, ndc(m, t * aspect * 10f, 0f, -10f).x, 1e-5f)
        assertNear(-1f, ndc(m, -t * aspect * 10f, 0f, -10f).x, 1e-5f)
    }

    @Test
    fun lensShiftTranslatesTheImageUniformly() {
        val shiftX = 0.3f
        val shiftY = -0.2f
        val plain = Mat4().perspective(50f * DEG2RAD, 1.5f, 0.1f, 1000f)
        val shifted = Mat4().perspective(50f * DEG2RAD, 1.5f, 0.1f, 1000f, shiftX, shiftY)
        for (p in listOf(Vec3(0f, 0f, -5f), Vec3(1f, 2f, -10f), Vec3(-3f, 0.5f, -50f), Vec3(20f, -10f, -400f))) {
            val a = ndc(plain, p.x, p.y, p.z)
            val b = ndc(shifted, p.x, p.y, p.z)
            assertNear(shiftX, b.x - a.x, 1e-5f, "x shift at $p")
            assertNear(shiftY, b.y - a.y, 1e-5f, "y shift at $p")
            assertNear(a.z, b.z, 1e-6f, "depth must not change")
        }
        // positive shift moves the image right/up: a point straight ahead now projects off-centre
        val centre = ndc(shifted, 0f, 0f, -10f)
        assertNear(shiftX, centre.x, 1e-6f)
        assertNear(shiftY, centre.y, 1e-6f)
    }

    @Test
    fun orthoMapsTheBoxToTheUnitCube() {
        val m = Mat4().ortho(0f, 800f, 600f, 0f, -1f, 1f) // y-down UI projection
        assertVec(-1f, 1f, 0f, ndc(m, 0f, 0f, 0f), 1e-6f)
        assertVec(1f, -1f, 0f, ndc(m, 800f, 600f, 0f), 1e-6f)
        assertVec(0f, 0f, 0f, ndc(m, 400f, 300f, 0f), 1e-6f)
        assertNear(-1f, ndc(m, 0f, 0f, 1f).z, 1e-6f) // z = -near
    }

    @Test
    fun setTrsRotationMatchesQuatRotate() {
        val rng = Rng(5)
        repeat(100) {
            val axis = rng.onSphere(Vec3())
            val q = Quat().setAxisAngle(axis.x, axis.y, axis.z, rng.range(-3f, 3f))
            val s = rng.range(0.5f, 3f)
            val t = Vec3(rng.signed() * 50f, rng.signed() * 50f, rng.signed() * 50f)
            val m = Mat4().setTRS(t.x, t.y, t.z, q, s)
            val v = Vec3(rng.signed(), rng.signed(), rng.signed())
            m.transform(v.x, v.y, v.z, clip)
            val expected = q.rotate(v, Vec3()).scale(s).add(t)
            assertVec(expected, Vec3(clip[0], clip[1], clip[2]), 1e-4f)
            assertNear(1f, clip[3], 0f)
        }
    }

    @Test
    fun setViewRotationIsTheInverseOfTheModelRotation() {
        val rng = Rng(9)
        repeat(100) {
            val axis = rng.onSphere(Vec3())
            val q = Quat().setAxisAngle(axis.x, axis.y, axis.z, rng.range(-3f, 3f))
            val model = Mat4().setTRS(0f, 0f, 0f, q, 1f)
            val view = Mat4().setViewRotation(q)
            val product = Mat4().mul(view, model)
            val id = Mat4()
            for (i in 0 until 16) assertNear(id.m[i], product.m[i], 1e-5f, "view*model [$i]")
            val product2 = Mat4().mul(model, view)
            for (i in 0 until 16) assertNear(id.m[i], product2.m[i], 1e-5f, "model*view [$i]")
            // and it matches rotateInverse (world -> camera)
            val v = Vec3(rng.signed(), rng.signed(), rng.signed())
            view.transform(v.x, v.y, v.z, clip)
            assertVec(q.rotateInverse(v, Vec3()), Vec3(clip[0], clip[1], clip[2]), 1e-5f)
        }
    }

    @Test
    fun viewOfAYawedCameraSeesItsForwardStraightAhead() {
        val q = Quat().setAxisAngle(0f, 1f, 0f, 0.8f)
        val fwd = q.rotate(0f, 0f, -1f, Vec3()).scale(10f)
        val view = Mat4().setViewRotation(q)
        view.transform(fwd.x, fwd.y, fwd.z, clip)
        assertVec(0f, 0f, -10f, Vec3(clip[0], clip[1], clip[2]), 1e-5f)
    }

    @Test
    fun mulMatchesAHandComputedProduct() {
        // column-major: m[col * 4 + row]
        val a = Mat4()
        val b = Mat4()
        val ar = arrayOf(
            floatArrayOf(1f, 2f, 3f, 4f),
            floatArrayOf(5f, 6f, 7f, 8f),
            floatArrayOf(9f, 10f, 11f, 12f),
            floatArrayOf(13f, 14f, 15f, 16f),
        )
        val br = arrayOf(
            floatArrayOf(2f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 3f),
            floatArrayOf(1f, 0f, 2f, 0f),
            floatArrayOf(0f, 4f, 0f, 1f),
        )
        for (r in 0 until 4) for (c in 0 until 4) {
            a.m[c * 4 + r] = ar[r][c]
            b.m[c * 4 + r] = br[r][c]
        }
        // hand computed A·B (row-major rows)
        val expected = arrayOf(
            floatArrayOf(5f, 18f, 7f, 10f),
            floatArrayOf(17f, 38f, 19f, 26f),
            floatArrayOf(29f, 58f, 31f, 42f),
            floatArrayOf(41f, 78f, 43f, 58f),
        )
        val p = Mat4().mul(a, b)
        for (r in 0 until 4) for (c in 0 until 4) {
            assertNear(expected[r][c], p.m[c * 4 + r], 0f, "row $r col $c")
        }
    }

    @Test
    fun mulAppliesTheRightOperandFirst() {
        val t = Mat4().setTRS(10f, 0f, 0f, Quat(), 1f)
        val r = Mat4().setTRS(0f, 0f, 0f, Quat().setAxisAngle(0f, 1f, 0f, PI_F / 2f), 1f)
        Mat4().mul(t, r).transform(0f, 0f, -1f, clip) // rotate, then translate
        assertVec(9f, 0f, 0f, Vec3(clip[0], clip[1], clip[2]), 1e-5f)
        Mat4().mul(r, t).transform(0f, 0f, -1f, clip) // translate, then rotate
        assertVec(-1f, 0f, -10f, Vec3(clip[0], clip[1], clip[2]), 1e-5f)
    }

    @Test
    fun setCopiesAllElements() {
        val a = Mat4().perspective(1f, 1.3f, 0.1f, 10f, 0.1f, 0.2f)
        val b = Mat4().set(a)
        assertContentEquals(a.m, b.m)
    }
}
