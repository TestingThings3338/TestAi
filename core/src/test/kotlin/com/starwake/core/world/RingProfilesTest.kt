package com.starwake.core.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 128-step ring opacity profiles of Appendix D (`ShipVisualAssets.RingProfiles`, 9.4 VisualAssetsTest). */
class RingProfilesTest {
    @Test
    fun theProfilesDecodeAsTheLuaDoes() {
        assertEquals(RingProfiles.COUNT, RingProfiles.values.size)
        for (p in RingProfiles.values) {
            assertEquals(RingProfiles.STEPS, p.size)
            for (v in p) assertTrue(v in 0f..1f)
        }
        // two digits per step, value / 99
        val sat = RingProfiles.values[RingProfiles.SATURN]
        assertEquals(0f, sat[68], 1e-7f) // step 69 = "00"
        assertEquals(1f / 99f, sat[69], 1e-7f) // step 70 = "01"
        assertEquals(9f / 99f, sat[70], 1e-7f) // step 71 = "09"
        assertEquals(29f / 99f, sat[127], 1e-7f) // the last step "29"
    }

    @Test
    fun theFirstNonZeroStepIsTheRingsInnerEdge() {
        // Appendix D: Saturn step 69 (f 0.539) against inner/outer 0.546; Jupiter 107 against 0.843;
        // Uranus 105 against 0.820. The appendix counts from 0 (69/128 = 0.539): 1-based these are
        // steps 70, 108 and 106 of the verbatim strings
        assertEquals(70, RingProfiles.firstNonZeroStep(RingProfiles.values[RingProfiles.SATURN]))
        assertEquals(108, RingProfiles.firstNonZeroStep(RingProfiles.values[RingProfiles.JUPITER]))
        assertEquals(106, RingProfiles.firstNonZeroStep(RingProfiles.values[RingProfiles.URANUS]))
        val sol = WorldBuilder.sol()
        for ((name, step) in listOf("Saturn" to 70, "Jupiter" to 108, "Uranus" to 106)) {
            val b = sol.bodies[sol.bodyIndex(name)]
            val f = (step - 0.5) / 128.0
            assertEquals(b.ringInner / b.ringOuter, f, 0.01, name)
        }
    }

    @Test
    fun densityInterpolatesBetweenStepCentres() {
        val p = RingProfiles.values[RingProfiles.SATURN]
        // value i (1-based) sits at f = (i - 0.5)/128
        for (i in listOf(1, 60, 70, 100, 128)) assertEquals(p[i - 1].toDouble(), RingProfiles.densityAt(p, (i - 0.5) / 128.0), 1e-6)
        // halfway between steps 99 and 100
        assertEquals((p[98] + p[99]) / 2.0, RingProfiles.densityAt(p, 99.0 / 128.0), 1e-6)
        // the Cassini division: dense B ring (f 0.70..0.83), the gap after it, the A ring beyond
        val bRing = RingProfiles.densityAt(p, 0.78)
        val gap = (0..127).map { it to p[it] }.filter { it.first in 108..116 }.minOf { it.second }
        assertTrue(bRing > 0.8, "B ring opacity $bRing")
        assertTrue(gap < 0.11f, "the Cassini division is almost empty: $gap")
        assertTrue(RingProfiles.densityAt(p, 0.9) > 0.5, "A ring")
        // clamped at both ends
        assertEquals(p[0].toDouble(), RingProfiles.densityAt(p, 0.0), 1e-9)
        assertEquals(p[127].toDouble(), RingProfiles.densityAt(p, 1.0), 1e-9)
    }
}
