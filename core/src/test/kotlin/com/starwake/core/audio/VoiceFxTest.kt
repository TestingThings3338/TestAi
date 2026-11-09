package com.starwake.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The voice chains of `09` 8.4: the 3-band equaliser measured with sines across the bands (both
 * chains, and flat at 0 dB), the flanger's taps and sweep, the distortion's shape, the BS.1770
 * loudness and the whole line processing.
 */
class VoiceFxTest {
    private fun sine(f: Double, seconds: Double, rate: Int, amp: Double = 0.25): FloatArray =
        FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * f * it / rate)).toFloat() }

    /** Gain in dB the equaliser applies to a sine of [f] Hz (steady state, edges skipped). */
    private fun eqGain(f: Double, rate: Int, low: Float, mid: Float, high: Float): Double {
        val x = sine(f, 1.0, rate)
        val y = x.copyOf()
        VoiceFx.equalizer(y, rate, low, mid, high)
        val from = rate / 4
        return 20 * log10(VoiceFx.rms(y, from, x.size) / VoiceFx.rms(x, from, x.size))
    }

    @Test
    fun theEqualiserBandsMeasuredWithSines() {
        for (rate in intArrayOf(48000, 24000, 22050)) {
            // Computer: low -3, mid 0, high +2 dB; Control: low -24, mid +3, high -16 dB
            assertEquals(-3.0, eqGain(80.0, rate, -3f, 0f, 2f), 0.3, "computer low @$rate")
            assertEquals(0.0, eqGain(1300.0, rate, -3f, 0f, 2f), 0.3, "computer mid @$rate")
            assertEquals(-24.0, eqGain(60.0, rate, -24f, 3f, -16f), 1.0, "control low @$rate")
            assertEquals(3.0, eqGain(1300.0, rate, -24f, 3f, -16f), 0.3, "control mid @$rate")
            // near Nyquist only the direction can be measured (the 4 kHz crossover is an octave away)
            assertTrue(eqGain(rate * 0.37, rate, -24f, 3f, -16f) < -10.0, "control high cut @$rate")
            assertTrue(eqGain(rate * 0.37, rate, -3f, 0f, 2f) > 1.0, "computer high lift @$rate")
        }
        // the high band well inside it (48 kHz): within 1 dB of the setting
        assertEquals(2.0, eqGain(14000.0, 48000, -3f, 0f, 2f), 0.4, "computer high")
        assertEquals(-16.0, eqGain(14000.0, 48000, -24f, 3f, -16f), 1.0, "control high")
        // all 0 dB: flat across a sweep (Linkwitz-Riley bands sum to an all-pass)
        for (f in doubleArrayOf(50.0, 150.0, 400.0, 1000.0, 2500.0, 4000.0, 7000.0, 12000.0)) {
            assertEquals(0.0, eqGain(f, 48000, 0f, 0f, 0f), 0.15, "flat at $f Hz")
        }
        // the crossover points sit between the band gains
        val at400 = eqGain(400.0, 48000, -24f, 3f, -16f)
        assertTrue(at400 > -24 && at400 < 3, "400 Hz between the bands: $at400")
    }

    @Test
    fun theFlangerAddsASweptDelayedCopy() {
        val rate = 24000
        val x = FloatArray(rate * 2)
        x[100] = 1f
        val y = x.copyOf()
        VoiceFx.flange(y, rate, 0.35f, 0.18f, 0.35f)
        assertEquals(0.82f, y[100], 1e-6f, "the dry tap: 1 - mix")
        // the delayed tap lands at the sweep's delay for that time, split by linear interpolation
        val d = VoiceFx.flangeDelay(100, rate, 0.35f, 0.35f)
        val k = (100 + d).toInt()
        val wet = y[k] + y[k + 1] + (if (k + 2 < y.size) y[k + 2] else 0f)
        assertEquals(0.18f, wet, 0.02f, "the wet tap sums to the mix")
        // the delay sweeps from base to base + depth x sweep at 0.35 Hz
        val lo = VoiceFx.flangeDelay(0, rate, 0.35f, 0.35f)
        val hi = VoiceFx.flangeDelay((rate / 0.35 / 2).toInt(), rate, 0.35f, 0.35f)
        assertEquals(VoiceFx.FLANGE_BASE_MS * rate / 1000, lo, 1e-6)
        assertEquals((VoiceFx.FLANGE_BASE_MS + 0.35 * VoiceFx.FLANGE_SWEEP_MS) * rate / 1000, hi, 0.01)
        // a steady sine comes out with its level within the comb's range
        val s = sine(700.0, 2.0, rate)
        val t = s.copyOf()
        VoiceFx.flange(t, rate, 0.35f, 0.18f, 0.35f)
        val r = VoiceFx.rms(t) / VoiceFx.rms(s)
        assertTrue(r in 0.64..1.0, "level ratio $r")
    }

    @Test
    fun theDistortionIsAnOddSoftClipperThatAddsHarmonics() {
        val levels = floatArrayOf(-1f, -0.5f, -0.1f, 0f, 0.1f, 0.5f, 1f)
        val x = levels.copyOf()
        VoiceFx.distortion(x, 0.2f)
        assertEquals(1f, x[6], 1e-6f, "unity at full scale")
        assertEquals(-1f, x[0], 1e-6f)
        assertEquals(0f, x[3], 0f)
        for (i in levels.indices) assertEquals(-x[i], x[levels.size - 1 - i], 1e-6f, "odd")
        assertTrue(x[4] > 0.2f, "small signals are lifted (drive 2.8): ${x[4]}")
        for (i in 1 until x.size) assertTrue(x[i] > x[i - 1], "monotonic")
        // harmonics: the 3rd harmonic of a 500 Hz sine grows with the level
        fun third(level: Float): Double {
            val rate = 24000
            val s = sine(500.0, 1.0, rate, 0.9)
            VoiceFx.distortion(s, level)
            var re = 0.0
            var im = 0.0
            for (i in s.indices) {
                re += s[i] * kotlin.math.cos(2 * PI * 1500 * i / rate)
                im += s[i] * sin(2 * PI * 1500 * i / rate)
            }
            return kotlin.math.sqrt(re * re + im * im) / s.size
        }
        assertTrue(third(0.6f) > third(0.2f) && third(0.2f) > third(0.01f), "THD rises with the level")
    }

    @Test
    fun loudnessFollowsBs1770() {
        // a -20 dBFS 997 Hz sine reads -23.0 LUFS (mono), at any rate
        for (rate in intArrayOf(48000, 44100, 24000)) {
            val x = sine(997.0, 3.0, rate, 0.1)
            assertEquals(-23.0, VoiceFx.loudness(x, rate), 0.15, "rate $rate")
        }
        // silence around it is gated out (without the gates 4 s of silence would pull it to -27.8)
        val rate = 48000
        val x = FloatArray(rate * 6)
        val s = sine(997.0, 2.0, rate, 0.1)
        System.arraycopy(s, 0, x, rate * 2, s.size)
        val l = VoiceFx.loudness(x, rate)
        assertTrue(l > -24.0 && l < -22.9, "gated: $l")
        // and normalising moves it to the target
        val y = sine(997.0, 2.0, rate, 0.3)
        VoiceFx.normalize(y, rate, -30.0, 0.9f)
        assertEquals(-30.0, VoiceFx.loudness(y, rate), 0.15)
    }

    @Test
    fun aLineThroughEitherChainLandsAtTheTargetUnderTheCeiling() {
        val rate = 22050
        // a voice-like test signal: 180 Hz buzz with formant-ish partials, syllable envelope
        val n = rate * 2
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val env = 0.5 + 0.5 * sin(2 * PI * 3.0 * t)
            var v = 0.0
            for (k in 1..20) v += sin(2 * PI * 180.0 * k * t) / k * (if (k in 3..6) 2.0 else 1.0)
            (v * env * 4000).toInt().coerceIn(-32768, 32767).toShort()
        }
        for (radio in listOf(false, true)) {
            val out = VoiceFx.process(pcm, 1, rate, radio)
            assertEquals(pcm.size, out.size)
            val f = VoiceFx.toMonoFloat(out, 1)
            val peak = VoiceFx.peak(f)
            assertTrue(peak <= VoiceFx.PEAK_CEILING + 1e-3f, "peak $peak")
            val l = VoiceFx.loudness(f, rate)
            // at the target unless the ceiling held it lower
            assertTrue(abs(l - VoiceFx.TARGET_LUFS) < 0.5 || (l < VoiceFx.TARGET_LUFS && peak > VoiceFx.PEAK_CEILING - 0.01f), "radio=$radio loudness $l")
            assertEquals(0f, f[0], 1e-3f, "faded in")
        }
        // stereo input is mixed down
        val st = ShortArray(rate * 2) { if (it % 2 == 0) 8000 else -8000 }
        assertEquals(rate, VoiceFx.process(st, 2, rate, false).size)
    }
}
