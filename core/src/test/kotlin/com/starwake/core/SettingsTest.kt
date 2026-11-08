package com.starwake.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsTest {
    private fun fields(s: Settings) = listOf(
        s.quality, s.fpsMode, s.stickXRoll, s.pitchUpClimb, s.sensitivity, s.leftHanded, s.haptics, s.showFps, s.livery,
    )

    @Test
    fun defaults() {
        val s = Settings()
        assertEquals(listOf<Any>(2, 0, true, true, 1f, false, true, false, 0), fields(s))
        assertEquals(1f, s.renderScale)
        assertEquals(0, s.fpsLimit)
    }

    @Test
    fun serializeDeserializeRoundTrip() {
        val a = Settings().apply {
            quality = 0; fpsMode = 2; stickXRoll = false; pitchUpClimb = false; sensitivity = 1.35f
            leftHanded = true; haptics = false; showFps = true; livery = 3
        }
        val text = a.serialize()
        val b = Settings().apply { deserialize(text) }
        assertEquals(fields(a), fields(b), "round trip of '$text'")
        assertEquals(text, b.serialize())
        // and the defaults survive a round trip too
        val d = Settings()
        assertEquals(fields(d), fields(Settings().apply { deserialize(d.serialize()) }))
    }

    @Test
    fun roundTripsEveryLegalValue() {
        for (q in 0..2) for (f in 0..2) for (flag in listOf(true, false)) {
            val a = Settings().apply {
                quality = q; fpsMode = f; stickXRoll = flag; pitchUpClimb = !flag; leftHanded = flag
                haptics = !flag; showFps = flag; sensitivity = 0.5f + q * 0.25f; livery = f + q
            }
            assertEquals(fields(a), fields(Settings().apply { deserialize(a.serialize()) }))
        }
    }

    @Test
    fun serializedFormIsCompactKeyValuePairs() {
        val text = Settings().serialize()
        assertEquals("q=2;fps=0;roll=1;climb=1;sens=1.0;left=0;hap=1;showfps=0;livery=0", text)
        assertTrue(!text.contains(' ') && !text.contains('\n'))
    }

    @Test
    fun nullEmptyAndGarbageInputKeepTheCurrentValues() {
        val s = Settings().apply { quality = 1; livery = 2; sensitivity = 0.8f }
        val before = fields(s)
        for (junk in listOf(
            null, "", ";;;", "garbage", "=", "=1", "q", "q=", "q=abc", "fps=1.5", "sens=fast", "livery=x",
            "unknown=7", "q=1=2", "\u0000￿", "q=9999999999999", "sens=", "livery=-",
        )) {
            s.deserialize(junk)
            assertEquals(before, fields(s), "input '$junk' must not change anything")
        }
    }

    @Test
    fun partialAndMixedInputAppliesTheValidParts() {
        val s = Settings()
        s.deserialize("q=0;fps=oops;sens=1.2;bogus;livery=4;showfps=1;;")
        assertEquals(0, s.quality)
        assertEquals(0, s.fpsMode, "invalid value keeps the default")
        assertEquals(1.2f, s.sensitivity)
        assertEquals(4, s.livery)
        assertEquals(true, s.showFps)
        assertEquals(true, s.haptics, "missing keys keep the default")
        // later duplicates win
        s.deserialize("q=1;q=2")
        assertEquals(2, s.quality)
    }

    @Test
    fun booleansAreOnlyTrueForOne() {
        val s = Settings()
        s.deserialize("roll=true;climb=yes;hap=0;left=1;showfps=2")
        assertEquals(false, s.stickXRoll)
        assertEquals(false, s.pitchUpClimb)
        assertEquals(false, s.haptics)
        assertEquals(true, s.leftHanded)
        assertEquals(false, s.showFps)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val s = Settings()
        s.deserialize("q=7;fps=-3;sens=9.5;livery=-2")
        assertEquals(2, s.quality)
        assertEquals(0, s.fpsMode)
        assertEquals(1.5f, s.sensitivity)
        assertEquals(0, s.livery)
        s.deserialize("q=-1;fps=99;sens=0.01")
        assertEquals(0, s.quality)
        assertEquals(2, s.fpsMode)
        assertEquals(0.5f, s.sensitivity)
        s.deserialize("sens=Infinity")
        assertEquals(1.5f, s.sensitivity)
        s.deserialize("sens=-Infinity")
        assertEquals(0.5f, s.sensitivity)
    }

    @Test
    fun nanSensitivityIsRejected() {
        val s = Settings()
        s.deserialize("sens=NaN")
        assertTrue(s.sensitivity in 0.5f..1.5f, "sensitivity ${s.sensitivity}")
    }

    @Test
    fun derivedValuesFollowQualityAndFpsMode() {
        val s = Settings()
        assertEquals(listOf(0.7f, 0.85f, 1f), (0..2).map { s.quality = it; s.renderScale })
        assertEquals(listOf(0, 60, 30), (0..2).map { s.fpsMode = it; s.fpsLimit })
    }
}
