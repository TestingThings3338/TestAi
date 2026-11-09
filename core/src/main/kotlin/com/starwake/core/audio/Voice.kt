package com.starwake.core.audio

import com.starwake.core.ui.TextBuf

/**
 * The announcer queue, a 1:1 port of Roblox `ShipVoice` (`09` 2.13): one line at a time with a
 * fixed gap; tiers STATUS < ADVISORY < CAUTION < WARNING sort the queue (stable within a tier);
 * WARNING cuts lower lines and plays the alarm tone first; the countdown ([VoiceLine.timed]) cuts
 * STATUS and ADVISORY; the same line at most every RepeatCooldown; queued lines expire by tier.
 *
 * Lines come from [out] (sound [SoundBank.voice]); a line whose sound is not loaded is dropped by
 * [say] (Roblox: a key without an id), so a platform without voice audio simply stays silent. The
 * Computer/Control effect chains are baked into the PCM by the platform ([VoiceFx]); both voices
 * play at [VoiceConfig.LINE_GAIN], the tone dry at ToneVolume, all through [Bus.VOICE].
 *
 * Game thread; fixed arrays, no allocation.
 */
class Voice(private val out: AudioOut, private val clock: AudioClock) {
    // the queue: parallel arrays, index 0 = head
    private val qKey = IntArray(CAPACITY)
    private val qAt = DoubleArray(CAPACITY)
    private val qToned = BooleanArray(CAPACITY)
    private var qSize = 0

    // the current line or tone: { sound, key, rank, timed, since, loadedAt, after }
    private var curKey = -1
    private var curTone = false
    private var curSince = 0.0
    private var curLoadedAt = Double.NaN

    private val lastPlayed = DoubleArray(VoiceLine.COUNT) { Double.NaN }
    private var busyUntil = 0.0

    /** Lines said (accepted by [say]) and played, for the harness and tests. */
    var accepted = 0
        private set
    var played = 0
        private set

    /** Effects volume and master (the voice group = master x Voice.Volume x effects, applied live). */
    fun setVolumes(master: Float, effects: Float) {
        if (master == lastMaster && effects == lastEffects) return
        lastMaster = master
        lastEffects = effects
        out.setBusGain(Bus.VOICE, master * VoiceConfig.VOLUME * effects)
    }

    private var lastMaster = -1f
    private var lastEffects = -1f

    /** The line has a sound (Roblox `has`): recorded or synthesised, and decoded. */
    fun has(line: VoiceLine): Boolean = out.isLoaded(SoundBank.voice(line))

    private fun maxWait(key: Int): Float {
        val line = VoiceLine.of(key)
        return if (line.timed) VoiceConfig.TIMED_MAX_WAIT else line.tier.maxWait
    }

    /** Does the new line cut the current one? */
    private fun cuts(entryKey: Int): Boolean {
        val entry = VoiceLine.of(entryKey)
        val current = VoiceLine.of(curKey)
        if (entry.tier.interrupts && current.rank < entry.rank) return true
        if (entry.timed && !current.timed && current.rank <= 2f) return true
        return false
    }

    private fun stopCurrent() {
        if (curKey >= 0) {
            val slot = if (curTone) SoundBank.SLOT_VOICE_TONE else SoundBank.SLOT_VOICE_LINE
            if (out.isPlaying(slot)) out.stop(slot)
            curKey = -1
            curTone = false
        }
    }

    /** By rank: before the first lower entry, else at the end. */
    private fun insert(key: Int, at: Double, toned: Boolean) {
        if (qSize >= CAPACITY) return
        val rank = VoiceLine.of(key).rank
        var index = qSize
        for (i in 0 until qSize) {
            if (VoiceLine.of(qKey[i]).rank < rank) {
                index = i
                break
            }
        }
        for (i in qSize downTo index + 1) {
            qKey[i] = qKey[i - 1]
            qAt[i] = qAt[i - 1]
            qToned[i] = qToned[i - 1]
        }
        qKey[index] = key
        qAt[index] = at
        qToned[index] = toned
        qSize++
    }

    private fun removeAt(index: Int) {
        for (i in index until qSize - 1) {
            qKey[i] = qKey[i + 1]
            qAt[i] = qAt[i + 1]
            qToned[i] = qToned[i + 1]
        }
        qSize--
    }

    /** Queues [line]; false when it was dropped (`say`, :221). */
    fun say(line: VoiceLine): Boolean {
        if (!has(line)) return false
        val now = clock.now
        val key = line.ordinal
        // the same line not over and over
        val last = lastPlayed[key]
        if (!last.isNaN() && now - last < VoiceConfig.REPEAT_COOLDOWN) return false
        // playing now (also its tone) or already queued
        if (curKey == key) return false
        for (i in 0 until qSize) if (qKey[i] == key) return false

        // full queue: the lowest entry at the tail gives way to a more important one
        if (qSize >= VoiceConfig.QUEUE_LIMIT) {
            val tail = VoiceLine.of(qKey[qSize - 1])
            if (tail.rank >= line.rank) return false
            qSize--
        }
        insert(key, now, false)

        // WARNING and the countdown cut the current line
        if (curKey >= 0 && cuts(key)) {
            stopCurrent()
            busyUntil = 0.0
        }
        accepted++
        return true
    }

    /** Says several lines in this order (a tier keeps their order). */
    fun sayAll(lines: Array<VoiceLine>) {
        for (l in lines) say(l)
    }

    /** Stops the current line and empties the queue (the crash, `interrupt`, :280). */
    fun interrupt() {
        stopCurrent()
        qSize = 0
        busyUntil = 0.0
    }

    private fun start(key: Int, tone: Boolean) {
        curKey = key
        curTone = tone
        curSince = clock.now
        curLoadedAt = Double.NaN
        if (tone) {
            out.play(SoundBank.SLOT_VOICE_TONE, VoiceConfig.WARNING_TONE, VoiceConfig.TONE_VOLUME, 1f, false, 0f, Bus.VOICE)
        } else {
            out.play(SoundBank.SLOT_VOICE_LINE, SoundBank.VOICE_BASE + key, VoiceConfig.LINE_GAIN, 1f, false, 0f, Bus.VOICE)
            played++
        }
    }

    /** Every frame: flight, decks and the destruction frame (`update`, :299). */
    fun update() {
        val now = clock.now
        if (curKey >= 0) {
            val tone = curTone
            val slot = if (tone) SoundBank.SLOT_VOICE_TONE else SoundBank.SLOT_VOICE_LINE
            val sound = if (tone) VoiceConfig.WARNING_TONE else SoundBank.VOICE_BASE + curKey
            // the length is known once loaded; measure from then
            if (curLoadedAt.isNaN() && out.isLoaded(sound) && out.length(sound) > 0f) curLoadedAt = now
            val deadline = if (!curLoadedAt.isNaN()) {
                curLoadedAt + out.length(sound) / RATE + VoiceConfig.END_SLACK
            } else {
                curSince + if (tone) VoiceConfig.MAX_UNKNOWN_TONE else VoiceConfig.MAX_UNKNOWN_LINE
            }
            val playing = out.isPlaying(slot)
            if (playing && now < deadline) return
            // stuck: stop it, so it never bursts into the next line
            if (playing) out.stop(slot)
            val key = curKey
            curKey = -1
            curTone = false
            if (tone) {
                // the tone is over: now the line behind it, freshly queued
                insert(key, now, true)
                busyUntil = now + VoiceConfig.TONE_GAP
            } else {
                busyUntil = now + if (VoiceLine.of(key).timed) VoiceConfig.TIMED_GAP else VoiceConfig.GAP
            }
        }

        if (now < busyUntil || qSize == 0) return

        // expired entries go (every tier has its own wait)
        var i = 0
        while (i < qSize) {
            if (now - qAt[i] > maxWait(qKey[i])) removeAt(i) else i++
        }
        if (qSize == 0) return

        val key = qKey[0]
        val toned = qToned[0]
        removeAt(0)
        val line = VoiceLine.of(key)
        if (!has(line)) return

        // the tone first for tiers that have one
        if (line.tier.hasTone && out.isLoaded(VoiceConfig.WARNING_TONE) && !toned && !line.timed && !line.noTone) {
            start(key, true)
            return
        }
        lastPlayed[key] = now
        start(key, false)
    }

    fun isSpeaking(): Boolean = curKey >= 0

    /** What plays ("TONE>Key" or "Key"), null when quiet. */
    val playing: VoiceLine? get() = if (curKey >= 0) VoiceLine.of(curKey) else null

    val playingTone: Boolean get() = curKey >= 0 && curTone

    /** Lines waiting. */
    val queued: Int get() = qSize

    fun queuedAt(i: Int): VoiceLine = VoiceLine.of(qKey[i])

    /** Status for tests and the "audio" debug command (`getStatus`, :380). */
    fun status(buf: TextBuf): TextBuf {
        buf.append("voice ")
        if (curKey >= 0) {
            if (curTone) buf.append("TONE>")
            buf.append(VoiceLine.of(curKey).name)
        } else {
            buf.append('-')
        }
        buf.append(" queue [")
        for (i in 0 until qSize) {
            if (i > 0) buf.append(' ')
            buf.append(VoiceLine.of(qKey[i]).name)
        }
        buf.append("] lines ").append(loadedLines())
        return buf
    }

    /** Lines with a sound (the Roblox line count). */
    fun loadedLines(): Int {
        var n = 0
        for (l in VoiceLine.ALL) if (has(l)) n++
        return n
    }

    private companion object {
        /** QueueLimit 4, plus the line coming back after its tone. */
        const val CAPACITY = 8

        /** Lines play at PlaybackSpeed 1 (max(PlaybackSpeed, 0.01) in the deadline). */
        const val RATE = 1f
    }
}
