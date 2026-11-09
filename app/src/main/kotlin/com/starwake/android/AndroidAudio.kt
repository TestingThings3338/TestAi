package com.starwake.android

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.starwake.core.audio.Bus
import com.starwake.core.audio.MixerAudioOut
import com.starwake.core.audio.OggInfo
import com.starwake.core.audio.Pcm
import com.starwake.core.audio.SoundBank
import com.starwake.core.audio.VoiceFx
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport

/**
 * The game's sound on Android (`09` 8.4): the core [com.starwake.core.audio.Mixer] fed into one
 * AudioTrack (float, 48 kHz stereo, PERFORMANCE_MODE_LOW_LATENCY, USAGE_GAME) by a mixer thread at
 * urgent-audio priority; every effect and voice line decoded once (MediaExtractor + MediaCodec) on
 * a loader thread; the two music slots streamed by MediaPlayers on their own thread, with pause and
 * resume at the same position; audio focus (music ducks on a transient loss, pauses on a loss);
 * [onPause]/[onResume] hold and continue everything.
 *
 * The game thread only calls the non-allocating [com.starwake.core.audio.AudioOut] methods; nothing
 * here blocks it. A sound that is not decoded yet is silent; a voice line without the user's
 * recording is spoken by [AndroidVoiceBaker] (text-to-speech, cached).
 */
internal class AndroidAudio(private val context: Context) : MixerAudioOut() {
    private val assets: AssetManager = context.assets
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "audio-loader")
    }
    private val baker = AndroidVoiceBaker(context, this)

    // ---------------------------------------------------------------- output
    private var track: AudioTrack? = null
    private var mixerThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var held = false
    @Volatile var underruns = 0
        private set

    // ---------------------------------------------------------------- music (MediaPlayer per music slot)
    private val musicThread = HandlerThread("audio-music").also { it.start() }
    private val musicHandler = Handler(musicThread.looper)
    private val music = arrayOf(MusicSlot(SoundBank.SLOT_MUSIC_FLIGHT), MusicSlot(SoundBank.SLOT_MUSIC_MENU))
    private val streamPath = arrayOfNulls<String>(SoundBank.CAPACITY)
    @Volatile private var musicBus = 1f
    @Volatile private var duck = 1f
    @Volatile private var focusPaused = false

    // ---------------------------------------------------------------- focus
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(ATTRIBUTES)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, musicHandler)
        .build()

    init {
        mixer.headroom = SoundBank.HEADROOM
    }

    // ================================================================== lifecycle (UI thread)

    /** Starts the output (first resume) or continues it. */
    fun onResume() {
        if (track == null) startOutput()
        held = false
        track?.play()
        mixerThread?.let { LockSupport.unpark(it) }
        audioManager?.requestAudioFocus(focusRequest)
        focusPaused = false
        for (m in music) musicHandler.post(m.applyState)
    }

    /** The app leaves the screen: everything holds where it is. */
    fun onPause() {
        held = true
        track?.pause()
        for (m in music) musicHandler.post(m.applyState)
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    fun release() {
        running = false
        held = false
        mixerThread?.let { LockSupport.unpark(it) }
        mixerThread?.join(500)
        track?.release()
        track = null
        for (m in music) musicHandler.post(m.release)
        musicThread.quitSafely()
        loader.shutdownNow()
        baker.shutdown()
    }

    private fun startOutput() {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, BURST * 2 * 4 * 3))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: RuntimeException) {
            Log.w(TAG, "no audio output", e)
            return
        }
        track = t
        mixer.snapBuses()
        running = true
        mixerThread = Thread({ mixLoop(t) }, "audio-mixer").also { it.start() }
    }

    /** The mixer thread: one burst after the other, the blocking write paces it. Allocates nothing. */
    private fun mixLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(BURST * 2)
        while (running) {
            if (held) {
                LockSupport.park(this)
                continue
            }
            renderBurst(buf, BURST)
            val n = t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            if (n < 0) {
                // a dead track (device change): wait a little instead of spinning
                LockSupport.parkNanos(this, 20_000_000L)
            }
            underruns = t.underrunCount
        }
    }

    // ================================================================== loading (loader thread)

    override fun load(sound: Int, path: String, stream: Boolean) {
        if (stream) {
            streamPath[sound] = path
            loader.execute { probeStream(sound, path) }
        } else {
            loader.execute { loadEffect(sound, path) }
        }
    }

    override fun loadVoice(sound: Int, path: String, text: String, radio: Boolean) {
        val key = path.substringAfterLast('/').substringBefore('.')
        loader.execute {
            // the user's own dry recording wins; else the device's voice speaks the text
            val pcm = if (exists(path)) decode(path) else null
            if (pcm != null) {
                publish(sound, Pcm(VoiceFx.process(pcm.data, pcm.channels, pcm.rate, radio), 1, pcm.rate))
            } else {
                baker.request(sound, key, text, radio)
            }
        }
    }

    private fun loadEffect(sound: Int, path: String) {
        val pcm = decode(path)
        if (pcm == null) {
            markFailed(sound)
            return
        }
        publish(sound, if (SoundBank.loopPadded(sound)) SoundBank.unpad(pcm) else pcm)
    }

    private fun probeStream(sound: Int, path: String) {
        val ex = MediaExtractor()
        try {
            assets.openFd(path).use { afd ->
                ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                val f = ex.getTrackFormat(0)
                val us = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                if (us > 0) publishStream(sound, us / 1e6f) else markFailed(sound)
            }
        } catch (e: IOException) {
            markFailed(sound)
        } catch (e: RuntimeException) {
            markFailed(sound)
        } finally {
            ex.release()
        }
    }

    private fun exists(path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (e: IOException) {
        false
    }

    /** Decodes an asset to 16-bit PCM at its own rate, cut to its exact length (OGG end trim). */
    private fun decode(path: String): Pcm? {
        val bytes = try {
            assets.open(path).use { it.readBytes() }
        } catch (e: IOException) {
            return null
        }
        val exact = OggInfo.frames(bytes)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            assets.openFd(path).use { afd -> ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
            if (ex.trackCount < 1) return null
            val inFormat = ex.getTrackFormat(0)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null
            ex.selectTrack(0)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(inFormat, null, null, 0)
            c.start()
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var out = ShortArray(65536)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var done = false
            while (!done) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)!!
                        val size = ex.readSampleData(b, 0)
                        if (size < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val b = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    b.position(info.offset)
                    b.limit(info.offset + info.size)
                    val count = if (float) info.size / 4 else info.size / 2
                    if (n + count > out.size) out = out.copyOf(maxOf(out.size * 2, n + count))
                    if (float) {
                        val fb = b.asFloatBuffer()
                        for (k in 0 until count) out[n + k] = (fb.get(k).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    } else {
                        b.asShortBuffer().get(out, n, count)
                    }
                    n += count
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
            if (n == 0 || channels < 1) return null
            return OggInfo.trim(Pcm(out.copyOf(n - n % channels), channels, rate), exact)
        } catch (e: IOException) {
            Log.w(TAG, "decode $path", e)
            return null
        } catch (e: RuntimeException) {
            Log.w(TAG, "decode $path", e)
            return null
        } finally {
            codec?.let {
                try {
                    it.stop()
                } catch (_: RuntimeException) {
                }
                it.release()
            }
            ex.release()
        }
    }

    // ================================================================== music slots (game thread side)

    private fun musicSlot(slot: Int): MusicSlot? =
        if (slot == SoundBank.SLOT_MUSIC_FLIGHT) music[0] else if (slot == SoundBank.SLOT_MUSIC_MENU) music[1] else null

    override fun play(slot: Int, sound: Int, gain: Float, rate: Float, loop: Boolean, startAt: Float, bus: Int) {
        val m = musicSlot(slot)
        if (m != null && sound in streamPath.indices && streamPath[sound] != null) {
            m.play(streamPath[sound]!!, gain, loop, startAt)
        } else {
            super.play(slot, sound, gain, rate, loop, startAt, bus)
        }
    }

    override fun set(slot: Int, gain: Float, rate: Float) {
        val m = musicSlot(slot)
        if (m != null && m.active) m.setGain(gain) else super.set(slot, gain, rate)
    }

    override fun stop(slot: Int) {
        val m = musicSlot(slot)
        if (m != null && m.active) m.stop() else super.stop(slot)
    }

    override fun pause(slot: Int) {
        val m = musicSlot(slot)
        if (m != null && m.active) m.pause() else super.pause(slot)
    }

    override fun resume(slot: Int) {
        val m = musicSlot(slot)
        if (m != null && m.active) m.resume() else super.resume(slot)
    }

    override fun isPlaying(slot: Int): Boolean {
        val m = musicSlot(slot)
        return if (m != null && m.active) m.isPlaying() else super.isPlaying(slot)
    }

    override fun position(slot: Int): Float {
        val m = musicSlot(slot)
        return if (m != null && m.active) m.positionMs / 1000f else super.position(slot)
    }

    override fun setBusGain(bus: Int, gain: Float) {
        if (bus == Bus.MUSIC) {
            if (gain != musicBus) {
                musicBus = gain
                for (m in music) m.volumeChanged()
            }
        } else {
            super.setBusGain(bus, gain)
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> focusPaused = true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck = DUCK
            AudioManager.AUDIOFOCUS_GAIN -> {
                duck = 1f
                focusPaused = false
            }
        }
        for (m in music) m.applyState.run()
    }

    /**
     * One streamed music slot. The game thread writes the wanted state into volatile fields and posts
     * a preallocated Runnable; the music thread applies it to its MediaPlayer (prepare, seek, start,
     * pause, volume) and reports position and completion back.
     */
    private inner class MusicSlot(val slot: Int) {
        @Volatile var active = false
        @Volatile private var wantPath: String? = null
        @Volatile private var wantLoop = false
        @Volatile private var wantStart = 0f
        @Volatile private var wantPlaying = false
        @Volatile private var wantPaused = false
        @Volatile private var gain = 0f
        @Volatile private var generation = 0
        @Volatile private var ended = false
        @Volatile var positionMs = 0

        private var player: MediaPlayer? = null
        private var appliedGeneration = -1
        private var prepared = false
        @Volatile private var pendingVolume = false

        val applyState = Runnable { apply() }
        val release = Runnable {
            player?.release()
            player = null
        }
        private val applyVolume = Runnable {
            pendingVolume = false
            volume()
        }
        private val poll = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && prepared) {
                    try {
                        positionMs = p.currentPosition
                    } catch (_: IllegalStateException) {
                    }
                }
                if (active) musicHandler.postDelayed(this, 100)
            }
        }

        fun play(path: String, g: Float, loop: Boolean, startAt: Float) {
            wantPath = path
            wantLoop = loop
            wantStart = startAt
            wantPlaying = true
            wantPaused = false
            gain = g
            ended = false
            positionMs = (startAt * 1000).toInt()
            generation++
            if (!active) {
                active = true
                musicHandler.post(poll)
            }
            musicHandler.post(applyState)
        }

        fun setGain(g: Float) {
            if (g == gain) return
            gain = g
            volumeChanged()
        }

        fun volumeChanged() {
            if (!pendingVolume) {
                pendingVolume = true
                musicHandler.post(applyVolume)
            }
        }

        fun stop() {
            wantPlaying = false
            active = false
            musicHandler.post(applyState)
        }

        fun pause() {
            wantPaused = true
            musicHandler.post(applyState)
        }

        fun resume() {
            wantPaused = false
            musicHandler.post(applyState)
        }

        fun isPlaying(): Boolean = active && wantPlaying && !wantPaused && !ended

        // ---- music thread
        private fun apply() {
            if (!wantPlaying) {
                player?.let {
                    try {
                        if (prepared && it.isPlaying) it.stop()
                    } catch (_: IllegalStateException) {
                    }
                    it.reset()
                }
                prepared = false
                appliedGeneration = -1
                return
            }
            val gen = generation
            if (gen != appliedGeneration) {
                appliedGeneration = gen
                prepared = false
                val p = player ?: MediaPlayer().also { mp ->
                    mp.setAudioAttributes(ATTRIBUTES)
                    mp.setOnCompletionListener { if (!wantLoop) ended = true }
                    player = mp
                }
                try {
                    p.reset()
                    assets.openFd(wantPath!!).use { afd -> p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
                    p.isLooping = wantLoop
                    p.prepare()
                    prepared = true
                    if (wantStart > 0f) p.seekTo((wantStart * 1000).toInt())
                } catch (e: IOException) {
                    Log.w(TAG, "music $wantPath", e)
                    ended = true
                    return
                } catch (e: RuntimeException) {
                    Log.w(TAG, "music $wantPath", e)
                    ended = true
                    return
                }
            }
            val p = player ?: return
            if (!prepared) return
            volume()
            val shouldRun = !wantPaused && !held && !focusPaused
            try {
                if (shouldRun && !p.isPlaying && !ended) p.start() else if (!shouldRun && p.isPlaying) p.pause()
            } catch (_: IllegalStateException) {
            }
        }

        private fun volume() {
            val p = player ?: return
            if (!prepared) return
            val v = (gain * musicBus * duck * SoundBank.HEADROOM).coerceIn(0f, 1f)
            try {
                p.setVolume(v, v)
            } catch (_: IllegalStateException) {
            }
        }
    }

    private companion object {
        const val TAG = "StarwakeAudio"
        const val RATE = 48_000

        /** Frames per mixer burst (5.3 ms at 48 kHz). */
        const val BURST = 256

        /** Music level while another app holds transient focus. */
        const val DUCK = 0.25f

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
