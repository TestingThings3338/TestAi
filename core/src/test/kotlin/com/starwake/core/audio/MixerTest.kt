package com.starwake.core.audio

import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The software mixer and its lock-free front ([MixerAudioOut]): no allocation per burst, the
 * limiter's ceiling, the rate with linear interpolation, sample-rate conversion, loop wraps, start
 * offsets, gain ramps, stops and pauses, buses, and the Roblox Sound semantics the game reads back.
 */
class MixerTest {
    /** A synchronous output: the test renders bursts itself. */
    private class TestOut : MixerAudioOut() {
        val buf = FloatArray(4096 * 2)

        override fun load(sound: Int, path: String, stream: Boolean) {}

        fun render(frames: Int): FloatArray {
            renderBurst(buf, frames)
            return buf
        }
    }

    private fun sine(freq: Double, seconds: Double, rate: Int, amp: Double = 0.5): Pcm {
        val n = (seconds * rate).toInt()
        return Pcm(ShortArray(n) { (sin(2 * PI * freq * it / rate) * amp * 32767).toInt().toShort() }, 1, rate)
    }

    /** Collects [frames] stereo frames in bursts of [burst]; returns the left channel. */
    private fun capture(out: TestOut, frames: Int, burst: Int = 256): FloatArray {
        val l = FloatArray(frames)
        var done = 0
        while (done < frames) {
            val n = minOf(burst, frames - done)
            val b = out.render(n)
            for (i in 0 until n) l[done + i] = b[i * 2]
            done += n
        }
        return l
    }

    private fun zeroCrossings(x: FloatArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from + 1 until to) if ((x[i - 1] < 0f) != (x[i] < 0f)) c++
        return c
    }

    @Test
    fun rateAndSampleRateConversionKeepPitchAndLevel() {
        val out = TestOut()
        out.publish(1, sine(1000.0, 2.0, 32000))
        out.play(0, 1, 1f, 1f, true, 0f, Bus.SFX)
        out.mixer.snapBuses()
        val x = capture(out, 48000)
        // 1 kHz at 32 kHz played at 48 kHz: still 1 kHz (2000 zero crossings a second)
        val zc = zeroCrossings(x, 4800, 48000)
        assertTrue(abs(zc - 2000 * 0.9) < 6, "1 kHz after the rate conversion: $zc crossings in 0.9 s")
        // level: 0.5 x mono pan 0.707 (headroom 1)
        var peak = 0f
        for (i in 4800 until 48000) peak = maxOf(peak, abs(x[i]))
        assertEquals(0.5f * Mixer.MONO_PAN, peak, 0.01f)
        // playback rate 1.5: 1.5 kHz
        out.set(0, 1f, 1.5f)
        val y = capture(out, 48000)
        val zc2 = zeroCrossings(y, 4800, 48000)
        assertTrue(abs(zc2 - 3000 * 0.9) < 8, "rate 1.5: $zc2")
    }

    @Test
    fun aLoopWrapsWithoutAStep() {
        val out = TestOut()
        // 100 Hz over exactly 0.5 s at 32 kHz: a whole number of cycles, a seamless loop
        out.publish(2, sine(100.0, 0.5, 32000, 0.8))
        out.play(3, 2, 1f, 1f, true, 0.45f, Bus.SFX)
        out.mixer.snapBuses()
        val x = capture(out, 48000)
        var worst = 0f
        for (i in 300 until x.size) worst = maxOf(worst, abs(x[i] - x[i - 1]))
        // the steepest step of a 100 Hz sine at 0.8 x 0.707 is 2 pi 100 / 48000 x 0.566 = 0.0074
        assertTrue(worst < 0.009f, "largest step $worst (no seam)")
        assertTrue(out.isPlaying(3))
    }

    @Test
    fun startOffsetAndPositionAndTheEndOfAOneShot() {
        val out = TestOut()
        out.publish(4, sine(440.0, 1.0, 48000))
        out.play(5, 4, 1f, 1f, false, 0.25f, Bus.SFX)
        assertTrue(out.isPlaying(5), "playing at once (Roblox Play)")
        assertEquals(0.25f, out.position(5), "the start offset before the first burst")
        capture(out, 24000)
        assertEquals(0.75f, out.position(5), 0.01f)
        capture(out, 11000)
        assertTrue(out.isPlaying(5))
        capture(out, 4000)
        assertFalse(out.isPlaying(5), "ended")
        assertFalse(out.isActive(5))
    }

    @Test
    fun aOneShotThatIsNotLoadedIsDroppedAndALoopWaitsForItsSound() {
        val out = TestOut()
        out.play(6, 7, 1f, 1f, false, 0f, Bus.SFX)
        capture(out, 256)
        assertFalse(out.isPlaying(6), "dropped, never played late")
        out.play(0, 8, 1f, 1f, true, 0f, Bus.SFX)
        out.mixer.snapBuses()
        val silent = capture(out, 1024)
        assertTrue(silent.all { it == 0f }, "waiting in silence")
        assertTrue(out.isPlaying(0))
        out.publish(8, sine(200.0, 1.0, 32000))
        val x = capture(out, 4800)
        assertTrue(x.any { abs(it) > 0.1f }, "the loop starts once its sound arrives")
    }

    @Test
    fun gainRampsOverABurstAndStopFades() {
        val out = TestOut()
        val dc = Pcm(ShortArray(48000) { 16384 }, 1, 48000) // 0.5
        out.publish(9, dc)
        out.play(1, 9, 1f, 1f, true, 0f, Bus.SFX)
        out.mixer.snapBuses()
        val first = capture(out, 256)
        // from silence to the gain in one burst: a ramp, never a jump
        assertEquals(0f, first[0], 0.01f)
        for (i in 1 until 256) assertTrue(first[i] - first[i - 1] < 0.01f, "ramp step at $i")
        assertEquals(0.5f * Mixer.MONO_PAN, first[255], 0.005f)
        out.set(1, 0.2f, 1f)
        val down = capture(out, 256)
        assertEquals(0.2f * 0.5f * Mixer.MONO_PAN, down[255], 0.003f)
        for (i in 1 until 256) assertTrue(abs(down[i] - down[i - 1]) < 0.01f)
        out.stop(1)
        assertFalse(out.isPlaying(1), "stopped for the game at once")
        val fade = capture(out, 256)
        assertTrue(fade[0] > 0.05f && fade[255] < 0.001f, "the mixer fades it over a burst")
        assertTrue(capture(out, 256).all { it == 0f })
    }

    @Test
    fun pauseHoldsThePositionAndResumeContinues() {
        val out = TestOut()
        out.publish(10, sine(300.0, 2.0, 48000))
        out.play(2, 10, 1f, 1f, false, 0f, Bus.MUSIC)
        capture(out, 24000)
        val p = out.position(2)
        out.pause(2)
        assertFalse(out.isPlaying(2), "IsPlaying is false while paused")
        assertTrue(out.isActive(2))
        capture(out, 24000)
        assertEquals(p, out.position(2), 1e-4f, "held")
        out.resume(2)
        capture(out, 12000)
        assertEquals(p + 0.25f, out.position(2), 0.01f)
    }

    @Test
    fun busesScaleSmoothAndPause() {
        val out = TestOut()
        val dc = Pcm(ShortArray(96000) { 16384 }, 1, 48000)
        out.publish(11, dc)
        out.play(0, 11, 1f, 1f, true, 0f, Bus.SFX)
        out.play(1, 11, 1f, 1f, true, 0f, Bus.MUSIC)
        out.mixer.snapBuses()
        capture(out, 512)
        out.setBusGain(Bus.SFX, 0f)
        val x = capture(out, 256)
        assertTrue(x[255] > 0.5f * Mixer.MONO_PAN * 0.95f, "the bus fades (30 ms), it does not jump")
        val y = capture(out, 48000)
        assertEquals(0.5f * Mixer.MONO_PAN, y[47999], 0.005f, "only the MUSIC voice is left")
        out.setBusPaused(Bus.MUSIC, true)
        val z = capture(out, 512)
        assertTrue(abs(z[511]) < 1e-4f, "paused bus silent")
        val pos = out.position(1)
        capture(out, 4800)
        assertEquals(pos, out.position(1), 1e-5f, "a paused bus holds its voices")
        out.setBusPaused(Bus.MUSIC, false)
        capture(out, 4800)
        assertTrue(out.position(1) > pos)
    }

    @Test
    fun theLimiterNeverPassesTheCeiling() {
        val out = TestOut()
        val loud = Pcm(ShortArray(48000) { i -> if ((i / 40) % 2 == 0) 32767 else -32768 }, 1, 48000)
        out.publish(12, loud)
        for (s in 0 until SoundBank.SLOTS) out.play(s, 12, 4f, 1f + s * 0.01f, true, s * 0.003f, Bus.SFX)
        out.mixer.headroom = 2f
        out.mixer.snapBuses()
        var peak = 0f
        repeat(200) {
            val b = out.render(256)
            for (i in 0 until 512) peak = maxOf(peak, abs(b[i]))
        }
        assertTrue(peak <= Mixer.CEILING + 1e-6f, "peak $peak above the ceiling")
        assertTrue(peak < 1f, "never 0 dBFS")
        assertTrue(out.mixer.limiting > 0.5f, "and it was limiting hard")
        // quiet material passes untouched (below the knee)
        val q = TestOut()
        q.publish(1, sine(500.0, 1.0, 48000, 0.3))
        q.play(0, 1, 1f, 1f, true, 0f, Bus.SFX)
        q.mixer.snapBuses()
        val x = capture(q, 9600)
        var p2 = 0f
        for (i in 4800 until 9600) p2 = maxOf(p2, abs(x[i]))
        assertEquals(0.3f * Mixer.MONO_PAN, p2, 0.003f)
    }

    @Test
    fun aStereoSoundKeepsItsChannels() {
        val out = TestOut()
        val n = 4800
        val data = ShortArray(n * 2) { i -> if (i % 2 == 0) 8192 else -8192 }
        out.publish(13, Pcm(data, 2, 48000))
        out.play(0, 13, 1f, 1f, true, 0f, Bus.MUSIC)
        out.mixer.snapBuses()
        capture(out, 256)
        val b = out.render(256)
        assertEquals(0.25f, b[200], 1e-3f)
        assertEquals(-0.25f, b[201], 1e-3f)
    }

    @Test
    fun renderingWithEverySlotBusyAllocatesNothing() {
        val out = TestOut()
        out.publish(1, sine(220.0, 3.0, 32000))
        out.publish(2, Pcm(ShortArray(44100 * 2 * 3) { (it % 200 - 100).toShort() }, 2, 44100))
        for (s in 0 until SoundBank.SLOTS) out.play(s, if (s == 35 || s == 36) 2 else 1, 0.1f, 0.8f + s * 0.01f, true, 0f, Bus.SFX)
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        repeat(500) { // warm up (JIT)
            for (s in 0 until SoundBank.SLOTS) out.set(s, 0.1f + (it % 7) * 0.01f, 0.8f + s * 0.01f)
            out.render(256)
        }
        val a = mx.currentThreadAllocatedBytes
        val t0 = System.nanoTime()
        val bursts = 4000
        for (k in 0 until bursts) {
            for (s in 0 until SoundBank.SLOTS) out.set(s, 0.1f + (k % 7) * 0.01f, 0.8f + s * 0.01f)
            if (k % 50 == 0) {
                out.play(9, 1, 0.5f, 1.1f, false, 0f, Bus.SFX)
                out.stop(10)
                out.play(10, 1, 0.3f, 1f, true, 0f, Bus.SFX)
            }
            out.render(256)
        }
        val t1 = System.nanoTime()
        val bytes = mx.currentThreadAllocatedBytes - a
        println("mixer: 40 busy slots, %.1f us per 256-frame burst (%.2f %% of its 5.33 ms), %d B allocated over %d bursts"
            .format((t1 - t0) / 1e3 / bursts, (t1 - t0) / 1e6 / bursts / (256 / 48.0) * 100, bytes, bursts))
        assertTrue(bytes < 1024, "$bytes B allocated by $bursts bursts")
    }
}
