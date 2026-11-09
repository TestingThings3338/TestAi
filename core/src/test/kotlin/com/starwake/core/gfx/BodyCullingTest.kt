package com.starwake.core.gfx

import com.starwake.core.math.Rng
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [BodyMath.offScreen]: the test BodyRenderer runs before choosing a quad or the full-screen triangle. */
class BodyCullingTest {
    // flight camera: 2400x1080, 52..64 degrees vertical
    private val ty = tan(26.0 * Math.PI / 180)
    private val tx = ty * 2400 / 1080
    private val halfDiag = atan(sqrt(tx * tx + ty * ty))

    @Test
    fun starGlowBehindTheCameraIsRejectedEvenWhenItWouldBeFullScreen() {
        // within ~5.5e8 m of the star the glow fades out 1.45 rad from its centre: wider than any
        // quad (1.3 rad), so it used to become a full-screen pass in every direction
        val fadeOut = 1.45
        assertTrue(BodyMath.offScreen(-1.0, fadeOut, halfDiag), "star straight behind")
        assertTrue(BodyMath.offScreen(cos(2.5), fadeOut, halfDiag), "star 143 degrees off the nose")
        assertFalse(BodyMath.offScreen(cos(2.2), fadeOut, halfDiag), "the glow still reaches the view's corner")
        assertFalse(BodyMath.offScreen(1.0, fadeOut, halfDiag), "star ahead")
    }

    @Test
    fun aConeWiderThanAHemisphereIsNeverRejected() {
        // angle + halfDiag >= PI: some pixel always lies inside the cone
        assertFalse(BodyMath.offScreen(-1.0, 2.5, halfDiag))
    }

    @Test
    fun rejectionIsConservative() {
        // whenever a cone is rejected, no direction inside the view rectangle lies in it
        val rng = Rng(11)
        var rejected = 0
        repeat(4000) {
            // cone axis anywhere on the sphere, the view looks down -Z
            var ax: Double
            var ay: Double
            var az: Double
            var l: Double
            do {
                ax = rng.signed().toDouble(); ay = rng.signed().toDouble(); az = rng.signed().toDouble()
                l = sqrt(ax * ax + ay * ay + az * az)
            } while (l < 0.1 || l > 1.0)
            ax /= l; ay /= l; az /= l
            val angle = rng.range(0.001f, 1.6f).toDouble()
            if (!BodyMath.offScreen(-az, angle, halfDiag)) return@repeat
            rejected++
            for (i in 0..16) {
                for (j in 0..16) {
                    val px = (i / 8.0 - 1.0) * tx
                    val py = (j / 8.0 - 1.0) * ty
                    val pl = sqrt(px * px + py * py + 1.0)
                    val c = (px * ax + py * ay - az) / pl
                    assertTrue(acos(c.coerceIn(-1.0, 1.0)) > angle, "pixel ($i, $j) inside a rejected cone")
                }
            }
        }
        assertTrue(rejected > 500, "only $rejected cones rejected")
    }
}
