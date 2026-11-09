package com.starwake.core.audio

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * [AudioOut] over a [Mixer], shared by the Android output and the desktop recorder. The game thread
 * and the mixer thread meet without locks and without allocation (`09` 8.2, 8.4):
 * - discrete events (play, stop, pause, resume, bus changes) go through a fixed single-producer,
 *   single-consumer ring of primitive records;
 * - the continuous values (gain and rate per slot) are a parameter block the game thread overwrites
 *   every frame and the mixer reads every burst (the latest value wins);
 * - what the game asks back ([isPlaying], [position]) comes from a game-side shadow of each slot
 *   plus the mixer's published sequence numbers and positions.
 *
 * Loading is the platform's: it decodes on its own threads and calls [publish]. Subclasses run
 * [renderBurst] on the mixer thread (Android) or synchronously (the harness).
 */
abstract class MixerAudioOut(
    val slots: Int = SoundBank.SLOTS,
    soundCapacity: Int = SoundBank.CAPACITY,
    outRate: Int = 48_000,
    maxBurst: Int = 1024,
) : AudioOut {
    /** Decoded sounds by id (loader threads write, the mixer reads). */
    protected val pcms = AtomicReferenceArray<Pcm?>(soundCapacity)
    private val failedFlags = AtomicReferenceArray<Boolean?>(soundCapacity)

    /** Seconds of a stream sound whose length the loader read from its header (0 = unknown). */
    protected val streamLength = FloatArray(soundCapacity)

    val mixer = Mixer(slots, pcms, outRate, maxBurst)

    // ---------------------------------------------------------------- command ring (SPSC)
    private val ringType = IntArray(RING)
    private val ringSlot = IntArray(RING)
    private val ringSound = IntArray(RING)
    private val ringGain = FloatArray(RING)
    private val ringRate = FloatArray(RING)
    private val ringStart = FloatArray(RING)
    private val ringFlag = IntArray(RING)
    private val ringSeq = IntArray(RING)
    private val writeIdx = AtomicInteger()
    private val readIdx = AtomicInteger()

    /** Commands dropped because the ring was full (the mixer stalled; diagnostics). */
    @Volatile var dropped = 0
        private set

    // ---------------------------------------------------------------- parameter block
    // Plain arrays tagged with the voice they belong to; the mixer applies a value whenever it
    // differs from the last one it used, so a torn or late read is corrected by the next burst.
    private val paramSeq = IntArray(slots)
    private val paramGain = FloatArray(slots)
    private val paramRate = FloatArray(slots) { 1f }
    private val paramFence = AtomicInteger()
    private val usedGain = FloatArray(slots)
    private val usedRate = FloatArray(slots)

    // ---------------------------------------------------------------- game-side shadow
    private val shadowSeq = IntArray(slots)
    private val shadowPlaying = BooleanArray(slots)
    private val shadowPaused = BooleanArray(slots)
    private val shadowStart = FloatArray(slots)
    private var nextSeq = 1

    /** The sound each slot was last told to play (-1 none); game thread. */
    val slotSound = IntArray(slots) { -1 }

    /** The gain each slot was last given (play or set); game thread (status, logs). */
    val slotGain = FloatArray(slots)

    /** Makes a decoded sound available (loader threads). */
    fun publish(sound: Int, pcm: Pcm) {
        if (sound in 0 until pcms.length()) pcms.set(sound, pcm)
    }

    /** Marks a sound as never loading (missing file, decode error, no text-to-speech voice). */
    fun markFailed(sound: Int) {
        if (sound in 0 until failedFlags.length()) failedFlags.set(sound, true)
    }

    override fun isLoaded(sound: Int): Boolean =
        sound in 0 until pcms.length() && (pcms.get(sound) != null || streamLength[sound] > 0f)

    override fun failed(sound: Int): Boolean = sound in 0 until failedFlags.length() && failedFlags.get(sound) == true

    override fun length(sound: Int): Float {
        if (sound !in 0 until pcms.length()) return 0f
        val p = pcms.get(sound)
        return p?.seconds ?: streamLength[sound]
    }

    // ================================================================== game thread

    override fun play(slot: Int, sound: Int, gain: Float, rate: Float, loop: Boolean, startAt: Float, bus: Int) {
        if (slot !in 0 until slots) return
        val id = nextSeq++
        shadowSeq[slot] = id
        shadowPlaying[slot] = true
        shadowPaused[slot] = false
        shadowStart[slot] = startAt
        slotSound[slot] = sound
        slotGain[slot] = gain
        paramSeq[slot] = id
        paramGain[slot] = gain
        paramRate[slot] = rate
        enqueue(CMD_PLAY, slot, sound, gain, rate, startAt, (if (loop) 1 else 0) or (bus shl 8), id)
    }

    override fun set(slot: Int, gain: Float, rate: Float) {
        if (slot !in 0 until slots) return
        slotGain[slot] = gain
        if (paramGain[slot] == gain && paramRate[slot] == rate) return
        paramGain[slot] = gain
        paramRate[slot] = rate
        paramFence.lazySet(paramFence.get() + 1) // release: the mixer sees the values after the fence
    }

    override fun stop(slot: Int) {
        if (slot !in 0 until slots) return
        shadowPlaying[slot] = false
        shadowPaused[slot] = false
        enqueue(CMD_STOP, slot, 0, 0f, 0f, 0f, 0, 0)
    }

    override fun pause(slot: Int) {
        if (slot !in 0 until slots || !shadowPlaying[slot]) return
        shadowPaused[slot] = true
        enqueue(CMD_PAUSE, slot, 0, 0f, 0f, 0f, 0, 0)
    }

    override fun resume(slot: Int) {
        if (slot !in 0 until slots || !shadowPaused[slot]) return
        shadowPaused[slot] = false
        enqueue(CMD_RESUME, slot, 0, 0f, 0f, 0f, 0, 0)
    }

    override fun isPlaying(slot: Int): Boolean {
        if (slot !in 0 until slots || !shadowPlaying[slot] || shadowPaused[slot]) return false
        return mixer.pubEnded.get(slot) != shadowSeq[slot]
    }

    /** Started and not stopped (also while paused): the slot holds a voice. */
    fun isActive(slot: Int): Boolean =
        slot in 0 until slots && shadowPlaying[slot] && mixer.pubEnded.get(slot) != shadowSeq[slot]

    override fun position(slot: Int): Float {
        if (slot !in 0 until slots) return 0f
        if (mixer.pubSeq.get(slot) != shadowSeq[slot]) return shadowStart[slot]
        return java.lang.Float.intBitsToFloat(mixer.pubPos.get(slot))
    }

    override fun setBusGain(bus: Int, gain: Float) {
        enqueue(CMD_BUS, 0, bus, gain, 0f, 0f, 0, 0)
    }

    override fun setBusPaused(bus: Int, paused: Boolean) {
        enqueue(CMD_BUS_PAUSE, 0, bus, 0f, 0f, 0f, if (paused) 1 else 0, 0)
    }

    private fun enqueue(type: Int, slot: Int, sound: Int, gain: Float, rate: Float, start: Float, flag: Int, seq: Int) {
        val w = writeIdx.get()
        if (w - readIdx.get() >= RING) {
            dropped++
            return
        }
        val i = w and (RING - 1)
        ringType[i] = type
        ringSlot[i] = slot
        ringSound[i] = sound
        ringGain[i] = gain
        ringRate[i] = rate
        ringStart[i] = start
        ringFlag[i] = flag
        ringSeq[i] = seq
        writeIdx.lazySet(w + 1) // release: the record is complete before the mixer can see it
    }

    // ================================================================== mixer thread

    /** Applies the pending commands and parameters, then mixes [frames] stereo frames into [out]. */
    fun renderBurst(out: FloatArray, frames: Int) {
        val m = mixer
        val w = writeIdx.get() // acquire
        var r = readIdx.get()
        while (r != w) {
            val i = r and (RING - 1)
            val slot = ringSlot[i]
            when (ringType[i]) {
                CMD_PLAY -> {
                    m.start(slot, ringSound[i], ringGain[i], ringRate[i], (ringFlag[i] and 1) != 0, ringStart[i], ringFlag[i] shr 8, ringSeq[i])
                    usedGain[slot] = ringGain[i]
                    usedRate[slot] = ringRate[i]
                }
                CMD_STOP -> m.stop(slot)
                CMD_PAUSE -> m.pause(slot)
                CMD_RESUME -> m.resume(slot)
                CMD_BUS -> m.setBusGain(ringSound[i], ringGain[i])
                CMD_BUS_PAUSE -> m.setBusPaused(ringSound[i], ringFlag[i] != 0)
            }
            r++
        }
        readIdx.lazySet(r)
        if (paramFence.get() != 0) { // acquire the parameter block
            for (s in 0 until slots) {
                if (paramSeq[s] != m.pubSeq.get(s)) continue // values of a voice not started yet
                val g = paramGain[s]
                val rt = paramRate[s]
                if (g != usedGain[s] || rt != usedRate[s]) {
                    usedGain[s] = g
                    usedRate[s] = rt
                    m.setParams(s, g, rt)
                }
            }
        }
        m.render(out, frames)
    }

    private companion object {
        const val RING = 1024
        const val CMD_PLAY = 1
        const val CMD_STOP = 2
        const val CMD_PAUSE = 3
        const val CMD_RESUME = 4
        const val CMD_BUS = 5
        const val CMD_BUS_PAUSE = 6
    }
}
