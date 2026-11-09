package com.starwake.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsTest {
    private fun fields(s: Settings) = listOf(
        s.quality, s.fpsMode, s.stickXRoll, s.pitchUpClimb, s.sensitivity, s.leftHanded, s.haptics, s.showFps, s.ship,
        s.fovDeg, s.cameraShake, s.chaseView,
    )

    @Test
    fun defaults() {
        val s = Settings()
        // 60 fps by default (DECISIONS 21a); FOV 80 and camera shake 1 as Roblox; the chase view off
        assertEquals(listOf<Any>(2, 1, true, true, 1f, false, true, false, 0, 80f, 1f, false), fields(s))
        assertEquals(1f, s.renderScale)
        assertEquals(60, s.fpsLimit)
    }

    @Test
    fun fovAndShakeClampAndSnapLikeTheRobloxMenu() {
        val s = Settings()
        s.fovDeg = 59.6f
        assertEquals(60f, s.fovDeg)
        s.fovDeg = 80.4f
        assertEquals(80f, s.fovDeg)
        s.fovDeg = 140f
        assertEquals(100f, s.fovDeg)
        s.fovDeg = Float.NaN
        assertEquals(80f, s.fovDeg, "NaN falls back to the default")
        s.cameraShake = 0.33f
        assertEquals(0.35f, s.cameraShake)
        s.cameraShake = 0.30000000000000004f
        assertEquals(0.3f, s.cameraShake)
        s.cameraShake = -1f
        assertEquals(0f, s.cameraShake)
        assertEquals(73f, Settings.snap(72.5f, 60f, 100f, 1f, 80f), "halves round away from zero (Luau)")
        assertEquals(3f, Settings.snap(2.5f, 0f, 10f, 1f, 0f))
    }

    @Test
    fun serializeDeserializeRoundTrip() {
        val a = Settings().apply {
            quality = 0; fpsMode = 2; stickXRoll = false; pitchUpClimb = false; sensitivity = 1.35f
            leftHanded = true; haptics = false; showFps = true; ship = 3; fovDeg = 72f; cameraShake = 0.35f; chaseView = true
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
                haptics = !flag; showFps = flag; sensitivity = 0.5f + q * 0.25f; ship = f + q
                fovDeg = 60f + q * 20f; cameraShake = f * 0.45f; chaseView = flag
            }
            assertEquals(fields(a), fields(Settings().apply { deserialize(a.serialize()) }))
        }
    }

    @Test
    fun serializedFormIsCompactKeyValuePairs() {
        val text = Settings().serialize()
        assertEquals("q=2;fps=1;roll=1;climb=1;sens=1.0;left=0;hap=1;showfps=0;ship=0;fov=80;shake=1;chase=0;vol=1;fx=1;mus=1;music=1", text)
        assertTrue(!text.contains(' ') && !text.contains('\n'))
    }

    @Test
    fun theSoundSettingsRoundTripAndSnapLikeTheRobloxSliders() {
        val a = Settings().apply { masterVolume = 0.7f; effectsVolume = 0.33f; musicVolume = 0f; musicOn = false }
        assertEquals(0.35f, a.effectsVolume, "5 % steps (ShipSettings EffectsVolume)")
        val b = Settings().apply { deserialize(a.serialize()) }
        assertEquals(listOf(0.7f, 0.35f, 0f, false), listOf(b.masterVolume, b.effectsVolume, b.musicVolume, b.musicOn))
        // old saves without the keys keep the defaults; junk is ignored
        val c = Settings().apply { deserialize("q=1;vol=loud;fx=NaN;mus=2;music=x") }
        assertEquals(listOf(1f, 1f, 1f, false), listOf(c.masterVolume, c.effectsVolume, c.musicVolume, c.musicOn))
    }

    @Test
    fun nullEmptyAndGarbageInputKeepTheCurrentValues() {
        val s = Settings().apply { quality = 1; ship = 2; sensitivity = 0.8f }
        val before = fields(s)
        for (junk in listOf(
            null, "", ";;;", "garbage", "=", "=1", "q", "q=", "q=abc", "fps=1.5", "sens=fast", "ship=x",
            "unknown=7", "q=1=2", "\u0000￿", "q=9999999999999", "sens=", "ship=-", "fov=wide", "shake=", "fov=NaN",
        )) {
            s.deserialize(junk)
            assertEquals(before, fields(s), "input '$junk' must not change anything")
        }
    }

    @Test
    fun partialAndMixedInputAppliesTheValidParts() {
        val s = Settings()
        s.deserialize("q=0;fps=oops;sens=1.2;bogus;ship=4;showfps=1;;")
        assertEquals(0, s.quality)
        assertEquals(1, s.fpsMode, "invalid value keeps the default (60 FPS)")
        assertEquals(1.2f, s.sensitivity)
        assertEquals(4, s.ship)
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
        s.deserialize("q=7;fps=-3;sens=9.5;ship=-2")
        assertEquals(2, s.quality)
        assertEquals(0, s.fpsMode)
        assertEquals(1.5f, s.sensitivity)
        assertEquals(0, s.ship)
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
    fun newKeysOutOfRangeAreClampedAndSnapped() {
        val s = Settings()
        s.deserialize("fov=12;shake=7")
        assertEquals(60f, s.fovDeg)
        assertEquals(1f, s.cameraShake)
        s.deserialize("fov=91.6;shake=0.42;chase=1")
        assertEquals(92f, s.fovDeg)
        assertEquals(0.4f, s.cameraShake)
        assertEquals(true, s.chaseView)
    }

    @Test
    fun derivedValuesFollowQualityAndFpsMode() {
        val s = Settings()
        assertEquals(listOf(0.7f, 0.85f, 1f), (0..2).map { s.quality = it; s.renderScale })
        assertEquals(listOf(0, 60, 30), (0..2).map { s.fpsMode = it; s.fpsLimit })
    }
}
