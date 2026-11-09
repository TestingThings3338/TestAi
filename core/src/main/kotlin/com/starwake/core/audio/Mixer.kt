package com.starwake.core.audio

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.exp
import kotlin.math.tanh

/** Decoded sound: 16-bit samples, interleaved when [channels] is 2, at [rate] Hz. Immutable once published. */
class Pcm(val data: ShortArray, val channels: Int, val rate: Int) {
    val frames: Int = data.size / channels
    val seconds: Float get() = frames.toFloat() / rate
}

/**
 * The software mixer shared by Android and the desktop harness (`09` 8.4): fixed voice slots, each
 * with a position (double, in source frames), a playback rate with linear interpolation, a gain
 * that ramps linearly over each burst (no zipper noise, no clicks), loop and start offset; buses
 * with smoothed gains and a pause flag; a master [headroom] and a soft-knee limiter whose output
 * never exceeds [CEILING] (below 0 dBFS).
 *
 * A hold (a slot's own pause or its bus's) fades the voice out over one burst and a release fades
 * it back in over one burst, so the pause menu never clicks; the held voice keeps the place where
 * the hold began (the fade-out burst does not move it), as Roblox's `Pause` keeps `TimePosition`.
 *
 * Threading: everything here belongs to the mixer thread. The game thread talks to it only through
 * [MixerAudioOut] (a lock-free command ring and parameter blocks); the sounds arrive through [sounds]
 * from the loader threads. What the game thread may read back is published in [pubSeq], [pubEnded]
 * and [pubPos]. [render] performs no allocation.
 */
class Mixer(
    val slots: Int,
    /** Decoded sounds by id, written by the loaders (a slot looks its sound up when it starts or waits). */
    val sounds: AtomicReferenceArray<Pcm?>,
    val outRate: Int = 48_000,
    val maxBurst: Int = 1024,
) {
    // ---- per slot (mixer thread)
    private val pcm = arrayOfNulls<Pcm>(slots)
    private val sound = IntArray(slots) { -1 }
    private val active = BooleanArray(slots)
    private val waiting = BooleanArray(slots)
    private val paused = BooleanArray(slots)
    private val stopping = BooleanArray(slots)
    private val looping = BooleanArray(slots)
    private val pos = DoubleArray(slots)
    private val gain = FloatArray(slots)
    private val gainTarget = FloatArray(slots)
    private val rate = FloatArray(slots)
    private val bus = IntArray(slots)
    private val seq = IntArray(slots)

    /** 1 = the slot was heard at the end of the last burst, 0 = held (paused, its bus paused, or not started). */
    private val heard = FloatArray(slots)

    // ---- buses
    private val busGain = FloatArray(Bus.COUNT)
    private val busTarget = FloatArray(Bus.COUNT) { 1f }
    private val busPaused = BooleanArray(Bus.COUNT)
    private val busStart = FloatArray(Bus.COUNT)

    /** Master gain before the limiter (`09` 8.4 "fixed headroom"; see [SoundBank.HEADROOM]). */
    var headroom = 1f

    // ---- limiter
    private var env = 0f
    private val release = exp(-1.0 / (LIMITER_RELEASE * outRate)).toFloat()

    // ---- published for the game thread
    /** Sequence number of the voice now in each slot (0 = never played). */
    val pubSeq = AtomicIntegerArray(slots)

    /** Sequence number of the last voice in each slot that ended by itself or was dropped. */
    val pubEnded = AtomicIntegerArray(slots)

    /** Position of each slot's voice, seconds into its sound, as float bits. */
    val pubPos = AtomicIntegerArray(slots)

    /** Bursts rendered and the peak of the last one (diagnostics, "audio" status). */
    @Volatile var bursts = 0L
        private set

    @Volatile var limiting = 0f
        private set

    private val mixL = FloatArray(maxBurst)
    private val mixR = FloatArray(maxBurst)

    // ================================================================== commands (mixer thread)

    /** Starts [snd] in [slot] (see [AudioOut.play]); [id] is the game side's sequence number for it. */
    fun start(slot: Int, snd: Int, g: Float, r: Float, loop: Boolean, startAt: Float, b: Int, id: Int) {
        if (slot !in 0 until slots) return
        val p = sounds.get(snd)
        sound[slot] = snd
        bus[slot] = b
        looping[slot] = loop
        gainTarget[slot] = g
        rate[slot] = r
        seq[slot] = id
        paused[slot] = false
        stopping[slot] = false
        gain[slot] = 0f // a few ms of ramp from silence: no click, also when starting mid-file
        heard[slot] = 0f
        pubSeq.set(slot, id)
        pubPos.set(slot, java.lang.Float.floatToRawIntBits(startAt))
        if (p == null) {
            pcm[slot] = null
            if (loop) {
                // a loop waits for its sound (the layers start before they are decoded)
                active[slot] = true
                waiting[slot] = true
                pos[slot] = startAt.toDouble()
            } else {
                // a one-shot that is not loaded is dropped, never played late
                active[slot] = false
                waiting[slot] = false
                pubEnded.set(slot, id)
            }
            return
        }
        pcm[slot] = p
        active[slot] = true
        waiting[slot] = false
        pos[slot] = startPosition(p, startAt, loop)
        if (pos[slot] < 0.0) {
            active[slot] = false
            pubEnded.set(slot, id)
        }
    }

    /** Source frame for [startAt] seconds, wrapped for a loop; -1 when a one-shot starts past its end. */
    private fun startPosition(p: Pcm, startAt: Float, loop: Boolean): Double {
        var f = startAt.toDouble() * p.rate
        if (f < 0.0) f = 0.0
        if (f >= p.frames) {
            if (!loop) return -1.0
            f %= p.frames.toDouble()
        }
        return f
    }

    fun setParams(slot: Int, g: Float, r: Float) {
        if (slot !in 0 until slots || stopping[slot]) return
        gainTarget[slot] = g
        rate[slot] = r
    }

    /** Fades [slot] out over one burst, then frees it. */
    fun stop(slot: Int) {
        if (slot !in 0 until slots || !active[slot]) return
        stopping[slot] = true
        gainTarget[slot] = 0f
        // nothing audible to fade (waiting, or fully held): free it at once
        if (waiting[slot] || heard[slot] == 0f) finish(slot, false)
    }

    /** Holds [slot]: it fades out over the next burst and keeps its place (see the class comment). */
    fun pause(slot: Int) {
        if (slot in 0 until slots && active[slot]) paused[slot] = true
    }

    /** Releases a held [slot]: it fades back in over the next burst from where it was held. */
    fun resume(slot: Int) {
        if (slot in 0 until slots && active[slot]) paused[slot] = false
    }

    fun setBusGain(b: Int, g: Float) {
        if (b in 0 until Bus.COUNT) busTarget[b] = g
    }

    /** Holds (or releases) every slot of bus [b], each with the one-burst fade of [pause] / [resume]. */
    fun setBusPaused(b: Int, p: Boolean) {
        if (b in 0 until Bus.COUNT) busPaused[b] = p
    }

    /** Jumps every bus to its target (at start-up, so the first sounds do not swell in). */
    fun snapBuses() {
        for (b in 0 until Bus.COUNT) busGain[b] = busTarget[b]
    }

    private fun finish(slot: Int, ended: Boolean) {
        active[slot] = false
        waiting[slot] = false
        stopping[slot] = false
        paused[slot] = false
        pcm[slot] = null
        // a natural end (or a stop) tells the game side the voice is over
        pubEnded.set(slot, seq[slot])
        if (ended) pubPos.set(slot, java.lang.Float.floatToRawIntBits(0f))
    }

    // ================================================================== render (mixer thread)

    /**
     * Mixes [frames] stereo frames (interleaved float, -1..1) into [out] from index 0. Frames above
     * [maxBurst] are rendered in pieces.
     */
    fun render(out: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(maxBurst, frames - done)
            renderBurst(out, done * 2, n)
            done += n
        }
        bursts++
    }

    private fun renderBurst(out: FloatArray, offset: Int, n: Int) {
        val l = mixL
        val r = mixR
        java.util.Arrays.fill(l, 0, n, 0f)
        java.util.Arrays.fill(r, 0, n, 0f)
        // bus gains: one-pole per burst (tau BUS_TAU), ramped linearly inside it
        val k = 1f - exp(-n / (BUS_TAU * outRate)).toFloat()
        for (b in 0 until Bus.COUNT) {
            busStart[b] = busGain[b]
            busGain[b] += (busTarget[b] - busGain[b]) * k
            if (kotlin.math.abs(busGain[b] - busTarget[b]) < 1e-5f) busGain[b] = busTarget[b]
        }
        for (s in 0 until slots) {
            if (!active[s]) continue
            val b = bus[s]
            // a hold fades out over this burst, a release fades in; held on both ends: silent
            val runStart = heard[s]
            val runEnd = if (paused[s] || busPaused[b]) 0f else 1f
            heard[s] = runEnd
            if (runStart == 0f && runEnd == 0f) continue
            var p = pcm[s]
            if (p == null) {
                // a waiting loop: its sound may have arrived
                p = sounds.get(sound[s])
                if (p == null) continue
                pcm[s] = p
                waiting[s] = false
                val f = startPosition(p, pos[s].toFloat(), true)
                pos[s] = if (f < 0.0) 0.0 else f
            }
            mixSlot(s, p, b, n, l, r, runStart, runEnd)
        }
        // master: headroom, soft-knee limiter with a hard ceiling
        var peak = 0f
        var o = offset
        val h = headroom
        for (i in 0 until n) {
            var a = l[i] * h
            var c = r[i] * h
            val m = maxOf(kotlin.math.abs(a), kotlin.math.abs(c))
            env = if (m > env) m else env * release + m * (1f - release)
            if (env > KNEE) {
                val g = kneeOut(env) / env
                a *= g
                c *= g
            }
            if (m > peak) peak = m
            out[o] = a
            out[o + 1] = c
            o += 2
        }
        limiting = if (env > KNEE) 1f - kneeOut(env) / env else 0f
    }

    /** Mixes slot [s] into [l]/[r]; [runStart] and [runEnd] are its hold ramp (1 heard, 0 held) over the burst. */
    private fun mixSlot(s: Int, p: Pcm, b: Int, n: Int, l: FloatArray, r: FloatArray, runStart: Float, runEnd: Float) {
        val data = p.data
        val frames = p.frames
        val stereo = p.channels == 2
        val step = rate[s].toDouble() * p.rate / outRate
        val loop = looping[s]
        // gain ramp over the burst: slot gain x bus gain x hold, all linear from start to end
        val g0 = gain[s] * busStart[b] * runStart * SAMPLE_SCALE
        val g1 = gainTarget[s] * busGain[b] * runEnd * SAMPLE_SCALE
        val dg = (g1 - g0) / n
        var g = g0
        val held = pos[s]
        var x = held
        var ended = false
        var i = 0
        while (i < n) {
            val idx = x.toInt()
            val frac = (x - idx).toFloat()
            var next = idx + 1
            if (next >= frames) next = if (loop) 0 else idx
            g += dg
            if (stereo) {
                val a0 = data[idx * 2].toFloat()
                val a1 = data[next * 2].toFloat()
                val c0 = data[idx * 2 + 1].toFloat()
                val c1 = data[next * 2 + 1].toFloat()
                l[i] += (a0 + (a1 - a0) * frac) * g
                r[i] += (c0 + (c1 - c0) * frac) * g
            } else {
                val a0 = data[idx].toFloat()
                val a1 = data[next].toFloat()
                val v = (a0 + (a1 - a0) * frac) * g * MONO_PAN
                l[i] += v
                r[i] += v
            }
            x += step
            if (x >= frames) {
                if (loop) {
                    x -= frames
                    if (x >= frames) x %= frames.toDouble()
                } else {
                    ended = true
                    break
                }
            }
            i++
        }
        gain[s] = gainTarget[s]
        if (ended || stopping[s]) {
            pos[s] = x
            finish(s, ended)
        } else if (runEnd == 0f) {
            pos[s] = held // faded out into a hold: it keeps the place where the hold began
        } else {
            pos[s] = x
            pubPos.set(s, java.lang.Float.floatToRawIntBits((x / p.rate).toFloat()))
        }
    }

    /** Soft knee in the amplitude domain: identity up to [KNEE], then tanh towards [CEILING]. */
    private fun kneeOut(e: Float): Float = KNEE + (CEILING - KNEE) * tanh((e - KNEE) / (CEILING - KNEE))

    companion object {
        /** Limiter knee and ceiling (linear full scale): -6 dBFS and -0.18 dBFS. */
        const val KNEE = 0.5f
        const val CEILING = 0.98f

        /** Limiter release time constant, s. */
        const val LIMITER_RELEASE = 0.08

        /** Bus gain smoothing time constant, s (mutes and volume changes never click). */
        const val BUS_TAU = 0.03

        /** 16-bit to float. */
        const val SAMPLE_SCALE = 1f / 32768f

        /** A mono sound on both channels: -3 dB each (constant power). */
        const val MONO_PAN = 0.70710677f
    }
}
