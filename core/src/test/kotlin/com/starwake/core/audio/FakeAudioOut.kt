package com.starwake.core.audio

/**
 * A deterministic [AudioOut] for the unit tests: sounds are "loaded" with a length the test sets,
 * slots advance only when the test calls [advance] (seconds), a one-shot ends at its length, a loop
 * wraps. Every play is logged (as "slot:sound" plus gain/rate) unless [logging] is off (allocation
 * tests).
 */
class FakeAudioOut(capacity: Int = SoundBank.CAPACITY, val slots: Int = SoundBank.SLOTS) : AudioOut {
    val loaded = BooleanArray(capacity)
    val lengths = FloatArray(capacity)
    val failedFlags = BooleanArray(capacity)
    val declared = arrayOfNulls<String>(capacity)

    val sound = IntArray(slots) { -1 }
    val gain = FloatArray(slots)
    val rate = FloatArray(slots)
    val loop = BooleanArray(slots)
    val playingFlag = BooleanArray(slots)
    val pausedFlag = BooleanArray(slots)
    val pos = FloatArray(slots)
    val busGain = FloatArray(Bus.COUNT) { 1f }

    /** A slot that never ends (a sound that hangs), for the voice's safety deadline. */
    val stuck = BooleanArray(slots)
    val busPaused = BooleanArray(Bus.COUNT)

    /** Every play as "slot:SoundName gain rate start"; stops as "stop slot". */
    val log = ArrayList<String>()
    var logging = true
    var plays = 0

    /** Sounds played (ids), in order. */
    val played = ArrayList<Int>()

    fun loadAllWith(seconds: Float) {
        for (i in loaded.indices) {
            loaded[i] = true
            lengths[i] = seconds
        }
    }

    fun setLength(sound: Int, seconds: Float) {
        loaded[sound] = true
        lengths[sound] = seconds
    }

    override fun load(sound: Int, path: String, stream: Boolean) {
        declared[sound] = path
    }

    override fun isLoaded(sound: Int): Boolean = sound in loaded.indices && loaded[sound]

    override fun failed(sound: Int): Boolean = sound in failedFlags.indices && failedFlags[sound]

    override fun length(sound: Int): Float = if (isLoaded(sound)) lengths[sound] else 0f

    override fun play(slot: Int, sound: Int, gain: Float, rate: Float, loop: Boolean, startAt: Float, bus: Int) {
        plays++
        this.sound[slot] = sound
        this.gain[slot] = gain
        this.rate[slot] = rate
        this.loop[slot] = loop
        pausedFlag[slot] = false
        pos[slot] = startAt
        // like the mixer: a one-shot that is not loaded never plays
        playingFlag[slot] = isLoaded(sound) || loop
        if (logging) {
            played.add(sound)
            log.add("$slot:${SoundBank.name(sound)} g=${"%.3f".format(gain)} r=${"%.3f".format(rate)} at=${"%.2f".format(startAt)}")
        }
    }

    override fun set(slot: Int, gain: Float, rate: Float) {
        this.gain[slot] = gain
        this.rate[slot] = rate
    }

    override fun stop(slot: Int) {
        playingFlag[slot] = false
        pausedFlag[slot] = false
        if (logging) log.add("stop $slot")
    }

    override fun pause(slot: Int) {
        if (playingFlag[slot]) pausedFlag[slot] = true
    }

    override fun resume(slot: Int) {
        pausedFlag[slot] = false
    }

    override fun isPlaying(slot: Int): Boolean = playingFlag[slot] && !pausedFlag[slot]

    override fun position(slot: Int): Float = pos[slot]

    override fun setBusGain(bus: Int, gain: Float) {
        busGain[bus] = gain
    }

    override fun setBusPaused(bus: Int, paused: Boolean) {
        busPaused[bus] = paused
    }

    /** Lets [seconds] of playback pass: positions advance, one-shots end, loops wrap. */
    fun advance(seconds: Float) {
        for (s in 0 until slots) {
            if (!playingFlag[s] || pausedFlag[s] || stuck[s]) continue
            val snd = sound[s]
            if (!isLoaded(snd)) continue
            pos[s] += seconds * rate[s]
            val len = lengths[snd]
            if (pos[s] >= len) {
                if (loop[s] && len > 0f) pos[s] %= len else playingFlag[s] = false
            }
        }
    }

    /** Names of the sounds played since the last [clearLog], in order. */
    fun playedNames(): List<String> = played.map { SoundBank.name(it) }

    fun clearLog() {
        log.clear()
        played.clear()
    }
}
