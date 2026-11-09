package com.starwake.core.gfx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cloud deck's field ([CloudField]): its cover per profile, and the CPU copy of the shaders' lookup. */
class CloudFieldTest {
    companion object {
        private val field: CloudField by lazy { CloudField().also { it.build(2) } }
    }

    /** Share of the sphere (area weighted) where a deck of [cover] is more than half opaque, from the map alone. */
    private fun covered(cover: Float): Double {
        val thr = CloudField.threshold(cover)
        var sum = 0.0
        var total = 0.0
        for (y in 0 until CloudField.MAP_HEIGHT) {
            val w = cos(((y + 0.5) / CloudField.MAP_HEIGHT - 0.5) * PI)
            for (x in 0 until CloudField.MAP_WIDTH step 4) {
                val v = (field.map[y * CloudField.MAP_WIDTH + x].toInt() and 255) / 255f
                if (CloudField.alpha(v, thr) > 0.5f) sum += w
                total += w
            }
        }
        return sum / total
    }

    @Test
    fun theThresholdFallsAsTheCoverGrows() {
        var last = Float.MAX_VALUE
        for (c in listOf(0.1f, 0.2f, 0.42f, 0.56f, 0.72f, 0.92f)) {
            val t = CloudField.threshold(c)
            assertTrue(t < last, "threshold($c) = $t")
            last = t
        }
    }

    @Test
    fun theProfilesCoversGiveBrokenCloudOverTheEarthAndAClosedDeckOverVenus() {
        assertTrue(field.ready)
        val earth = covered(0.56f)
        val mars = covered(0.2f)
        val venus = covered(0.92f)
        assertTrue(earth in 0.2..0.6, "Earth: $earth of the sky under cloud")
        assertTrue(mars < 0.2 && mars < earth, "Mars: $mars")
        assertTrue(venus > 0.9, "Venus: $venus")
    }

    @Test
    fun theOpacityRampIsTheShaders() {
        val thr = 0.5f
        assertEquals(0f, CloudField.alpha(thr - CloudField.EDGE_LOW, thr), 0f)
        assertEquals(1f, CloudField.alpha(thr + CloudField.EDGE_HIGH, thr), 0f)
        assertTrue(CloudField.alpha(thr, thr) in 0.05f..0.3f)
    }

    @Test
    fun theFieldOnTheCpuIsTheMapPlusBoundedDetail() {
        val r = 6_362_500.0
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (k in 0 until 400) {
            val lat = (k * 0.37) % 2.6 - 1.3
            val lon = (k * 1.13) % 6.2 - 3.1
            val nx = cos(lat) * cos(lon)
            val ny = sin(lat)
            val nz = cos(lat) * sin(lon)
            val v = field.value(nx, ny, nz, nx * r, ny * r, nz * r)
            lo = minOf(lo, v)
            hi = maxOf(hi, v)
            // the same point again: the same value
            assertEquals(v, field.value(nx, ny, nz, nx * r, ny * r, nz * r), 0f)
        }
        val swing = (CloudField.BANK_GAIN + CloudField.BILLOW_GAIN + CloudField.PUFF_GAIN) * 0.5f
        assertTrue(lo >= -swing && hi <= 1f + swing, "field range $lo .. $hi")
        assertTrue(hi - lo > 0.5f, "the weather varies: $lo .. $hi")
    }

    @Test
    fun theNoiseTilesWithItsPeriod() {
        val p = CloudField.BILLOW_PERIOD
        val a = field.value(0.0, 1.0, 0.0, 1234.0, 6_362_500.0, -777.0)
        // one bank period along the bank's own first axis is not a period of the billows: only the
        // exact common shifts repeat, and a whole number of billow periods changes the bank octave
        val b = field.value(0.0, 1.0, 0.0, 1234.0 + p, 6_362_500.0, -777.0)
        assertTrue(kotlin.math.abs(a - b) <= CloudField.BANK_GAIN, "one billow period on: $a -> $b")
    }
}
