package com.starwake.core.hud

import com.starwake.core.ui.TextBuf
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The ShipUnits rules of 01-core-loop.md 2.11 and the examples of its 9.4 test list. */
class UnitsTest {
    private val buf = TextBuf(64)

    private fun dist(m: Double, upper: Boolean = false) = Units.distance(buf.clear(), m, upper).toString()
    private fun speed(v: Double, upper: Boolean = false) = Units.speed(buf.clear(), v, upper).toString()

    private fun speedParts(v: Double): Pair<String, String> {
        val unit = Units.speedParts(buf.clear(), v)
        return buf.toString() to unit
    }

    private fun distanceParts(m: Double): Pair<String, String> {
        val unit = Units.distanceParts(buf.clear(), m)
        return buf.toString() to unit
    }

    @Test
    fun distanceExamples() {
        assertEquals("316 m", dist(316.0))
        assertEquals("3.17 km", dist(3170.0))
        assertEquals("53.0 km", dist(53_000.0))
        assertEquals("828 km", dist(828_000.0))
        assertEquals("6.84 Mm", dist(6_840_000.0))
        assertEquals("53.0 Mm", dist(53_000_000.0))
        assertEquals("1,240 Mm", dist(1.24e9))
        assertEquals("27,000 Mm", dist(2.7e10), "the largest in-system distance at S = 3000")
    }

    @Test
    fun distanceBoundariesNeverRollOver() {
        val cases = listOf(
            999.4 to "999 m", 999.5 to "1.00 km", 9_994.9 to "9.99 km", 9_995.0 to "10.0 km",
            99_949.0 to "99.9 km", 99_950.0 to "100 km", 999_499.0 to "999 km", 999_500.0 to "1.00 Mm",
            9_994_999.0 to "9.99 Mm", 9_995_000.0 to "10.0 Mm", 99_949_999.0 to "99.9 Mm", 99_950_000.0 to "100 Mm",
        )
        for ((m, text) in cases) assertEquals(text, dist(m), "distance($m)")
        // no "1000 m", "10.00 km", "100.0 km", "1000 km", "10.00 Mm" or "100.0 Mm" anywhere on a fine sweep
        val forbidden = listOf("1000 m", "10.00 km", "100.0 km", "1000 km", "10.00 Mm", "100.0 Mm")
        var m = 1.0
        while (m < 1e9) {
            val t = dist(m)
            for (f in forbidden) assertTrue(!t.startsWith(f), "distance($m) = $t")
            m *= 1.0007
        }
    }

    @Test
    fun speedExamplesAndParts() {
        assertEquals("260" to Units.M_S, speedParts(260.0))
        assertEquals("1.24" to Units.KM_S, speedParts(1240.0))
        assertEquals("53.0" to Units.KM_S, speedParts(53_000.0))
        assertEquals("250" to Units.KM_S, speedParts(250_000.0))
        assertEquals("1.06" to Units.MM_S, speedParts(1_060_000.0))
        assertEquals("150.0" to Units.MM_S, speedParts(1.5e8), "the 0.5 c cap, never grouped")
        assertEquals("1.00" to Units.KM_S, speedParts(999.5))
        assertEquals("100" to Units.KM_S, speedParts(99_950.0))
        assertEquals("1.00" to Units.MM_S, speedParts(999_500.0))
        assertEquals("260 m/s", speed(260.0))
        assertEquals("53.0 km/s", speed(53_000.0))
        assertEquals("1.06 Mm/s", speed(1_060_000.0))
        assertEquals("260 M/S", speed(260.0, upper = true))
        assertEquals("1.06 Mm/s", speed(1_060_000.0, upper = true), "Mm/s stays mixed case in upper-case text")
    }

    @Test
    fun invalidAndNegativeValues() {
        assertEquals("--", dist(Double.NaN))
        assertEquals("--", dist(Double.POSITIVE_INFINITY))
        assertEquals("--" to "", distanceParts(Double.NaN))
        assertEquals("--" to Units.M_S, speedParts(Double.NaN))
        assertEquals("--" to Units.M_S, speedParts(Double.POSITIVE_INFINITY))
        assertEquals("0 m", dist(-12.0))
        assertEquals("0 m", dist(Double.NEGATIVE_INFINITY), "Lua does not catch -inf: it prints 0 m")
        assertEquals("0" to Units.M_S, speedParts(-5.0))
    }

    @Test
    fun upperCaseKeepsMegametres() {
        assertEquals("316 M" to "3.17 KM", dist(316.0, true) to dist(3170.0, true))
        assertEquals("6.84 Mm", dist(6_840_000.0, true))
        assertEquals("1,240 Mm", dist(1.24e9, true))
        fun up(s: String) = Units.upper(s, buf.clear()).toString()
        assertEquals("DISTANCE 6.84 Mm", up("distance 6.84 Mm"))
        assertEquals("SPEED 1.06 Mm/s", up("speed 1.06 mm/s"))
        assertEquals("1,240 Mm · 4.48 KM", up("1,240 Mm · 4.48 km"))
        assertEquals("5 MMX", up("5 mmx"), "only whole words")
        assertEquals("THE MM BAND", up("the mm band"), "only after a number")
    }

    @Test
    fun accelerationAndGrouping() {
        assertEquals("35.0 m/s²", Units.acceleration(buf.clear(), 35.0).toString())
        assertEquals("1,240,567", Units.grouped(buf.clear(), 1_240_567.0).toString())
        assertEquals("1,000", Units.grouped(buf.clear(), 999.5).toString())
        assertEquals("100,005", Units.grouped(buf.clear(), 100_005.0).toString())
        assertEquals("0", Units.grouped(buf.clear(), -3.0).toString())
        assertEquals("999", Units.grouped(buf.clear(), 999.0).toString())
    }

    @Test
    fun roundsInDoubleNotFloat() {
        // 6.845 km in Float is 6.8449998: Double keeps the Lua result of the double value
        assertEquals("6.85 km", dist(6_850.0))
        assertEquals("6.84 km", dist(6_844.9))
        assertEquals("2.00 Mm", dist(1_999_999.0))
    }

    @Test
    fun noAllocationPerCall() {
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        if (!mx.isThreadAllocatedMemorySupported) return
        mx.isThreadAllocatedMemoryEnabled = true
        val out = TextBuf(64)
        fun run(n: Int) {
            var m = 1.0
            for (i in 0 until n) {
                Units.distance(out.clear(), m)
                Units.speed(out.clear(), m * 0.3, upper = true)
                Units.distanceParts(out.clear(), -m)
                Units.speedParts(out.clear(), m)
                m *= 1.002
            }
        }
        run(10_000) // warm-up: class loading, JIT
        val before = mx.currentThreadAllocatedBytes
        run(10_000)
        val bytes = mx.currentThreadAllocatedBytes - before
        // the counter itself costs a few bytes per read; 40k calls allocating anything would be > 100 KB
        assertTrue(bytes < 4096, "Units allocated $bytes B over 40,000 calls")
    }
}
