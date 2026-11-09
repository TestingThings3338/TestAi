package com.starwake.desktop

import com.starwake.core.audio.Bus
import com.starwake.core.audio.MixerAudioOut
import com.starwake.core.audio.OggInfo
import com.starwake.core.audio.Pcm
import com.starwake.core.audio.SoundBank
import com.starwake.core.audio.VoiceFx
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The harness' [com.starwake.core.audio.AudioOut] (`09` 8.4 "desktop harness"): the real core
 * [com.starwake.core.audio.Mixer] behind the real lock-free front, run synchronously ([advance] after
 * every harness frame, so runs are deterministic), plus an event log of `(frame, slot, sound, gain,
 * rate, event)` for assertions.
 *
 * Two modes:
 * - **virtual** (default, every scenario): no decoding; each sound is silence of its true length
 *   (read from the OGG headers), so the game's audio logic runs exactly as on the phone at almost no
 *   cost;
 * - **real** ([decode] true): every file is decoded with ffmpeg (as the phone decodes with
 *   MediaCodec) and the mix can be written to a 48 kHz stereo WAV ([wav]).
 *
 * There is no text-to-speech on the desktop: a voice line without a recording is silence of a length
 * estimated from its text, so the queue and its timing behave as on the phone and the log shows every
 * line (DECISIONS 38). Recordings in assets/audio/voice are used and run through [VoiceFx].
 * Game thread; [advance] and the log allocate nothing.
 */
class RecordingAudioOut(
    private val assetsDir: File,
    private val decode: Boolean = false,
    private val wav: File? = null,
) : MixerAudioOut() {
    // ---------------------------------------------------------------- event log
    val logFrame = IntArray(LOG)
    val logSlot = IntArray(LOG)
    val logSound = IntArray(LOG)
    val logGain = FloatArray(LOG)
    val logRate = FloatArray(LOG)
    val logEvent = IntArray(LOG)
    var logCount = 0
        private set

    /** Harness frames advanced (the "frame" of the log). */
    var frame = 0
        private set

    /** Sounds that loaded / failed (missing file, ffmpeg absent). */
    var loadedCount = 0
        private set
    var failedCount = 0
        private set

    private var carry = 0.0
    private val burst = FloatArray(1024 * 2)
    private var out: RandomAccessFile? = null
    private val bytes = ByteBuffer.allocate(1024 * 4).order(ByteOrder.LITTLE_ENDIAN)
    private var wavFrames = 0L

    /** Peak and sum of squares of everything mixed (the WAV's level), and the limiter's work. */
    var mixPeak = 0f
        private set
    private var sumSquares = 0.0
    private var mixedFrames = 0L
    var limitingBursts = 0
        private set

    init {
        mixer.headroom = SoundBank.HEADROOM
        if (wav != null) {
            wav.parentFile?.mkdirs()
            out = RandomAccessFile(wav, "rw").also {
                it.setLength(0)
                it.write(ByteArray(44)) // header written at close
            }
        }
    }

    // ================================================================== loading

    override fun load(sound: Int, path: String, stream: Boolean) {
        val f = File(assetsDir, path)
        if (!f.isFile) {
            markFailed(sound)
            failedCount++
            return
        }
        if (decode) {
            val pcm = ffmpeg(f, if (stream) 2 else 1)
            if (pcm == null) {
                markFailed(sound)
                failedCount++
                return
            }
            val exact = (OggFile.seconds(f) * pcm.rate + 0.5).toLong()
            val trimmed = OggInfo.trim(pcm, exact)
            publish(sound, if (SoundBank.loopPadded(sound)) SoundBank.unpad(trimmed) else trimmed)
        } else {
            val seconds = OggFile.seconds(f)
            val padded = SoundBank.loopPadded(sound)
            val s = if (padded) seconds - 2 * SoundBank.LOOP_PAD_SECONDS else seconds
            publish(sound, silence(s, if (stream) 2 else 1))
        }
        loadedCount++
    }

    override fun loadVoice(sound: Int, path: String, text: String, radio: Boolean) {
        val f = File(assetsDir, path)
        if (f.isFile) {
            val pcm = if (decode) ffmpeg(f, 1) else null
            if (pcm != null) {
                publish(sound, Pcm(VoiceFx.process(pcm.data, 1, pcm.rate, radio), 1, pcm.rate))
            } else {
                publish(sound, silence(OggFile.seconds(f), 1))
            }
        } else {
            // no text-to-speech here: silence as long as the line would take
            publish(sound, silence(estimateSeconds(text), 1))
        }
        loadedCount++
    }

    private fun silence(seconds: Float, channels: Int): Pcm {
        val frames = maxOf(1, (seconds * SILENT_RATE).toInt())
        return Pcm(ShortArray(frames * channels), channels, SILENT_RATE)
    }

    /** Decodes [f] with ffmpeg to 16-bit PCM at 48 kHz (the mixer rate), [channels] channels; null on failure. */
    private fun ffmpeg(f: File, channels: Int): Pcm? = try {
        val p = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", f.path, "-f", "s16le", "-acodec", "pcm_s16le",
            "-ac", channels.toString(), "-ar", "48000", "-",
        ).redirectErrorStream(false).start()
        val raw = p.inputStream.readBytes()
        p.waitFor()
        if (p.exitValue() != 0 || raw.isEmpty()) {
            null
        } else {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val s = ShortArray(raw.size / 2)
            bb.asShortBuffer().get(s)
            Pcm(s, channels, 48000)
        }
    } catch (e: java.io.IOException) {
        null
    }

    // ================================================================== the log

    override fun play(slot: Int, sound: Int, gain: Float, rate: Float, loop: Boolean, startAt: Float, bus: Int) {
        super.play(slot, sound, gain, rate, loop, startAt, bus)
        log(slot, sound, gain, rate, if (loop) EV_LOOP else EV_PLAY)
    }

    override fun stop(slot: Int) {
        if (isActive(slot)) log(slot, slotSound[slot], 0f, 0f, EV_STOP)
        super.stop(slot)
    }

    override fun pause(slot: Int) {
        log(slot, slotSound[slot], 0f, 0f, EV_PAUSE)
        super.pause(slot)
    }

    override fun resume(slot: Int) {
        log(slot, slotSound[slot], 0f, 0f, EV_RESUME)
        super.resume(slot)
    }

    override fun setBusGain(bus: Int, gain: Float) {
        log(-1, bus, gain, 0f, EV_BUS)
        super.setBusGain(bus, gain)
    }

    override fun setBusPaused(bus: Int, paused: Boolean) {
        log(-1, bus, if (paused) 1f else 0f, 0f, EV_BUS_PAUSE)
        super.setBusPaused(bus, paused)
    }

    private fun log(slot: Int, sound: Int, gain: Float, rate: Float, event: Int) {
        val i = logCount
        if (i >= LOG) return
        logFrame[i] = frame
        logSlot[i] = slot
        logSound[i] = sound
        logGain[i] = gain
        logRate[i] = rate
        logEvent[i] = event
        logCount = i + 1
    }

    // ================================================================== time

    /** Mixes the audio of one harness frame of [nanos] (the mixer thread's work, done in step with the game). */
    fun advance(nanos: Long) {
        frame++
        carry += nanos * 48000.0 / 1e9
        var n = carry.toInt()
        carry -= n
        while (n > 0) {
            val k = minOf(n, 1024)
            renderBurst(burst, k)
            if (mixer.limiting > 0f) limitingBursts++
            val o = out
            for (i in 0 until k * 2) {
                val v = burst[i]
                val a = if (v < 0f) -v else v
                if (a > mixPeak) mixPeak = a
                sumSquares += (v * v).toDouble()
            }
            mixedFrames += k
            if (o != null) {
                bytes.clear()
                for (i in 0 until k * 2) {
                    val s = (burst[i] * 32767f).toInt()
                    bytes.putShort((if (s > 32767) 32767 else if (s < -32768) -32768 else s).toShort())
                }
                o.write(bytes.array(), 0, k * 4)
                wavFrames += k
            }
            n -= k
        }
    }

    /** RMS of the whole mix so far, dBFS. */
    val mixRmsDb: Double get() = if (mixedFrames == 0L) -120.0 else 10 * kotlin.math.log10(sumSquares / (mixedFrames * 2) + 1e-12)

    /** Finishes the WAV (header with the sizes). */
    fun close() {
        val o = out ?: return
        out = null
        val data = wavFrames * 4
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(48000).putInt(48000 * 4).putShort(4).putShort(16)
        h.put("data".toByteArray()).putInt(data.toInt())
        o.seek(0)
        o.write(h.array())
        o.close()
    }

    // ================================================================== reading the log (harness; allocates)

    class Event(val frame: Int, val slot: Int, val sound: Int, val gain: Float, val rate: Float, val event: Int) {
        val name: String get() = if (slot < 0) Bus.name(sound) else SoundBank.name(sound)

        override fun toString(): String =
            "[f%5d] %-9s slot %2d %-28s gain %.3f rate %.3f".format(frame, EVENT_NAMES[event], slot, name, gain, rate)
    }

    fun events(): List<Event> = (0 until logCount).map { Event(logFrame[it], logSlot[it], logSound[it], logGain[it], logRate[it], logEvent[it]) }

    companion object {
        const val LOG = 200_000
        const val EV_PLAY = 0
        const val EV_LOOP = 1
        const val EV_STOP = 2
        const val EV_PAUSE = 3
        const val EV_RESUME = 4
        const val EV_BUS = 5
        const val EV_BUS_PAUSE = 6
        val EVENT_NAMES = arrayOf("PLAY", "LOOP", "STOP", "PAUSE", "RESUME", "BUS", "BUSPAUSE")

        /** Silent stand-ins need no fidelity: a low rate keeps them small. */
        const val SILENT_RATE = 2000

        /** How long a line of [text] would take to say: about 13 characters a second plus a breath. */
        fun estimateSeconds(text: String): Float = 0.35f + text.length / 13f
    }
}

/** Length of an OGG file on disk from its first and last bytes ([com.starwake.core.audio.OggInfo]). */
object OggFile {
    fun seconds(f: File): Float {
        RandomAccessFile(f, "r").use { r ->
            val head = ByteArray(minOf(4096L, r.length()).toInt())
            r.readFully(head)
            val tailLen = minOf(65536L, r.length()).toInt()
            val tail = ByteArray(tailLen)
            r.seek(r.length() - tailLen)
            r.readFully(tail)
            return OggInfo.seconds(head, tail)
        }
    }
}
