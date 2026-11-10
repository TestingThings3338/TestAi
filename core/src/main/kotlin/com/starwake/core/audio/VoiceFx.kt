package com.starwake.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The announcer's effect chains of `09` 8.4 (Roblox `Voice.Effects`) in plain Kotlin, applied once
 * to every line when it is loaded (the user's dry recording or a text-to-speech rendering):
 * - **Computer**: 3-band EQ (low -3 dB, mid 0, high +2 dB), flanger (depth 0.35, mix 0.18, 0.35 Hz),
 *   gain 1;
 * - **Control** (traffic control, "radio"): EQ (low -24, mid +3, high -16 dB), distortion 0.2, gain 1.3;
 * then the line is loudness-normalised ([TARGET_LUFS], ITU-R BS.1770 K-weighted and gated) with a
 * peak ceiling, so every line and both voices sit at the same level.
 *
 * Roblox bands: low < 400 Hz, mid 400 Hz - 4 kHz, high > 4 kHz; here split by 4th-order
 * Linkwitz-Riley crossovers (flat when all gains are 0 dB). Not real time; allocates its buffers.
 */
object VoiceFx {
    /** Bump when a chain changes: the text-to-speech cache is keyed by it. */
    const val CHAIN_VERSION = 1

    const val LOW_SPLIT = 400.0
    const val HIGH_SPLIT = 4000.0

    /** Loudness of every processed line (LUFS) and its sample-peak ceiling (-1 dBFS). */
    const val TARGET_LUFS = -22.0
    const val PEAK_CEILING = 0.891f

    /** The level the chain sees: every dry line is peak-normalised to this first. */
    const val INPUT_PEAK = 0.9f

    // Voice.Effects
    const val COMPUTER_LOW = -3f
    const val COMPUTER_MID = 0f
    const val COMPUTER_HIGH = 2f
    const val FLANGE_DEPTH = 0.35f
    const val FLANGE_MIX = 0.18f
    const val FLANGE_RATE = 0.35f
    const val CONTROL_LOW = -24f
    const val CONTROL_MID = 3f
    const val CONTROL_HIGH = -16f
    const val DISTORTION_LEVEL = 0.2f

    /** Flanger delay: [FLANGE_BASE_MS] plus depth x [FLANGE_SWEEP_MS], swept by a raised cosine. */
    const val FLANGE_BASE_MS = 1.0
    const val FLANGE_SWEEP_MS = 8.0

    /**
     * Runs the Computer or (with [radio]) the Control chain over a decoded line and normalises it:
     * 16-bit in (interleaved when [channels] is 2, mixed down), mono 16-bit out at the same [rate].
     */
    fun process(pcm: ShortArray, channels: Int, rate: Int, radio: Boolean): ShortArray {
        val x = toMonoFloat(pcm, channels)
        peakNormalize(x, INPUT_PEAK)
        if (radio) {
            equalizer(x, rate, CONTROL_LOW, CONTROL_MID, CONTROL_HIGH)
            distortion(x, DISTORTION_LEVEL)
            gain(x, VoiceConfig.CONTROL_VOLUME)
        } else {
            equalizer(x, rate, COMPUTER_LOW, COMPUTER_MID, COMPUTER_HIGH)
            flange(x, rate, FLANGE_DEPTH, FLANGE_MIX, FLANGE_RATE)
            gain(x, VoiceConfig.COMPUTER_VOLUME)
        }
        normalize(x, rate, TARGET_LUFS, PEAK_CEILING)
        fadeEdges(x, rate, 0.003f)
        return toShort(x)
    }

    fun toMonoFloat(pcm: ShortArray, channels: Int): FloatArray {
        val c = max(1, channels)
        val n = pcm.size / c
        val x = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            for (k in 0 until c) s += pcm[i * c + k]
            x[i] = s / (c * 32768f)
        }
        return x
    }

    fun toShort(x: FloatArray): ShortArray = ShortArray(x.size) { i ->
        val v = (x[i] * 32767f).toInt()
        v.coerceIn(-32768, 32767).toShort()
    }

    fun peak(x: FloatArray): Float {
        var p = 0f
        for (v in x) p = max(p, abs(v))
        return p
    }

    fun peakNormalize(x: FloatArray, target: Float) {
        val p = peak(x)
        if (p > 1e-6f) gain(x, target / p)
    }

    fun gain(x: FloatArray, g: Float) {
        for (i in x.indices) x[i] *= g
    }

    // ================================================================== biquads

    /** A biquad (transposed direct form II), coefficients normalised by a0. */
    class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun reset() {
            z1 = 0.0
            z2 = 0.0
        }

        fun process(v: Double): Double {
            val y = b0 * v + z1
            z1 = b1 * v - a1 * y + z2
            z2 = b2 * v - a2 * y
            return y
        }

        fun run(x: DoubleArray) {
            reset()
            for (i in x.indices) x[i] = process(x[i])
        }

        companion object {
            /** RBJ low-pass, [q] (Butterworth: 1/sqrt(2)). */
            fun lowPass(f: Double, rate: Int, q: Double = SQRT_HALF): Biquad {
                val w = 2.0 * PI * f / rate
                val alpha = kotlin.math.sin(w) / (2.0 * q)
                val c = cos(w)
                val a0 = 1.0 + alpha
                return Biquad((1.0 - c) / 2.0 / a0, (1.0 - c) / a0, (1.0 - c) / 2.0 / a0, -2.0 * c / a0, (1.0 - alpha) / a0)
            }

            /** RBJ high-pass. */
            fun highPass(f: Double, rate: Int, q: Double = SQRT_HALF): Biquad {
                val w = 2.0 * PI * f / rate
                val alpha = kotlin.math.sin(w) / (2.0 * q)
                val c = cos(w)
                val a0 = 1.0 + alpha
                return Biquad((1.0 + c) / 2.0 / a0, -(1.0 + c) / a0, (1.0 + c) / 2.0 / a0, -2.0 * c / a0, (1.0 - alpha) / a0)
            }

            const val SQRT_HALF = 0.7071067811865476
        }
    }

    /** Linkwitz-Riley 4th order: two cascaded Butterworth sections. */
    private fun lr4(x: DoubleArray, f: Double, rate: Int, high: Boolean) {
        val a = if (high) Biquad.highPass(f, rate) else Biquad.lowPass(f, rate)
        val b = if (high) Biquad.highPass(f, rate) else Biquad.lowPass(f, rate)
        a.run(x)
        b.run(x)
    }

    /**
     * Roblox EqualizerSoundEffect: three bands split at [LOW_SPLIT] and [HIGH_SPLIT] with
     * Linkwitz-Riley crossovers; the low band is phase-aligned through the high crossover's
     * all-pass, so the bands sum flat. Gains in dB. A split at or above Nyquist leaves the band out.
     */
    fun equalizer(x: FloatArray, rate: Int, lowDb: Float, midDb: Float, highDb: Float) {
        val n = x.size
        val gl = db(lowDb)
        val gm = db(midDb)
        val gh = db(highDb)
        val low = DoubleArray(n) { x[it].toDouble() }
        val rest = low.copyOf()
        lr4(low, LOW_SPLIT, rate, high = false)
        lr4(rest, LOW_SPLIT, rate, high = true)
        val mid = rest.copyOf()
        val high = rest
        if (HIGH_SPLIT < rate * 0.45) {
            lr4(mid, HIGH_SPLIT, rate, high = false)
            lr4(high, HIGH_SPLIT, rate, high = true)
            // the low band through the 4 kHz all-pass (LP + HP of the same LR4 pair)
            val lowLp = low.copyOf()
            val lowHp = low
            lr4(lowLp, HIGH_SPLIT, rate, high = false)
            lr4(lowHp, HIGH_SPLIT, rate, high = true)
            for (i in 0 until n) x[i] = ((lowLp[i] + lowHp[i]) * gl + mid[i] * gm + high[i] * gh).toFloat()
        } else {
            for (i in 0 until n) x[i] = (low[i] * gl + mid[i] * gm).toFloat()
        }
    }

    /**
     * Roblox FlangeSoundEffect: the dry signal plus [mix] of a copy delayed by a slowly swept time
     * (base + depth x sweep, raised cosine at [rateHz]), linear interpolation in the delay line.
     */
    fun flange(x: FloatArray, rate: Int, depth: Float, mix: Float, rateHz: Float) {
        val dry = x.copyOf()
        val base = FLANGE_BASE_MS * 0.001 * rate
        val sweep = depth * FLANGE_SWEEP_MS * 0.001 * rate
        for (i in x.indices) {
            val d = base + sweep * (0.5 - 0.5 * cos(2.0 * PI * rateHz * i / rate))
            val p = i - d
            val k = kotlin.math.floor(p).toInt()
            val f = (p - k).toFloat()
            val a = if (k >= 0) dry[k] else 0f
            val b = if (k + 1 >= 0) dry[k + 1] else 0f
            val delayed = a + (b - a) * f
            x[i] = (1f - mix) * dry[i] + mix * delayed
        }
    }

    /** The flanger's delay in samples at sample [i] (tests). */
    fun flangeDelay(i: Int, rate: Int, depth: Float, rateHz: Float): Double =
        FLANGE_BASE_MS * 0.001 * rate + depth * FLANGE_SWEEP_MS * 0.001 * rate * (0.5 - 0.5 * cos(2.0 * PI * rateHz * i / rate))

    /** Roblox DistortionSoundEffect: a tanh waveshaper with drive 1 + 9 x [level], unity at full scale. */
    fun distortion(x: FloatArray, level: Float) {
        val k = 1.0 + 9.0 * level
        val norm = tanh(k)
        for (i in x.indices) x[i] = (tanh(k * x[i]) / norm).toFloat()
    }

    private fun db(v: Float): Double = 10.0.pow(v / 20.0)

    // ================================================================== loudness (ITU-R BS.1770-4)

    /** Integrated loudness in LUFS of a mono signal (K-weighted, 400 ms blocks, -70 / -10 LU gates). */
    fun loudness(x: FloatArray, rate: Int): Double {
        val y = DoubleArray(x.size) { x[it].toDouble() }
        kWeighting(rate).forEach { it.run(y) }
        val block = (0.4 * rate).toInt()
        val hop = max(1, block / 4)
        val powers = ArrayList<Double>()
        if (y.size <= block) {
            var s = 0.0
            for (v in y) s += v * v
            powers.add(s / max(1, y.size))
        } else {
            var start = 0
            while (start + block <= y.size) {
                var s = 0.0
                for (i in start until start + block) s += y[i] * y[i]
                powers.add(s / block)
                start += hop
            }
        }
        fun lufs(p: Double) = -0.691 + 10.0 * log10(max(p, 1e-12))
        val absGated = powers.filter { lufs(it) > -70.0 }
        if (absGated.isEmpty()) return -70.0
        val rel = lufs(absGated.average()) - 10.0
        val gated = absGated.filter { lufs(it) > rel }
        return lufs((if (gated.isEmpty()) absGated else gated).average())
    }

    /** The K-weighting pre-filter (high shelf + high-pass) for any sample rate (BS.1770 stage 1 and 2). */
    fun kWeighting(rate: Int): List<Biquad> {
        val g = 3.999843853973347
        val f0 = 1681.974450955533
        val q = 0.7071752369554196
        var k = tan(PI * f0 / rate)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        val shelf = Biquad(
            (vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0,
        )
        val f1 = 38.13547087602444
        val q1 = 0.5003270373238773
        k = tan(PI * f1 / rate)
        a0 = 1.0 + k / q1 + k * k
        val hp = Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q1 + k * k) / a0)
        return listOf(shelf, hp)
    }

    /** Scales [x] to [targetLufs], less if its sample peak would pass [ceiling]. */
    fun normalize(x: FloatArray, rate: Int, targetLufs: Double, ceiling: Float) {
        val l = loudness(x, rate)
        var g = 10.0.pow((targetLufs - l) / 20.0).toFloat()
        val p = peak(x)
        if (p * g > ceiling && p > 0f) g = ceiling / p
        gain(x, g)
    }

    /** Short linear fades at both ends (no click when a line starts or stops). */
    fun fadeEdges(x: FloatArray, rate: Int, seconds: Float) {
        val n = minOf(x.size / 2, (seconds * rate).toInt())
        for (i in 0 until n) {
            val g = i.toFloat() / n
            x[i] *= g
            x[x.size - 1 - i] *= g
        }
    }

    /** RMS of [x] (tests). */
    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / max(1, to - from))
    }
}
