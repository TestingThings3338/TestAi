package com.starwake.core.gfx

import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.DVec3
import com.starwake.core.math.Mat4
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.tan

/**
 * Camera for camera-relative rendering: the view matrix only contains rotation, and every object is
 * positioned relative to [pos] (in doubles) before being converted to float. This keeps vertex
 * precision perfect no matter how far the player travels.
 */
class Camera {
    val pos = DVec3()
    val rot = Quat()
    var fovY = 62f * DEG2RAD
    var near = 0.5f
    var far = 40000f
    var shiftX = 0f
    var shiftY = 0f
    var width = 1
        private set
    var height = 1
        private set

    val proj = Mat4()
    val view = Mat4()
    val viewProj = Mat4()

    /** Same orientation, but for the far-away "sky space" (planet, rings). */
    val skyProj = Mat4()
    val skyViewProj = Mat4()

    val right = Vec3()
    val up = Vec3()
    val forward = Vec3()
    var tanHalfX = 1f
        private set
    var tanHalfY = 1f
        private set

    private val clip = FloatArray(4)

    fun update(width: Int, height: Int) {
        this.width = width
        this.height = height
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        proj.perspective(fovY, aspect, near, far, shiftX, shiftY)
        skyProj.perspective(fovY, aspect, 300f, 4000f, shiftX, shiftY)
        view.setViewRotation(rot)
        viewProj.mul(proj, view)
        skyViewProj.mul(skyProj, view)
        rot.rotate(1f, 0f, 0f, right)
        rot.rotate(0f, 1f, 0f, up)
        rot.rotate(0f, 0f, -1f, forward)
        tanHalfY = tan(fovY * 0.5f)
        tanHalfX = tanHalfY * aspect
    }

    /**
     * Projects a camera-relative point to screen pixels (origin top-left). Returns false when the
     * point is behind the camera.
     */
    fun project(rx: Float, ry: Float, rz: Float, out: Vec3): Boolean {
        viewProj.transform(rx, ry, rz, clip)
        val w = clip[3]
        if (w <= 0.01f) return false
        val nx = clip[0] / w
        val ny = clip[1] / w
        out.set((nx * 0.5f + 0.5f) * width, (0.5f - ny * 0.5f) * height, w)
        return true
    }
}
